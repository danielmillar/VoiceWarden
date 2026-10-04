package dev.danielmillar.nevusvoice.concurrent;

import dev.danielmillar.nevusvoice.TestFixtures;
import dev.danielmillar.nevusvoice.evidence.EvidenceSettings;
import dev.danielmillar.nevusvoice.evidence.EvidenceStore;
import dev.danielmillar.nevusvoice.testutil.Fakes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PluginExecutorsTest {
    @TempDir Path folder;
    private final Logger logger = Logger.getAnonymousLogger();

    @Test void overflowingEvidenceCompletesExceptionallyAndPermitsAreReused() {
        var delegate = new Fakes.Executor(false);
        var bounded = PluginExecutors.bounded(delegate, 1, logger, "Evidence storage");
        var store = new EvidenceStore(() -> new EvidenceSettings(EvidenceSettings.Mode.NONE, folder, false, 0, 0), bounded, logger);
        var first = store.saveIncident(TestFixtures.incident());
        var rejected = store.saveIncident(TestFixtures.incident());
        assertTrue(rejected.isCompletedExceptionally());
        assertThrows(CompletionException.class, rejected::join);
        delegate.drain();
        assertNull(first.join());
        var next = store.saveIncident(TestFixtures.incident());
        delegate.drain();
        assertNull(next.join());
    }

    @Test void delegateRejectionIsPropagatedAndReleasesCapacity() {
        var attempts = new AtomicInteger();
        Executor delegate = task -> {
            if (attempts.incrementAndGet() == 1) throw new RejectedExecutionException();
            task.run();
        };
        var bounded = PluginExecutors.bounded(delegate, 1, logger, "Evidence storage");
        assertThrows(RejectedExecutionException.class, () -> bounded.execute(() -> fail("Rejected task ran")));
        var ran = new AtomicInteger();
        bounded.execute(ran::incrementAndGet);
        assertEquals(1, ran.get());
    }

    @Test void shutdownReturnsWhileWorkersAndPersistenceAreBlockedAndDrainsIo() throws Exception {
        var executors = new PluginExecutors(1, logger);
        var workers = new CompletableFuture<Void>();
        var ioEntered = new CountDownLatch(1);
        var releaseIo = new CountDownLatch(1);
        var saving = new CountDownLatch(1);
        var releaseSave = new CountDownLatch(1);
        executors.io().execute(() -> {
            ioEntered.countDown();
            await(releaseIo);
        });
        assertTrue(ioEntered.await(3, TimeUnit.SECONDS));
        var done = executors.shutdownAfter(workers, () -> {
            saving.countDown();
            await(releaseSave);
        });
        try {
            assertFalse(done.isDone());
            assertEquals(1, saving.getCount());
            workers.complete(null);
            releaseIo.countDown();
            assertTrue(saving.await(3, TimeUnit.SECONDS));
            assertFalse(done.isDone());
            assertTrue(executors.io().isTerminated());
        } finally {
            workers.complete(null);
            releaseIo.countDown();
            releaseSave.countDown();
            done.get(3, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
}
