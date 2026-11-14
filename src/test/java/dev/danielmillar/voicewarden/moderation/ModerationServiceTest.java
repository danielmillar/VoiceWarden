package dev.danielmillar.voicewarden.moderation;

import dev.danielmillar.voicewarden.TestFixtures;
import dev.danielmillar.voicewarden.config.*;
import dev.danielmillar.voicewarden.evidence.*;
import dev.danielmillar.voicewarden.health.Metrics;
import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.moderation.rules.*;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.notify.StaffAlertService;
import dev.danielmillar.voicewarden.notify.discord.*;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.stt.Transcript;
import dev.danielmillar.voicewarden.audio.AudioSegment;
import dev.danielmillar.voicewarden.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.UserManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ModerationServiceTest {
    @TempDir Path folder;
    private final Logger log = Logger.getLogger("ModerationServiceTest");
    private final AtomicInteger writes = new AtomicInteger();
    private final UUID player = TestFixtures.PLAYER;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<CompletableFuture<List<String>>> asked = new ArrayList<>();
    private MuteService mutes;
    private OffenseTracker offenses;
    private Metrics metrics;
    private TranscriptBuffer buffer;

    @AfterEach void close() { http.close(); }

    private LuckPermsHook hook() {
        UserManager users = Fakes.proxy(UserManager.class, (p,m,a) -> switch (m.getName()) {
            case "modifyUser" -> { writes.incrementAndGet(); yield CompletableFuture.completedFuture(null); }
            case "getUser" -> null;
            default -> throw new AssertionError(m);
        });
        return new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p,m,a) -> {
            if (m.getName().equals("getUserManager")) return users;
            throw new AssertionError(m);
        }));
    }
    private PluginConfig config() throws Exception {
        var cfg = Fakes.config(folder);
        cfg = Fakes.with(cfg, "alerts", new PluginConfig.Alerts(false, false));
        cfg = Fakes.with(cfg, "recordings", new EvidenceSettings(EvidenceSettings.Mode.NONE, folder, false, 0, 0));
        return Fakes.with(cfg, "mute", Fakes.with(cfg.mute(), "notifyPlayer", false));
    }
    private static PluginConfig doubleCheck(PluginConfig cfg) {
        return Fakes.with(cfg, "speechToText", Fakes.with(cfg.speechToText(), "doubleCheck", true));
    }
    /** The second model answers only when the test completes the future it was handed. */
    private ModerationService moderation(PluginConfig settings) throws Exception {
        var messages = Messages.load(folder.resolve("messages.yml"));
        var players = new OnlinePlayers();
        var database = Fakes.database(folder);
        mutes = new MuteService(() -> settings, () -> messages, hook(), players, Runnable::run, log, database);
        offenses = new OffenseTracker(database, log);
        metrics = new Metrics();
        buffer = new TranscriptBuffer(new TranscriptBuffer.Limits(Duration.ofHours(1), 10, 0));
        var rules = RuleEngine.compile(List.of(
                new RuleDefinition("slur", "Slur", RuleAction.MUTE, 1, true, List.of("badword"), List.of(), List.of(), List.of()),
                new RuleDefinition("threat", "Threat", RuleAction.MUTE, 1, true, List.of("worseword"), List.of(), List.of(), List.of()),
                new RuleDefinition("swear", "Swear", RuleAction.FLAG, 1, true, List.of("darn"), List.of(), List.of(), List.of())),
                Set.of());
        return new ModerationService(() -> settings, () -> messages, () -> rules, buffer, offenses, mutes,
                new StaffAlertService(() -> settings, () -> messages, players, log),
                new DiscordWebhookService(DiscordSettings::disabled, http, log),
                new EvidenceStore(settings::recordings, Runnable::run, log), new CustomCommandRunner(null, log),
                new TranscriptLog(folder.resolve("logs"), Runnable::run, log), players, metrics, log,
                segment -> { var answer = new CompletableFuture<List<String>>(); asked.add(answer); return answer; });
    }
    private Transcript said(String text) {
        var segment = new AudioSegment(player, "Alex", TestFixtures.AUDIO, 16000, Instant.now(), System.nanoTime(), null, false);
        return new Transcript(segment, text, 0);
    }

    @Test void disabledMasterSwitchDiscardsQueuedTranscriptWithoutModeration() throws Exception {
        var moderation = moderation(Fakes.with(config(), "enabled", false));
        moderation.onTranscript(said("badword"));
        assertFalse(mutes.isMuted(player));
        assertEquals(0, metrics.autoMutes.sum());
        assertEquals(0, writes.get());
        assertTrue(buffer.recentLines(player, 10).isEmpty());
    }

    @Test void hitTheSecondModelAlsoHearsIsPunishedOnlyOnceItAnswers() throws Exception {
        var moderation = moderation(doubleCheck(config()));
        moderation.onTranscript(said("you badword"));
        assertEquals(1, asked.size());
        assertFalse(mutes.isMuted(player));
        assertEquals(1, offenses.peekNext(player, 30));

        asked.getFirst().complete(List.of("You, badword."));
        assertTrue(mutes.isMuted(player));
        assertEquals(2, offenses.peekNext(player, 30));
        assertEquals(0, metrics.unconfirmed.sum());
    }

    @Test void hitTheSecondModelDoesNotHearPunishesNobodyAndDoesNotCountTowardsTheThreshold() throws Exception {
        var cfg = doubleCheck(config());
        cfg = Fakes.with(cfg, "moderation", Fakes.with(cfg.moderation(), "flagThreshold", 2));
        var moderation = moderation(cfg);
        moderation.onTranscript(said("badword"));
        asked.getFirst().complete(List.of("bad weather"));
        assertEquals(1, metrics.unconfirmed.sum());
        assertEquals(0, metrics.warnings.sum());

        moderation.onTranscript(said("badword"));
        asked.get(1).complete(List.of("badword"));
        assertFalse(mutes.isMuted(player), "an unconfirmed hit added a flag");
        assertEquals(1, metrics.warnings.sum());
        assertEquals(1, offenses.peekNext(player, 30));
    }

    @Test void onlyTheMuteRulesBothModelsHeardAddFlags() throws Exception {
        var cfg = doubleCheck(config());
        cfg = Fakes.with(cfg, "moderation", Fakes.with(cfg.moderation(), "flagThreshold", 2));
        var moderation = moderation(cfg);
        moderation.onTranscript(said("badword worseword darn"));
        asked.getFirst().complete(List.of("bad bird", "worseword darn"));
        assertFalse(mutes.isMuted(player));
        assertEquals(1, metrics.warnings.sum());
        assertEquals(0, metrics.unconfirmed.sum());
    }

    @Test void secondModelThatCannotRunPunishesNobody() throws Exception {
        var moderation = moderation(doubleCheck(config()));
        moderation.onTranscript(said("badword"));
        asked.getFirst().completeExceptionally(new IllegalStateException("queue full"));
        assertFalse(mutes.isMuted(player));
        assertEquals(1, offenses.peekNext(player, 30));
        assertEquals(1, metrics.unconfirmed.sum());
    }

    @Test void hitStillWaitingWhenTheEngineStopsIsDroppedWithoutAnIncident() throws Exception {
        var moderation = moderation(doubleCheck(config()));
        moderation.onTranscript(said("badword"));
        asked.getFirst().cancel(false);
        assertFalse(mutes.isMuted(player));
        assertEquals(0, metrics.incidents.sum());
        assertEquals(0, metrics.unconfirmed.sum());
    }

    @Test void flagOnlyHitsSkipTheSecondModel() throws Exception {
        var moderation = moderation(doubleCheck(config()));
        moderation.onTranscript(said("darn"));
        assertTrue(asked.isEmpty());
        assertEquals(1, metrics.incidents.sum());
    }

    @ParameterizedTest
    @ValueSource(strings = {"moderation:\n  languages: [en]\n",
            "speech-to-text:\n  double-check:\n    enabled: true\nmoderation:\n  languages: [en, es]\n"})
    void withoutTheDoubleCheckHitsArePunishedImmediately(String yaml) throws Exception {
        var file = Files.writeString(folder.resolve("config.yml"), yaml);
        var cfg = ConfigLoader.load(folder, file).config();
        cfg = Fakes.with(cfg, "recordings", new EvidenceSettings(EvidenceSettings.Mode.NONE, folder, false, 0, 0));
        var moderation = moderation(Fakes.with(cfg, "mute", Fakes.with(cfg.mute(), "notifyPlayer", false)));
        moderation.onTranscript(said("badword"));
        assertTrue(asked.isEmpty());
        assertTrue(mutes.isMuted(player));
    }

    @Test void forgiveClearsTheFlagWindowAndOffenceCount() throws Exception {
        var cfg = config();
        cfg = Fakes.with(cfg, "moderation", Fakes.with(Fakes.with(cfg.moderation(), "flagThreshold", 2), "dryRun", false));
        cfg = Fakes.with(cfg, "alerts", new PluginConfig.Alerts(false, false));
        cfg = Fakes.with(cfg, "recordings", new EvidenceSettings(EvidenceSettings.Mode.NONE, folder, false, 0, 0));
        final var settings = cfg;
        var messages = Messages.load(folder.resolve("messages.yml"));
        var players = new OnlinePlayers();
        var database = Fakes.database(folder);
        var mutes = new MuteService(() -> settings, () -> messages, hook(), players, Runnable::run, log, database);
        var rules = RuleEngine.compile(List.of(new RuleDefinition("test", "test", RuleAction.MUTE, 1, true,
                List.of("badword"), List.of(), List.of(), List.of())), Set.of());
        var offenses = new OffenseTracker(database, log);
        try (var http = HttpClient.newHttpClient()) {
            var discord = new DiscordWebhookService(DiscordSettings::disabled, http, log);
            var moderation = new ModerationService(() -> settings, () -> messages, () -> rules,
                    new TranscriptBuffer(new TranscriptBuffer.Limits(Duration.ofHours(1), 10, 0)), offenses, mutes,
                    new StaffAlertService(() -> settings, () -> messages, players, log), discord,
                    new EvidenceStore(settings::recordings, Runnable::run, log), new CustomCommandRunner(null, log),
                    new TranscriptLog(folder.resolve("logs"), Runnable::run, log), players, new Metrics(), log,
                    segment -> { throw new AssertionError("double check is off"); });
            var segment = new AudioSegment(player, "Alex", TestFixtures.AUDIO, 16000, Instant.now(), System.nanoTime(), null, false);
            offenses.recordNext(player, 0);
            moderation.onTranscript(new Transcript(segment, "badword", 0));
            moderation.forgive(player).join();
            moderation.onTranscript(new Transcript(segment, "badword", 0));
            assertFalse(mutes.isMuted(player));
            assertEquals(0, offenses.lookup(player, 0).join().count());
        }
        database.close();
    }
}
