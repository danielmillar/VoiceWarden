package dev.voicewarden.core.storage;

import static dev.voicewarden.core.storage.StorageFixtures.NOW;
import static dev.voicewarden.core.storage.StorageFixtures.mute;
import static dev.voicewarden.core.storage.StorageFixtures.report;
import static dev.voicewarden.core.storage.StorageFixtures.strike;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceMuteRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteStorageProviderTest {

    @TempDir
    Path dataDir;

    private SqliteStorageProvider storage;
    private final UUID steve = UUID.randomUUID();

    @BeforeEach
    void open() {
        storage = new SqliteStorageProvider(StorageConfig.sqlite(dataDir.resolve("data"), "lobby"));
    }

    @AfterEach
    void close() {
        storage.close();
    }

    @Test
    void createsBothWalDatabasesUnderTheDataDirectory() {
        assertEquals(dataDir.resolve("data").resolve("mute_history.db"), storage.muteDatabase());
        assertEquals(dataDir.resolve("data").resolve("voice_reports.db"), storage.reportDatabase());
        assertTrue(Files.isRegularFile(storage.muteDatabase()));
        assertTrue(Files.isRegularFile(storage.reportDatabase()));
        assertEquals("wal", storage.journalMode(false));
        assertEquals("wal", storage.journalMode(true));
        assertTrue(storage.isAvailable());
    }

    @Test
    void reopeningKeepsData() {
        storage.recordStrike(strike(steve, NOW, "lobby"));
        storage.close();
        storage = new SqliteStorageProvider(StorageConfig.sqlite(dataDir.resolve("data"), "lobby"));
        assertEquals(1, storage.countActiveStrikes(steve, NOW.minusSeconds(60), true));
    }

    @Test
    void closedProviderReportsUnavailableAndRejectsQueries() {
        storage.close();
        assertFalse(storage.isAvailable());
        assertThrows(StorageException.class, () -> storage.countActiveStrikes(steve, NOW, true));
    }

    @Test
    void muteLifecycle() {
        VoiceMuteRecord expired = mute(steve, NOW.minus(Duration.ofHours(2)), Duration.ofMinutes(30), "lobby");
        VoiceMuteRecord current = mute(steve, NOW.minus(Duration.ofMinutes(5)), Duration.ofMinutes(30), "lobby");
        storage.saveMute(expired);
        storage.saveMute(current);

        assertEquals(current, storage.findActiveMute(steve, NOW).orElseThrow());
        assertTrue(storage.findActiveMute(steve, NOW.plus(Duration.ofHours(1))).isEmpty());
        assertEquals(List.of(current), storage.findAllActiveMutes(NOW));
        assertEquals(List.of(current, expired), storage.findMuteHistory(steve, 10));
        assertEquals(List.of(current), storage.findMuteHistory(steve, 1));
        assertEquals(1, storage.countMutesInWindow(steve, NOW.minus(Duration.ofHours(1)), true));
        assertEquals(2, storage.countMutesInWindow(steve, NOW.minus(Duration.ofDays(1)), true));

        assertEquals(2, storage.deactivateActiveMutes(steve));
        assertTrue(storage.findActiveMute(steve, NOW).isEmpty());
        assertTrue(storage.findAllActiveMutes(NOW).isEmpty());
        assertFalse(storage.findMuteHistory(steve, 10).getFirst().active());
        assertEquals(0, storage.deactivateActiveMutes(steve));
    }

    @Test
    void permanentMuteStaysActive() {
        VoiceMuteRecord permanent = mute(steve, NOW, null, "lobby");
        storage.saveMute(permanent);
        assertEquals(permanent, storage.findActiveMute(steve, NOW.plus(Duration.ofDays(3650))).orElseThrow());
    }

    @Test
    void muteWindowCountRespectsServerScope() {
        storage.saveMute(mute(steve, NOW, Duration.ofMinutes(5), "lobby"));
        storage.saveMute(mute(steve, NOW, Duration.ofMinutes(5), "survival"));
        assertEquals(2, storage.countMutesInWindow(steve, NOW.minusSeconds(1), true));
        assertEquals(1, storage.countMutesInWindow(steve, NOW.minusSeconds(1), false));
    }

    @Test
    void strikesUseARollingWindow() {
        StrikeRecord old = storage.recordStrike(strike(steve, NOW.minus(Duration.ofMinutes(15)), "lobby"));
        StrikeRecord recent = storage.recordStrike(strike(steve, NOW.minus(Duration.ofMinutes(2)), "lobby"));
        assertTrue(old.id() > 0);
        assertTrue(recent.id() > old.id());
        assertEquals(FlagSeverity.MODERATE, recent.severity());

        assertEquals(1, storage.countActiveStrikes(steve, NOW.minus(Duration.ofMinutes(10)), true));
        assertEquals(2, storage.countActiveStrikes(steve, NOW.minus(Duration.ofMinutes(20)), true));
        assertEquals(0, storage.countActiveStrikes(UUID.randomUUID(), NOW.minus(Duration.ofMinutes(20)), true));
    }

    @Test
    void networkStrikesShareAcrossServersOnlyWhenNetworkWide() {
        try (SqliteStorageProvider survival = new SqliteStorageProvider(
                StorageConfig.sqlite(dataDir.resolve("data"), "survival"))) {
            storage.recordStrike(strike(steve, NOW, "lobby"));
            survival.recordStrike(strike(steve, NOW, "survival"));
            survival.recordStrike(strike(steve, NOW, "survival"));

            assertEquals(3, storage.countActiveStrikes(steve, NOW.minusSeconds(1), true));
            assertEquals(3, survival.countActiveStrikes(steve, NOW.minusSeconds(1), true));
            assertEquals(1, storage.countActiveStrikes(steve, NOW.minusSeconds(1), false));
            assertEquals(2, survival.countActiveStrikes(steve, NOW.minusSeconds(1), false));
        }
    }

    @Test
    void pardonMarksStrikesPardonedAndDeactivatesMutes() {
        UUID alex = UUID.randomUUID();
        storage.recordStrike(strike(steve, NOW, "lobby"));
        storage.recordStrike(strike(steve, NOW, "lobby"));
        storage.recordStrike(strike(alex, NOW, "lobby"));
        storage.saveMute(mute(steve, NOW, Duration.ofMinutes(10), "lobby"));

        PardonResult result = storage.pardonPlayer(steve);

        assertEquals(new PardonResult(2, 1), result);
        assertEquals(0, storage.countActiveStrikes(steve, NOW.minusSeconds(1), true));
        assertEquals(1, storage.countActiveStrikes(alex, NOW.minusSeconds(1), true));
        assertTrue(storage.findStrikes(steve).stream().allMatch(StrikeRecord::pardoned));
        assertTrue(storage.findActiveMute(steve, NOW).isEmpty());
        assertEquals(0, storage.pardonStrikes(steve));
    }

    @Test
    void strikeTextRoundTripsVerbatim() {
        String transcript = "quote ' double \" backslash \\ emoji 🎤 newline\nend";
        storage.recordStrike(StrikeRecord.unsaved(steve, "Steve", FlagSeverity.SEVERE, "a, b", transcript, NOW, "lobby"));
        StrikeRecord stored = storage.findStrikes(steve).getFirst();
        assertEquals(transcript, stored.transcript());
        assertEquals("a, b", stored.matchedWords());
        assertEquals(FlagSeverity.SEVERE, stored.severity());
        assertEquals(NOW, stored.createdAt());
    }

    @Test
    void voiceReportLifecycle() {
        UUID alex = UUID.randomUUID();
        VoiceReportRecord first = storage.saveReport(report(alex, steve, NOW.minusSeconds(60)));
        VoiceReportRecord second = storage.saveReport(report(alex, steve, NOW));
        VoiceReportRecord other = storage.saveReport(report(UUID.randomUUID(), steve, NOW.plusSeconds(1)));

        assertTrue(first.id() > 0);
        assertEquals(first, storage.findReportById(first.id()).orElseThrow());
        assertTrue(storage.findReportById(9999).isEmpty());
        assertEquals(List.of(other, second, first), storage.findReports(true, 10));
        assertEquals(List.of(other, second), storage.findReports(true, 2));
        assertEquals(2, storage.countOpenReportsByReporter(alex));

        assertTrue(storage.markReportReviewed(first.id(), "Mod", NOW.plusSeconds(5)));
        assertFalse(storage.markReportReviewed(first.id(), "OtherMod", NOW.plusSeconds(6)));
        assertFalse(storage.markReportReviewed(9999, "Mod", NOW));

        VoiceReportRecord reviewed = storage.findReportById(first.id()).orElseThrow();
        assertTrue(reviewed.reviewed());
        assertEquals("Mod", reviewed.reviewedBy());
        assertEquals(NOW.plusSeconds(5), reviewed.reviewedAt());
        assertEquals(List.of(other, second), storage.findReports(true, 10));
        assertEquals(List.of(other, second, reviewed), storage.findReports(false, 10));
        assertEquals(1, storage.countOpenReportsByReporter(alex));
    }
}
