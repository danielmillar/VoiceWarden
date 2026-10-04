package dev.danielmillar.nevusvoice.moderation;

import dev.danielmillar.nevusvoice.TestFixtures;
import dev.danielmillar.nevusvoice.concurrent.PluginExecutors;
import dev.danielmillar.nevusvoice.config.*;
import dev.danielmillar.nevusvoice.evidence.*;
import dev.danielmillar.nevusvoice.health.Metrics;
import dev.danielmillar.nevusvoice.luckperms.LuckPermsHook;
import dev.danielmillar.nevusvoice.moderation.*;
import dev.danielmillar.nevusvoice.moderation.rules.*;
import dev.danielmillar.nevusvoice.mute.MuteService;
import dev.danielmillar.nevusvoice.notify.StaffAlertService;
import dev.danielmillar.nevusvoice.notify.discord.*;
import dev.danielmillar.nevusvoice.player.OnlinePlayers;
import dev.danielmillar.nevusvoice.report.ReportStore;
import dev.danielmillar.nevusvoice.stt.Transcript;
import dev.danielmillar.nevusvoice.audio.AudioSegment;
import dev.danielmillar.nevusvoice.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.UserManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
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
        return Fakes.with(cfg, "mute", Fakes.with(cfg.mute(), "notifyPlayer", false));
    }
    @Test void disabledMasterSwitchDiscardsQueuedTranscriptWithoutModeration() throws Exception {
        var cfg = config();
        cfg = Fakes.with(cfg, "enabled", false);
        cfg = Fakes.with(cfg, "alerts", new PluginConfig.Alerts(false, false));
        cfg = Fakes.with(cfg, "recordings", new EvidenceSettings(EvidenceSettings.Mode.NONE, folder, false, 0, 0));
        final var settings = cfg;
        var messages = Messages.load(folder.resolve("messages.yml"));
        var players = new OnlinePlayers();
        var hook = hook();
        var mutes = new MuteService(() -> settings, () -> messages, hook, players, Runnable::run, log, folder);
        var rules = RuleEngine.compile(List.of(new RuleDefinition("test", "test", RuleAction.MUTE, 1, true,
                List.of("badword"), List.of(), List.of(), List.of())), Set.of());
        var metrics = new Metrics();
        var buffer = new TranscriptBuffer(new TranscriptBuffer.Limits(Duration.ofHours(1), 10, 0));
        var alerts = new StaffAlertService(() -> settings, () -> messages, players, log);
        var evidence = new EvidenceStore(settings::recordings, Runnable::run, log);
        try (var http = HttpClient.newHttpClient()) {
            var discord = new DiscordWebhookService(DiscordSettings::disabled, http, log);
            var moderation = new ModerationService(() -> settings, () -> messages, () -> rules, buffer,
                    new OffenseTracker(hook, log), mutes, alerts, discord, evidence,
                    new CustomCommandRunner(null, log), new TranscriptLog(folder.resolve("logs"), Runnable::run, log),
                    players, metrics, log);
            var segment = new AudioSegment(player, "Alex", TestFixtures.AUDIO, 16000, Instant.now(), System.nanoTime(), null, false);
            moderation.onTranscript(new Transcript(segment, "badword", 0));
            assertFalse(mutes.isMuted(player));
            assertEquals(0, metrics.autoMutes.sum());
            assertEquals(0, writes.get());
            assertTrue(buffer.recentLines(player, 10).isEmpty());
        }
    }
    @Test void disablingDuringOfflineHistoryLoadPreventsPunishment() throws Exception {
        var cfg = config();
        cfg = Fakes.with(cfg, "alerts", new PluginConfig.Alerts(false, false));
        cfg = Fakes.with(cfg, "recordings", new EvidenceSettings(EvidenceSettings.Mode.NONE, folder, false, 0, 0));
        var settings = new AtomicReference<>(cfg);
        var historyLoaded = new CompletableFuture<net.luckperms.api.model.user.User>();
        var entered = new CountDownLatch(1);
        var meta = Fakes.proxy(net.luckperms.api.cacheddata.CachedMetaData.class, (p, m, a) ->
                a[0].equals("nevusvoice-offenses") ? Optional.of(4) : Optional.of(System.currentTimeMillis()));
        var cached = Fakes.proxy(net.luckperms.api.cacheddata.CachedDataManager.class, (p, m, a) -> meta);
        var user = Fakes.proxy(net.luckperms.api.model.user.User.class, (p, m, a) -> cached);
        var users = Fakes.proxy(UserManager.class, (p, m, a) -> switch (m.getName()) {
            case "getUser" -> null;
            case "loadUser" -> { entered.countDown(); yield historyLoaded; }
            case "modifyUser" -> { writes.incrementAndGet(); yield CompletableFuture.completedFuture(null); }
            default -> throw new AssertionError(m);
        });
        var hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> users));
        var messages = Messages.load(folder.resolve("messages.yml"));
        var players = new OnlinePlayers();
        var mutes = new MuteService(settings::get, () -> messages, hook, players, Runnable::run, log, folder);
        var rules = RuleEngine.compile(List.of(new RuleDefinition("test", "test", RuleAction.MUTE, 1, true,
                List.of("badword"), List.of(), List.of(), List.of())), Set.of());
        var metrics = new Metrics();
        var buffer = new TranscriptBuffer(new TranscriptBuffer.Limits(Duration.ofHours(1), 10, 0));
        var alerts = new StaffAlertService(settings::get, () -> messages, players, log);
        var evidence = new EvidenceStore(() -> settings.get().recordings(), Runnable::run, log);
        try (var http = HttpClient.newHttpClient(); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var discord = new DiscordWebhookService(DiscordSettings::disabled, http, log);
            var moderation = new ModerationService(settings::get, () -> messages, () -> rules, buffer,
                    new OffenseTracker(hook, log), mutes, alerts, discord, evidence,
                    new CustomCommandRunner(null, log), new TranscriptLog(folder.resolve("logs"), Runnable::run, log),
                    players, metrics, log);
            var segment = new AudioSegment(player, "Alex", TestFixtures.AUDIO, 16000, Instant.now(), System.nanoTime(), null, false);
            var done = worker.submit(() -> moderation.onTranscript(new Transcript(segment, "badword", 0)));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                settings.set(Fakes.with(settings.get(), "enabled", false));
            } finally {
                historyLoaded.complete(user);
            }
            done.get(3, TimeUnit.SECONDS);
            assertFalse(mutes.isMuted(player));
            assertEquals(0, writes.get());
            assertEquals(0, metrics.autoMutes.sum());
            assertEquals(0, metrics.incidents.sum());
        }
    }

}
