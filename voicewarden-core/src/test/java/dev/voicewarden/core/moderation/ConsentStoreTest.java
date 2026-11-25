package dev.voicewarden.core.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsentStoreTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void unknownPlayersArePending(@TempDir Path dir) throws IOException {
        ConsentStore store = new ConsentStore(dir.resolve("consent.properties"));
        assertEquals(List.of(), store.load());
        assertEquals(ConsentStatus.PENDING, store.status(ALEX, 1));
    }

    @Test
    void persistsDecisionsInTheDocumentedFormat(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("data/consent.properties");
        ConsentStore store = new ConsentStore(file);
        store.accept(ALEX, 2);
        store.deny(STEVE, 2);
        assertEquals(ConsentStatus.ACCEPTED, store.status(ALEX, 2));
        assertEquals(ConsentStatus.DENIED, store.status(STEVE, 2));

        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.contains(ALEX + "=ACCEPTED:2"), lines.toString());
        assertTrue(lines.contains(STEVE + "=DENIED:2"), lines.toString());
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }

        ConsentStore reloaded = new ConsentStore(file);
        assertEquals(List.of(), reloaded.load());
        assertEquals(ConsentStatus.ACCEPTED, reloaded.status(ALEX, 2));
        assertEquals(ConsentStatus.DENIED, reloaded.status(STEVE, 2));
    }

    @Test
    void bumpingRulesVersionInvalidatesOldAnswers(@TempDir Path dir) throws IOException {
        ConsentStore store = new ConsentStore(dir.resolve("consent.properties"));
        store.accept(ALEX, 1);
        store.deny(STEVE, 1);
        assertEquals(ConsentStatus.OUTDATED, store.status(ALEX, 2));
        assertEquals(ConsentStatus.OUTDATED, store.status(STEVE, 2));
        store.accept(ALEX, 2);
        assertEquals(ConsentStatus.ACCEPTED, store.status(ALEX, 2));
    }

    @Test
    void acceptIfNotDeniedAutoAcceptsPendingAndOutdatedAcceptedWithoutOverwritingDenied(@TempDir Path dir) throws IOException {
        ConsentStore store = new ConsentStore(dir.resolve("consent.properties"));
        assertTrue(store.acceptIfNotDenied(ALEX, 1));
        assertEquals(ConsentStatus.ACCEPTED, store.status(ALEX, 1));
        assertEquals(false, store.acceptIfNotDenied(ALEX, 1));

        assertTrue(store.acceptIfNotDenied(ALEX, 2));
        assertEquals(ConsentStatus.ACCEPTED, store.status(ALEX, 2));

        store.deny(STEVE, 1);
        assertEquals(false, store.acceptIfNotDenied(STEVE, 1));
        assertEquals(false, store.acceptIfNotDenied(STEVE, 2));
        assertEquals(ConsentStatus.DENIED, store.status(STEVE, 1));
    }

    @Test
    void badLinesAreReportedAndSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("consent.properties");
        Files.writeString(file, "# comment\n" + ALEX + "=ACCEPTED:3\nnot-a-uuid=ACCEPTED:1\n" + STEVE + "=MAYBE:1\n\n");
        ConsentStore store = new ConsentStore(file);
        List<String> problems = store.load();
        assertEquals(2, problems.size());
        assertEquals(ConsentStatus.ACCEPTED, store.status(ALEX, 3));
        assertEquals(ConsentStatus.PENDING, store.status(STEVE, 1));
    }

    @Test
    void resetForgetsAPlayer(@TempDir Path dir) throws IOException {
        ConsentStore store = new ConsentStore(dir.resolve("consent.properties"));
        store.accept(ALEX, 1);
        store.reset(ALEX);
        assertEquals(ConsentStatus.PENDING, store.status(ALEX, 1));
        ConsentStore reloaded = new ConsentStore(store.file());
        reloaded.load();
        assertEquals(ConsentStatus.PENDING, reloaded.status(ALEX, 1));
    }

    @Test
    void concurrentWritesAllLand(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("consent.properties");
        ConsentStore store = new ConsentStore(file);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<UUID> players = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            UUID id = UUID.randomUUID();
            players.add(id);
            futures.add(pool.submit(() -> {
                store.accept(id, 1);
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        ConsentStore reloaded = new ConsentStore(file);
        assertEquals(List.of(), reloaded.load());
        for (UUID id : players) {
            assertEquals(ConsentStatus.ACCEPTED, reloaded.status(id, 1));
        }
    }

    @Test
    void failedSaveDoesNotMutateInMemoryRecords(@TempDir Path dir) throws IOException {
        Path blockingDir = dir.resolve("consent.properties");
        Files.createDirectories(blockingDir);
        Files.writeString(blockingDir.resolve("blocker"), "busy");
        ConsentStore store = new ConsentStore(blockingDir);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> store.accept(ALEX, 1));
        assertEquals(ConsentStatus.PENDING, store.status(ALEX, 1));
    }
}
