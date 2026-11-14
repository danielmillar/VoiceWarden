package dev.danielmillar.voicewarden.privacy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.danielmillar.voicewarden.TestFixtures;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.consent.ConsentService;
import dev.danielmillar.voicewarden.evidence.EvidenceSettings;
import dev.danielmillar.voicewarden.evidence.EvidenceStore;
import dev.danielmillar.voicewarden.moderation.OffenseTracker;
import dev.danielmillar.voicewarden.moderation.TranscriptBuffer;
import dev.danielmillar.voicewarden.moderation.TranscriptLog;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.report.ReportStore;
import dev.danielmillar.voicewarden.report.VoiceReport;
import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDataTest {
    @TempDir Path folder;
    private final UUID alice = TestFixtures.PLAYER;
    private final UUID bob = new UUID(0, 2);
    private final UUID carol = new UUID(0, 3);
    private final List<String> mirrorCalls = new ArrayList<>();
    private final Set<UUID> cooldownsForgotten = ConcurrentHashMap.newKeySet();
    private PluginConfig config;
    private Database database;
    private MuteService mutes;
    private OffenseTracker offenses;
    private ConsentService consent;
    private ReportStore reports;
    private EvidenceStore evidence;
    private TranscriptBuffer buffer;
    private TranscriptLog log;
    private PlayerData data;

    @BeforeEach void setup() throws Exception {
        config = Fakes.config(folder);
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "notifyPlayer", false));
        config = Fakes.with(config, "consent", new PluginConfig.Consent(PluginConfig.ConsentMode.CONFIRM, 1,
                PluginConfig.OnDeny.STAY, false));
        config = Fakes.with(config, "recordings", new EvidenceSettings(EvidenceSettings.Mode.FLAGGED,
                folder.resolve("recordings"), true, 0, 0));
        Messages messages = Messages.load(folder.resolve("messages.yml"));
        database = Fakes.database(folder);
        Logger logger = Logger.getAnonymousLogger();
        mutes = new MuteService(() -> config, () -> messages, (player, previous, next, duration) -> {
            mirrorCalls.add(player + (next == null ? " cleared" : " denied"));
            return CompletableFuture.completedFuture(null);
        }, new OnlinePlayers(), Runnable::run, logger, database);
        offenses = new OffenseTracker(database, logger);
        consent = new ConsentService(() -> config.consent(), database, System::currentTimeMillis, logger);
        reports = new ReportStore(database, logger);
        evidence = new EvidenceStore(() -> config.recordings(), Runnable::run, logger);
        buffer = new TranscriptBuffer(new TranscriptBuffer.Limits(Duration.ofHours(1), 10, 1_000));
        log = new TranscriptLog(folder.resolve("logs"), Runnable::run, logger);
        data = new PlayerData(() -> config, database, mutes, offenses, id -> offenses.reset(id), consent, reports,
                cooldownsForgotten::add, evidence, buffer, log, folder.resolve("exports"), id -> false, Runnable::run, logger);
    }

    @AfterEach void close() {
        database.close();
    }

    @Test void forgetEmptiesEveryStoreForThePlayerAndLeavesOthersAlone() throws Exception {
        seed(alice, "Alex");
        seed(bob, "Bob");
        reports.add(report(carol, alice, "alice is reported"));
        reports.add(report(alice, carol, "alice reported carol"));
        reports.add(report(carol, bob, "bob is reported"));
        Path aliceExport = data.export(alice, "Alex").join();
        Path bobExport = data.export(bob, "Bob").join();

        var forgotten = data.forget(alice).join();

        assertEquals(new PlayerData.Forgotten(true, 1, 1, true, 2, 2, 1, 1, 1), forgotten);
        assertTrue(mirrorCalls.contains(alice + " cleared"));
        assertEquals(Set.of(alice), cooldownsForgotten);
        assertFalse(Files.exists(aliceExport));
        assertTrue(Files.exists(bobExport));
        assertTrue(evidence.find(alice).join().isEmpty());
        assertEquals(1, evidence.find(bob).join().size());
        assertTrue(buffer.recentLines(alice, 10).isEmpty());
        assertEquals(1, buffer.recentLines(bob, 10).size());
        assertEquals(List.of(), log.lines(alice));
        assertEquals(1, log.lines(bob).size());
        for (String table : List.of("mutes", "mute_mirrors", "mute_events", "offenses", "consents")) {
            assertEquals(0, rows("SELECT COUNT(*) FROM " + table + " WHERE player_id = ?", alice), table);
            assertEquals(1, rows("SELECT COUNT(*) FROM " + table + " WHERE player_id = ?", bob), table);
        }
        assertEquals(0, rows("SELECT COUNT(*) FROM reports WHERE reporter_id = ? OR target_id = ?", alice, alice));
        assertEquals(1, rows("SELECT COUNT(*) FROM reports"));
        assertEquals(1, rows("SELECT COUNT(*) FROM report_lines"));

        // In memory too: the next load is not needed for the player to be gone.
        assertFalse(mutes.isMuted(alice));
        assertTrue(mutes.isMuted(bob));
        assertEquals(0, offenses.stored(alice).count());
        assertTrue(consent.answer(alice).isEmpty());
        assertTrue(reports.involving(alice).isEmpty());
        assertEquals(1, reports.involving(bob).size());
    }

    @Test void exportHoldsEverythingStoredAboutThePlayerButNotOtherPlayersSpeech() throws Exception {
        seed(alice, "Alex");
        seed(bob, "Bob");
        reports.add(report(carol, alice, "alice is reported"));
        reports.add(report(alice, carol, "carol said this"));

        Path file = data.export(alice, "Alex").join();

        assertEquals(folder.resolve("exports"), file.getParent());
        assertTrue(file.getFileName().toString().startsWith(alice.toString()));
        JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(alice.toString(), json.get("player").getAsString());
        assertEquals("seeded", json.getAsJsonObject("mute").get("reason").getAsString());
        assertEquals(1, json.getAsJsonArray("muteHistory").size());
        assertEquals(1, json.getAsJsonObject("offences").get("count").getAsInt());
        assertTrue(json.getAsJsonObject("consent").get("accepted").getAsBoolean());
        assertEquals("Alex said this", json.getAsJsonArray("bufferedTranscript").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(1, json.getAsJsonArray("transcriptLogs").size());
        var exportedReports = json.getAsJsonArray("reports");
        assertEquals(2, exportedReports.size());
        String text = Files.readString(file);
        assertTrue(text.contains("alice is reported"));
        assertFalse(text.contains("carol said this"));
        assertFalse(text.contains("Bob said this"));
        assertFalse(text.contains("\"reporterName\": \"Carol\""));
        var exportedEvidence = json.getAsJsonArray("evidence");
        assertEquals(1, exportedEvidence.size());
        assertEquals("hello badword @everyone",
                exportedEvidence.get(0).getAsJsonObject().getAsJsonObject("content").get("transcript").getAsString());
    }

    @Test void pruneDeletesOldTranscriptLogsAndExports() throws Exception {
        config = Fakes.with(config, "retention", new PluginConfig.Retention(30, 0, 0, 0, 7));
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        Files.createDirectories(folder.resolve("logs"));
        Path oldLog = Files.writeString(folder.resolve("logs/transcripts-2026-09-01.log"), "x\n");
        Path newLog = Files.writeString(folder.resolve("logs/transcripts-2026-11-01.log"), "x\n");
        Files.createDirectories(folder.resolve("exports"));
        Path oldExport = Files.writeString(folder.resolve("exports/" + alice + "-old.json"), "{}");
        Files.setLastModifiedTime(oldExport, FileTime.from(now.minus(Duration.ofDays(8))));
        Path newExport = Files.writeString(folder.resolve("exports/" + bob + "-new.json"), "{}");
        Files.setLastModifiedTime(newExport, FileTime.from(now.minus(Duration.ofDays(6))));

        var pruned = data.prune(now).join();

        assertEquals(new PlayerData.Pruned(0, 0, 0, 0, 1, 1), pruned);
        assertFalse(Files.exists(oldLog));
        assertTrue(Files.exists(newLog));
        assertFalse(Files.exists(oldExport));
        assertTrue(Files.exists(newExport));
    }

    private void seed(UUID player, String name) throws Exception {
        mutes.mute(player, name, Duration.ofHours(1), "seeded", "staff", false).join();
        offenses.recordNext(player, 0);
        consent.answer(player, true);
        buffer.add(player, name, Instant.now(), name + " said this", new short[10]);
        log.append(Instant.now(), player, name, name + " said this");
        var incident = Fakes.with(Fakes.with(Fakes.with(TestFixtures.incident(), "playerId", player), "playerName", name),
                "id", UUID.randomUUID());
        assertNotNull(evidence.saveIncident(incident).join());
        log.lines(player); // writes the pending line
        database.flush().join();
    }

    private static VoiceReport report(UUID reporter, UUID target, String line) {
        var r = TestFixtures.report();
        return new VoiceReport(UUID.randomUUID(), r.createdAt(), reporter, reporter.equals(new UUID(0, 3)) ? "Carol" : "Someone",
                target, "Target", r.window(), List.of(new dev.danielmillar.voicewarden.moderation.TranscriptLine(r.createdAt(), line)),
                r.audio(), r.sampleRate());
    }

    private int rows(String sql, UUID... ids) throws Exception {
        database.flush().join();
        return database.read(c -> {
            try (PreparedStatement s = c.prepareStatement(sql)) {
                for (int i = 0; i < ids.length; i++) {
                    s.setString(i + 1, ids[i].toString());
                }
                try (var r = s.executeQuery()) {
                    return r.next() ? r.getInt(1) : 0;
                }
            }
        });
    }
}
