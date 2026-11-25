package dev.voicewarden.core.storage;

import static dev.voicewarden.core.storage.StorageFixtures.NOW;
import static dev.voicewarden.core.storage.StorageFixtures.mute;
import static dev.voicewarden.core.storage.StorageFixtures.report;
import static dev.voicewarden.core.storage.StorageFixtures.strike;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.FlagSeverity;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResilientStorageManagerTest {

    private static final Duration QUERY_TIMEOUT = Duration.ofMillis(300);

    @TempDir
    Path dataDir;

    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private ResilientStorageManager manager;

    @AfterEach
    void closeManager() {
        if (manager != null) {
            manager.close();
        }
    }

    private ResilientStorageManager manager(StorageProviderFactory factory, Duration reconnectInterval) {
        manager = new ResilientStorageManager(factory, "TEST", "lobby", QUERY_TIMEOUT, reconnectInterval,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return manager;
    }

    private StorageConfig sqliteConfig() {
        return StorageConfig.sqlite(dataDir, "lobby");
    }

    @Test
    void startReturnsImmediatelyWhenThePrimaryHangs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ResilientStorageManager storage = manager(() -> {
            release.await();
            throw new IllegalStateException("never connects");
        }, Duration.ofMinutes(1));

        long started = System.nanoTime();
        storage.start();
        StrikeRecord saved = storage.recordStrike(strike(steve, NOW, "lobby"));
        int strikes = storage.countActiveStrikes(steve, NOW.minusSeconds(1), true);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMs < 2000, "start and fallback calls took " + elapsedMs + "ms");
        assertFalse(storage.isAvailable());
        assertTrue(storage.isUsingFallback());
        assertTrue(saved.id() > 0);
        assertEquals(1, strikes);
        release.countDown();
    }

    @Test
    void fallsBackToMemoryForStrikesMutesAndReportsWhenThePrimaryFails() throws Exception {
        ResilientStorageManager storage = manager(() -> {
            throw new java.sql.SQLException("Connection refused");
        }, Duration.ofMinutes(1));
        storage.start();
        assertFalse(storage.reconnectNow().get(2, TimeUnit.SECONDS));
        assertTrue(storage.lastFailure().contains("Connection refused"));

        storage.recordStrike(strike(steve, NOW, "lobby"));
        storage.recordStrike(strike(steve, NOW, "survival"));
        assertEquals(2, storage.countActiveStrikes(steve, NOW.minusSeconds(1), true));
        assertEquals(1, storage.countActiveStrikes(steve, NOW.minusSeconds(1), false));
        assertEquals(2, storage.pardonStrikes(steve));
        assertEquals(0, storage.countActiveStrikes(steve, NOW.minusSeconds(1), true));

        storage.saveMute(mute(steve, NOW, Duration.ofMinutes(10), "lobby"));
        assertTrue(storage.findActiveMute(steve, NOW).isPresent());
        assertEquals(1, storage.findAllActiveMutes(NOW).size());
        assertEquals(1, storage.countMutesInWindow(steve, NOW.minusSeconds(1), true));
        assertEquals(1, storage.deactivateActiveMutes(steve));
        assertTrue(storage.findActiveMute(steve, NOW).isEmpty());

        VoiceReportRecord saved = storage.saveReport(report(alex, steve, NOW));
        assertEquals(saved, storage.findReportById(saved.id()).orElseThrow());
        assertEquals(1, storage.countOpenReportsByReporter(alex));
        assertTrue(storage.markReportReviewed(saved.id(), "Mod", NOW));
        assertEquals(0, storage.countOpenReportsByReporter(alex));
        assertEquals(1, storage.findReports(false, 10).size());

        assertEquals(7, storage.pendingWriteCount());
    }

    @Test
    void recoversAndReplaysQueuedWritesInOrder() throws Exception {
        AtomicBoolean databaseUp = new AtomicBoolean(false);
        ResilientStorageManager storage = manager(() -> {
            if (!databaseUp.get()) {
                throw new java.sql.SQLException("Connection refused");
            }
            return new SqliteStorageProvider(sqliteConfig());
        }, Duration.ofMinutes(1));
        storage.start();
        assertFalse(storage.reconnectNow().get(2, TimeUnit.SECONDS));

        storage.saveMute(mute(steve, NOW.minusSeconds(30), Duration.ofMinutes(10), "lobby"));
        storage.deactivateActiveMutes(steve);
        storage.saveMute(mute(steve, NOW, Duration.ofMinutes(20), "lobby"));
        storage.recordStrike(strike(steve, NOW, "lobby"));
        VoiceReportRecord offlineReport = storage.saveReport(report(alex, steve, NOW));
        storage.markReportReviewed(offlineReport.id(), "Mod", NOW.plusSeconds(1));
        storage.saveReport(report(alex, steve, NOW.plusSeconds(2)));

        databaseUp.set(true);
        assertTrue(storage.reconnectNow().get(5, TimeUnit.SECONDS));
        assertTrue(storage.isAvailable());
        assertEquals(0, storage.pendingWriteCount());

        try (SqliteStorageProvider direct = new SqliteStorageProvider(sqliteConfig())) {
            assertEquals(2, direct.findMuteHistory(steve, 10).size());
            assertEquals(NOW.plus(Duration.ofMinutes(20)), direct.findActiveMute(steve, NOW).orElseThrow().expiresAt());
            assertEquals(1, direct.countActiveStrikes(steve, NOW.minusSeconds(1), true));
            assertEquals(2, direct.findReports(false, 10).size());
            assertEquals(1, direct.findReports(true, 10).size());
            assertEquals(1, direct.countOpenReportsByReporter(alex));
        }

        storage.recordStrike(strike(steve, NOW, "lobby"));
        assertEquals(2, storage.countActiveStrikes(steve, NOW.minusSeconds(1), true));
    }

    @Test
    void periodicRetryReconnectsWithoutBeingAsked() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ResilientStorageManager storage = manager(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new java.sql.SQLException("Connection refused");
            }
            return new SqliteStorageProvider(sqliteConfig());
        }, Duration.ofMillis(50));
        storage.start();
        storage.recordStrike(strike(steve, NOW, "lobby"));

        awaitTrue(storage::isAvailable, Duration.ofSeconds(5));
        awaitTrue(() -> storage.pendingWriteCount() == 0, Duration.ofSeconds(5));
        assertTrue(attempts.get() >= 3);
        assertEquals(1, storage.countActiveStrikes(steve, NOW.minusSeconds(1), true));
    }

    @Test
    void slowPrimaryIsDemotedAfterOneTimeoutAndWritesAreQueued() throws Exception {
        AtomicBoolean hang = new AtomicBoolean(false);
        InMemoryFallbackStore database = new InMemoryFallbackStore("lobby");
        StorageProvider slow = (StorageProvider) Proxy.newProxyInstance(
                StorageProvider.class.getClassLoader(), new Class<?>[] {StorageProvider.class},
                (proxy, method, args) -> {
                    if (hang.get() && !method.getName().equals("close")) {
                        Thread.sleep(10_000);
                    }
                    try {
                        return method.invoke(database, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        ResilientStorageManager storage = manager(() -> slow, Duration.ofMinutes(1));
        storage.start();
        assertTrue(storage.reconnectNow().get(2, TimeUnit.SECONDS));
        storage.recordStrike(strike(steve, NOW, "lobby"));
        assertEquals(1, database.countActiveStrikes(steve, NOW.minusSeconds(1), true));

        hang.set(true);
        long started = System.nanoTime();
        int counted = storage.countActiveStrikes(steve, NOW.minusSeconds(1), true);
        StrikeRecord queued = storage.recordStrike(strike(steve, NOW, "lobby"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(1, counted);
        assertTrue(queued.id() > 0);
        assertFalse(storage.isAvailable());
        assertTrue(elapsedMs < QUERY_TIMEOUT.toMillis() + 500, "fallback took " + elapsedMs + "ms");
        assertEquals(1, storage.pendingWriteCount());
        assertTrue(storage.lastFailure().contains("did not answer"));
    }

    private static StorageProvider rejectingPoison(InMemoryFallbackStore database) {
        return (StorageProvider) Proxy.newProxyInstance(
                StorageProvider.class.getClassLoader(), new Class<?>[] {StorageProvider.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("recordStrike") && ((StrikeRecord) args[0]).matchedWords().equals("poison")) {
                        throw new StorageException("Data too long for column 'matched_words'");
                    }
                    try {
                        return method.invoke(database, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private StrikeRecord poison() {
        return StrikeRecord.unsaved(steve, "Steve", FlagSeverity.MILD, "poison", "t", NOW, "lobby");
    }

    @Test
    void aRejectedWriteOnAHealthyPrimaryDoesNotTakeItOffline() throws Exception {
        InMemoryFallbackStore database = new InMemoryFallbackStore("lobby");
        ResilientStorageManager storage = manager(() -> rejectingPoison(database), Duration.ofMinutes(1));
        storage.start();
        assertTrue(storage.reconnectNow().get(2, TimeUnit.SECONDS));

        StrikeRecord served = storage.recordStrike(poison());
        storage.recordStrike(strike(steve, NOW, "lobby"));

        assertTrue(served.id() > 0);
        assertTrue(storage.isAvailable());
        assertEquals(0, storage.pendingWriteCount());
        assertEquals(1, database.countActiveStrikes(steve, NOW.minusSeconds(1), true));
    }

    @Test
    void aRejectedQueuedWriteDoesNotBlockRecovery() throws Exception {
        AtomicBoolean databaseUp = new AtomicBoolean(false);
        InMemoryFallbackStore database = new InMemoryFallbackStore("lobby");
        ResilientStorageManager storage = manager(() -> {
            if (!databaseUp.get()) {
                throw new java.sql.SQLException("Connection refused");
            }
            return rejectingPoison(database);
        }, Duration.ofMinutes(1));
        storage.start();
        assertFalse(storage.reconnectNow().get(2, TimeUnit.SECONDS));
        storage.recordStrike(strike(steve, NOW, "lobby"));
        storage.recordStrike(poison());
        storage.recordStrike(strike(steve, NOW, "lobby"));
        assertEquals(3, storage.pendingWriteCount());

        databaseUp.set(true);
        assertTrue(storage.reconnectNow().get(5, TimeUnit.SECONDS));

        assertTrue(storage.isAvailable());
        assertEquals(0, storage.pendingWriteCount());
        assertEquals(2, database.countActiveStrikes(steve, NOW.minusSeconds(1), true));
    }

    @Test
    void reconnectImmediatelyAfterAFailedAttemptStartsAFreshAttempt() throws Exception {
        for (int i = 0; i < 50; i++) {
            AtomicBoolean databaseUp = new AtomicBoolean(false);
            InMemoryFallbackStore database = new InMemoryFallbackStore("lobby");
            ResilientStorageManager storage = manager(() -> {
                if (!databaseUp.get()) {
                    throw new java.sql.SQLException("Connection refused");
                }
                return database;
            }, Duration.ofMinutes(1));
            storage.start();
            assertFalse(storage.reconnectNow().get(2, TimeUnit.SECONDS));
            databaseUp.set(true);
            assertTrue(storage.reconnectNow().get(2, TimeUnit.SECONDS), "iteration " + i);
            storage.close();
        }
    }

    @Test
    void asyncHelpersRunOnVirtualThreads() throws Exception {
        ResilientStorageManager storage = manager(() -> new SqliteStorageProvider(sqliteConfig()), Duration.ofMinutes(1));
        storage.start();
        assertTrue(storage.reconnectNow().get(5, TimeUnit.SECONDS));

        assertTrue(storage.supplyAsync(ignored -> Thread.currentThread().isVirtual()).get(2, TimeUnit.SECONDS));
        StrikeRecord saved = storage.recordStrikeAsync(strike(steve, NOW, "lobby")).get(2, TimeUnit.SECONDS);
        assertTrue(saved.id() > 0);
        assertEquals(1, storage.countActiveStrikesAsync(steve, NOW.minusSeconds(1), true).get(2, TimeUnit.SECONDS));
        storage.saveMuteAsync(mute(steve, NOW, Duration.ofMinutes(5), "lobby")).get(2, TimeUnit.SECONDS);
        assertTrue(storage.findActiveMuteAsync(steve).get(2, TimeUnit.SECONDS).isPresent());
        assertEquals(new PardonResult(1, 1), storage.pardonPlayerAsync(steve).get(2, TimeUnit.SECONDS));
        VoiceReportRecord report = storage.saveReportAsync(report(alex, steve, NOW)).get(2, TimeUnit.SECONDS);
        assertEquals(report, storage.findReportsAsync(true, 5).get(2, TimeUnit.SECONDS).getFirst());
    }

    @Test
    void closeStopsRetryingAndClosesThePrimary() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        InMemoryFallbackStore database = new InMemoryFallbackStore("lobby");
        StorageProvider tracked = (StorageProvider) Proxy.newProxyInstance(
                StorageProvider.class.getClassLoader(), new Class<?>[] {StorageProvider.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closed.set(true);
                    }
                    return method.invoke(database, args);
                });
        ResilientStorageManager storage = manager(() -> tracked, Duration.ofMinutes(1));
        storage.start();
        assertTrue(storage.reconnectNow().get(2, TimeUnit.SECONDS));
        storage.close();
        assertTrue(closed.get());
        assertFalse(storage.isAvailable());
        assertFalse(storage.reconnectNow().get(1, TimeUnit.SECONDS));
    }

    @Test
    void startFutureCompletesAfterPrimaryIsReadyForStartupReads() throws Exception {
        InMemoryFallbackStore database = new InMemoryFallbackStore("lobby");
        database.saveMute(mute(steve, NOW, Duration.ofMinutes(30), "lobby"));
        java.util.concurrent.CountDownLatch allowOpen = new java.util.concurrent.CountDownLatch(1);
        ResilientStorageManager storage = manager(() -> {
            try {
                allowOpen.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return database;
        }, Duration.ofMinutes(1));
        java.util.concurrent.CompletableFuture<Boolean> ready = storage.start();
        assertFalse(ready.isDone());
        allowOpen.countDown();
        int loaded = ready.thenApply(connected -> storage.findAllActiveMutes(NOW).size()).get(5, TimeUnit.SECONDS);
        assertEquals(1, loaded);
    }

    @Test
    void findReportByIdResolvesOfflineFallbackIdAfterReplay() throws Exception {
        AtomicBoolean databaseUp = new AtomicBoolean(false);
        ResilientStorageManager storage = manager(() -> {
            if (!databaseUp.get()) {
                throw new java.sql.SQLException("offline");
            }
            return new SqliteStorageProvider(sqliteConfig());
        }, Duration.ofMinutes(1));
        storage.start();
        assertFalse(storage.reconnectNow().get(2, TimeUnit.SECONDS));

        VoiceReportRecord offline = storage.saveReport(report(alex, steve, NOW));
        assertTrue(offline.id() < 0);

        databaseUp.set(true);
        assertTrue(storage.reconnectNow().get(5, TimeUnit.SECONDS));

        VoiceReportRecord resolved = storage.findReportById(offline.id()).orElseThrow();
        assertTrue(resolved.id() > 0);
        assertEquals(steve, resolved.targetId());
    }

    @Test
    void fallbackPruneBeforePrunesExpiredReportsToo() {
        InMemoryFallbackStore store = new InMemoryFallbackStore("lobby");
        store.saveReport(report(alex, steve, NOW.minus(Duration.ofDays(2))));
        store.saveReport(report(alex, steve, NOW));
        int pruned = store.pruneBefore(NOW.minus(Duration.ofDays(1)), NOW);
        assertEquals(1, pruned);
        assertEquals(1, store.findReports(false, 10).size());
    }

    @Test
    void closePreventsSubsequentPendingWrites() throws Exception {
        ResilientStorageManager storage = manager(() -> {
            throw new java.sql.SQLException("offline");
        }, Duration.ofMinutes(1));
        storage.close();
        storage.saveMute(mute(steve, NOW, Duration.ofMinutes(5), "lobby"));
        storage.recordStrike(strike(steve, NOW, "lobby"));
        assertEquals(0, storage.pendingWriteCount());
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(10);
        }
    }
}
