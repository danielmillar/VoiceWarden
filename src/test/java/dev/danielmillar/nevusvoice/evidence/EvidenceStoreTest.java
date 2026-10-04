package dev.danielmillar.nevusvoice.evidence;

import com.google.gson.*;
import dev.danielmillar.nevusvoice.TestFixtures;
import dev.danielmillar.nevusvoice.audio.WavEncoder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceStoreTest {
    @TempDir Path directory;
    private ExecutorService executor;
    private AtomicReference<EvidenceSettings> config;
    private EvidenceStore store;
    private List<LogRecord> logs;
    private CountDownLatch logged;

    @BeforeEach void setup() {
        executor = Executors.newSingleThreadExecutor();
        config = new AtomicReference<>(new EvidenceSettings(EvidenceSettings.Mode.ALL, directory, true, 0, 0));
        logs = new CopyOnWriteArrayList<>();
        logged = new CountDownLatch(1);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); logged.countDown(); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        store = new EvidenceStore(config::get, executor, logger);
    }

    @AfterEach void stop() { executor.shutdownNow(); }

    @Test void incidentContainsEveryMetadataFieldAndMatchingWav() throws Exception {
        var incident = TestFixtures.incident();
        Path json = store.saveIncident(incident).get(3, TimeUnit.SECONDS);
        assertEquals(directory.resolve("2026-10-03/123456_Alex___incident_abcdef01.json"), json);
        JsonObject metadata = read(json);
        assertEquals(19, metadata.size()); // 17 non-audio record fields + schema + audioFile
        assertEquals(1, metadata.get("schema").getAsInt());
        assertEquals(incident.detectedAt().toString(), metadata.get("detectedAt").getAsString());
        assertEquals("PT0.5S", metadata.get("audioDuration").getAsString());
        assertEquals(incident.id().toString(), metadata.get("id").getAsString());
        assertFalse(metadata.has("audio"));
        assertEquals(2, metadata.getAsJsonArray("matches").size());
        assertEquals("world", metadata.getAsJsonObject("location").get("world").getAsString());
        Path wav = json.resolveSibling(metadata.get("audioFile").getAsString());
        assertArrayEquals(WavEncoder.encode(incident.audio(), incident.sampleRate()), Files.readAllBytes(wav));
        assertTrue(Files.readString(json).contains("\n  \"schema\""));
        try (var paths = Files.walk(directory)) { assertEquals(2, paths.filter(Files::isRegularFile).count()); }
    }

    @Test void reportAndUtteranceHaveExpectedNamesAndNoAudioArray() throws Exception {
        Path report = store.saveReport(TestFixtures.report()).get(3, TimeUnit.SECONDS);
        assertEquals("123456_Alex___report_abcdef01.json", report.getFileName().toString());
        assertEquals("Reporter_*", read(report).get("reporterName").getAsString());
        Path utterance = store.saveUtterance(TestFixtures.PLAYER, "../../Alex", TestFixtures.TIME,
                "spoken words", new short[0], 16000).get(3, TimeUnit.SECONDS);
        assertTrue(utterance.getFileName().toString().matches("123456_______Alex_utterance_[0-9a-f]{8}\\.json"));
        assertEquals("spoken words", read(utterance).get("transcript").getAsString());
        assertTrue(read(utterance).get("audioFile").isJsonNull());
        assertFalse(read(utterance).has("audio"));
    }

    @ParameterizedTest @EnumSource(EvidenceSettings.Mode.class)
    void modeGating(EvidenceSettings.Mode mode) throws Exception {
        config.set(new EvidenceSettings(mode, directory, false, 0, 0));
        Path incident = store.saveIncident(TestFixtures.incident()).get(3, TimeUnit.SECONDS);
        Path report = store.saveReport(TestFixtures.report()).get(3, TimeUnit.SECONDS);
        Path utterance = store.saveUtterance(TestFixtures.PLAYER, "Alex", TestFixtures.TIME, "test", TestFixtures.AUDIO, 16000)
                .get(3, TimeUnit.SECONDS);
        assertEquals(mode != EvidenceSettings.Mode.NONE, incident != null);
        assertEquals(mode != EvidenceSettings.Mode.NONE, report != null);
        assertEquals(mode == EvidenceSettings.Mode.ALL, utterance != null);
        if (incident != null) assertTrue(read(incident).get("audioFile").isJsonNull());
    }

    @Test void usesSettingsOnExecutorAndCallerReturnsBeforeIo() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        CompletableFuture<Path> future = store.saveIncident(TestFixtures.incident());
        assertFalse(future.isDone());
        config.set(new EvidenceSettings(EvidenceSettings.Mode.NONE, directory, true, 0, 0));
        release.countDown();
        assertNull(future.get(3, TimeUnit.SECONDS));
    }

    @Test void cleanupExpiresFilesByMtimeAndRemovesEmptyDateDirectories() throws Exception {
        config.set(new EvidenceSettings(EvidenceSettings.Mode.ALL, directory, true, 2, 0));
        Path old = file("2020-01-01/old.json", 10, Instant.now().minus(Duration.ofDays(3)));
        Path oldAudio = file("2020-01-01/old.wav", 15, Instant.now().minus(Duration.ofDays(3)));
        Path recent = file("2026-10-03/recent.json", 20, Instant.now());
        var result = store.cleanup().get(3, TimeUnit.SECONDS);
        assertEquals(new EvidenceStore.CleanupResult(2, 25, 20), result);
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(oldAudio));
        assertFalse(Files.exists(old.getParent()));
        assertTrue(Files.exists(recent));
    }

    @Test void cleanupDeletesOldestUntilWithinSizeCap() throws Exception {
        config.set(new EvidenceSettings(EvidenceSettings.Mode.ALL, directory, true, 0, 25));
        Path oldest = file("2026-10-01/first.json", 10, Instant.now().minusSeconds(30));
        Path middle = file("2026-10-02/second.wav", 15, Instant.now().minusSeconds(20));
        Path newest = file("2026-10-03/third.json", 20, Instant.now().minusSeconds(10));
        assertEquals(new EvidenceStore.CleanupResult(2, 25, 20), store.cleanup().get(3, TimeUnit.SECONDS));
        assertFalse(Files.exists(oldest));
        assertFalse(Files.exists(middle));
        assertTrue(Files.exists(newest));
    }

    @Test void retentionRunsBeforeQuotaSoFreshOldestIsKeptWhenExpiredNewestFreesSpace() throws Exception {
        config.set(new EvidenceSettings(EvidenceSettings.Mode.ALL, directory, false, 2, 20));
        file("2020-01-01/expired.json", 30, Instant.now().minus(Duration.ofDays(3)));
        Path fresh = file("2026-10-03/fresh.json", 20, Instant.now());
        assertEquals(new EvidenceStore.CleanupResult(1, 30, 20), store.cleanup().get(3, TimeUnit.SECONDS));
        assertTrue(Files.exists(fresh));
    }

    @Test void zeroLimitsAndMissingDirectoryKeepAllFiles() throws Exception {
        Path missing = directory.resolve("missing");
        config.set(new EvidenceSettings(EvidenceSettings.Mode.NONE, missing, false, 0, 0));
        assertEquals(new EvidenceStore.CleanupResult(0, 0, 0), store.cleanup().get(3, TimeUnit.SECONDS));
        config.set(new EvidenceSettings(EvidenceSettings.Mode.NONE, directory, false, 0, 0));
        file("2020-01-01/old.json", 12, Instant.EPOCH);
        assertEquals(new EvidenceStore.CleanupResult(0, 0, 12), store.cleanup().get(3, TimeUnit.SECONDS));
    }

    @Test void filesystemFailureIsAsyncExceptionalAndLoggedOnce() throws Exception {
        Path notDirectory = directory.resolve("file");
        Files.writeString(notDirectory, "occupied");
        config.set(new EvidenceSettings(EvidenceSettings.Mode.ALL, notDirectory, true, 0, 0));
        var future = assertDoesNotThrow(() -> store.saveIncident(TestFixtures.incident()));
        assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertEquals(1, logs.size());
    }

    @Test void executorRejectionNeverEscapesCaller() throws Exception {
        executor.shutdown();
        var future = assertDoesNotThrow(() -> store.saveReport(TestFixtures.report()));
        assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(logged.await(2, TimeUnit.SECONDS));
        assertEquals(1, logs.size());
    }

    private JsonObject read(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    private Path file(String name, int size, Instant modified) throws Exception {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[size]);
        Files.setLastModifiedTime(path, FileTime.from(modified));
        return path;
    }
}
