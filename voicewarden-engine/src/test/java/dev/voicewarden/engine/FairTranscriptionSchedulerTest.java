package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.core.ipc.IpcMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FairTranscriptionSchedulerTest {

    private static final UUID BLOCKER = new UUID(0, 99);
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CAROL = new UUID(0, 3);

    private final BlockingQueue<IpcMessage.TranscriptResult> results = new LinkedBlockingQueue<>();
    private FairTranscriptionScheduler scheduler;

    @AfterEach
    void close() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private static TranscriptionJob live(UUID player, long utterance) {
        return new TranscriptionJob.Live(player, utterance, 0, false, new short[160], System.nanoTime());
    }

    private static TranscriptionJob fin(UUID player, long utterance) {
        return fin(player, utterance, 320);
    }

    private static TranscriptionJob fin(UUID player, long utterance, int samples) {
        return new TranscriptionJob.Final(player, utterance, false, new short[samples], System.nanoTime());
    }

    private static TranscriptionJob verify(UUID player, long utterance) {
        return new TranscriptionJob.Verify(player, utterance, "bad word", new short[320], System.nanoTime());
    }

    private FakeSpeechEngine blockedEngine(CountDownLatch gate) throws InterruptedException {
        FakeSpeechEngine engine = new FakeSpeechEngine(gate);
        scheduler = FairTranscriptionScheduler.start(engine, results::add, 1, 8);
        scheduler.submit(fin(BLOCKER, 1));
        assertTrue(engine.firstCallStarted.await(5, TimeUnit.SECONDS));
        return engine;
    }

    private List<IpcMessage.TranscriptResult> await(int count) throws InterruptedException {
        List<IpcMessage.TranscriptResult> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            IpcMessage.TranscriptResult r = results.poll(5, TimeUnit.SECONDS);
            assertNotNull(r, "timed out waiting for result " + (i + 1) + " of " + count);
            out.add(r);
        }
        return out;
    }

    @Test
    void priorityLaneRunsBeforeQueuedFinals() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        FakeSpeechEngine engine = blockedEngine(gate);
        scheduler.submit(fin(ALICE, 1));
        scheduler.submit(fin(BOB, 1));
        scheduler.submit(live(CAROL, 7));
        scheduler.submit(verify(ALICE, 2));
        gate.countDown();
        await(5);
        assertEquals(List.of("final:99:1", "live:3:7", "verify:1:2", "final:1:1", "final:2:1"), engine.calls);
    }

    @Test
    void finalsAreDrainedRoundRobinAcrossPlayers() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        FakeSpeechEngine engine = blockedEngine(gate);
        for (int i = 1; i <= 3; i++) {
            scheduler.submit(fin(ALICE, i));
        }
        scheduler.submit(fin(BOB, 1));
        scheduler.submit(fin(CAROL, 1));
        scheduler.submit(fin(BOB, 2));
        gate.countDown();
        await(7);
        assertEquals(List.of("final:99:1", "final:1:1", "final:2:1", "final:3:1", "final:1:2", "final:2:2",
                "final:1:3"), engine.calls);
    }

    @Test
    void overloadShedsOldestJobAndCountsIt() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        FakeSpeechEngine engine = new FakeSpeechEngine(gate);
        scheduler = FairTranscriptionScheduler.start(engine, results::add, 1, 3);
        scheduler.submit(fin(BLOCKER, 1));
        assertTrue(engine.firstCallStarted.await(5, TimeUnit.SECONDS));
        for (int i = 1; i <= 5; i++) {
            scheduler.submit(fin(ALICE, i));
        }
        SchedulerStats stats = scheduler.stats();
        assertEquals(2, stats.droppedOverload());
        assertEquals(3, stats.queuedJobs());
        gate.countDown();
        await(4);
        assertEquals(List.of("final:99:1", "final:1:3", "final:1:4", "final:1:5"), engine.calls);
        assertEquals(4, scheduler.stats().processedFinal());
        assertEquals(0, scheduler.stats().queuedJobs());
    }

    @Test
    void dropUtterancePurgesQueuedJobsAndSuppressesInFlightResult() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        FakeSpeechEngine engine = blockedEngine(gate);
        scheduler.submit(live(ALICE, 5));
        scheduler.submit(fin(ALICE, 5));
        scheduler.submit(fin(ALICE, 6));
        scheduler.dropUtterance(ALICE, 5);
        scheduler.dropUtterance(BLOCKER, 1);
        assertEquals(List.of("1:5", "99:1"), engine.drops);
        gate.countDown();
        List<IpcMessage.TranscriptResult> got = await(1);
        assertEquals(ALICE, got.get(0).playerId());
        assertEquals(6, got.get(0).utteranceId());
        assertEquals(List.of("final:99:1", "final:1:6"), engine.calls);
        assertEquals(null, results.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void onePlayerNeverRunsTwoJobsAtOnceSoLiveChunksStayOrdered() throws InterruptedException {
        FakeSpeechEngine engine = new FakeSpeechEngine();
        scheduler = FairTranscriptionScheduler.start(engine, results::add, 4, 64);
        for (int i = 0; i < 40; i++) {
            scheduler.submit(new TranscriptionJob.Live(ALICE, 1, i, false, new short[i + 1], System.nanoTime()));
        }
        List<IpcMessage.TranscriptResult> got = await(40);
        for (int i = 0; i < 40; i++) {
            assertEquals("live " + (i + 1), got.get(i).text());
        }
    }

    @Test
    void unchangedLivePartialsAreNotResent() throws InterruptedException {
        FakeSpeechEngine engine = new FakeSpeechEngine();
        scheduler = FairTranscriptionScheduler.start(engine, results::add, 1, 16);
        scheduler.submit(new TranscriptionJob.Live(ALICE, 1, 0, false, new short[10], System.nanoTime()));
        scheduler.submit(new TranscriptionJob.Live(ALICE, 1, 1, false, new short[10], System.nanoTime()));
        scheduler.submit(new TranscriptionJob.Live(ALICE, 1, 2, false, new short[11], System.nanoTime()));
        scheduler.submit(new TranscriptionJob.Live(ALICE, 1, 3, true, new short[11], System.nanoTime()));
        scheduler.submit(new TranscriptionJob.Live(ALICE, 2, 0, false, new short[11], System.nanoTime()));
        List<IpcMessage.TranscriptResult> got = await(4);
        assertEquals(List.of("live 10", "live 11", "live 11 end", "live 11"),
                got.stream().map(IpcMessage.TranscriptResult::text).toList());
        assertEquals(2, got.get(3).utteranceId());
        assertEquals(null, results.poll(200, TimeUnit.MILLISECONDS));
        assertEquals(5, scheduler.stats().processedLive());
    }

    @Test
    void offlineProfilesSendNoLiveResults() throws InterruptedException {
        scheduler = FairTranscriptionScheduler.start(new FakeSpeechEngine(null, false), results::add, 1, 8);
        scheduler.submit(new TranscriptionJob.Live(ALICE, 1, 0, true, new short[10], System.nanoTime()));
        scheduler.submit(fin(ALICE, 1));
        IpcMessage.TranscriptResult only = await(1).get(0);
        assertTrue(only.isFinal());
        assertEquals(null, results.poll(200, TimeUnit.MILLISECONDS));
        assertEquals(1, scheduler.stats().processedLive());
    }

    @Test
    void resultsCarryLaneFlagsAndConfirmation() throws InterruptedException {
        FakeSpeechEngine engine = new FakeSpeechEngine();
        scheduler = FairTranscriptionScheduler.start(engine, results::add, 1, 8);
        scheduler.submit(live(ALICE, 1));
        IpcMessage.TranscriptResult liveResult = await(1).get(0);
        assertTrue(liveResult.isLive());
        assertFalse(liveResult.isFinal());
        assertEquals(ConfirmationState.UNCHECKED, liveResult.confirmationState());

        scheduler.submit(new TranscriptionJob.Final(ALICE, 2, true, new short[10], System.nanoTime()));
        IpcMessage.TranscriptResult finalResult = await(1).get(0);
        assertTrue(finalResult.isFinal());
        assertFalse(finalResult.isVerificationResult());
        assertEquals(ConfirmationState.CONFIRMED, finalResult.confirmationState());

        scheduler.submit(new TranscriptionJob.Verify(ALICE, 3, "static noise", new short[10], System.nanoTime()));
        IpcMessage.TranscriptResult verifyResult = await(1).get(0);
        assertTrue(verifyResult.isVerificationResult());
        assertEquals(ConfirmationState.UNCONFIRMED, verifyResult.confirmationState());
        assertEquals(1, scheduler.stats().processedLive());
        assertEquals(1, scheduler.stats().processedFinal());
        assertEquals(1, scheduler.stats().processedVerify());
    }

    @Test
    void engineFailureYieldsUncheckedFinalAndKeepsWorking() throws InterruptedException {
        FakeSpeechEngine engine = new FakeSpeechEngine();
        scheduler = FairTranscriptionScheduler.start(engine, results::add, 1, 8);
        scheduler.submit(fin(ALICE, 1, 13));
        scheduler.submit(fin(ALICE, 2));
        List<IpcMessage.TranscriptResult> got = await(2);
        assertEquals("", got.get(0).text());
        assertEquals(ConfirmationState.UNCHECKED, got.get(0).confirmationState());
        assertEquals("final 320", got.get(1).text());
        assertEquals(1, scheduler.stats().failed());
    }

    @Test
    void closedSchedulerRejectsWork() {
        scheduler = FairTranscriptionScheduler.start(new FakeSpeechEngine(), results::add, 2, 8);
        scheduler.close();
        assertFalse(scheduler.submit(fin(ALICE, 1)));
    }

    @Test
    void purgeAllDropsQueuedWorkAndDrainsInFlightBeforeReturning() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        FakeSpeechEngine engine = blockedEngine(gate);
        scheduler.submit(fin(ALICE, 1));
        scheduler.submit(fin(BOB, 1));
        Thread release = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            gate.countDown();
        });
        release.start();
        scheduler.purgeAll(java.time.Duration.ofSeconds(5));
        release.join(2_000);
        assertEquals(0, scheduler.stats().queuedJobs());
        assertEquals(List.of("final:99:1"), engine.calls);
    }
}
