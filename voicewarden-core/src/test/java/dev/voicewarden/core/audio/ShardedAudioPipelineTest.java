package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ShardedAudioPipelineTest {

    private static final int FRAME_48K = AudioFormat.OPUS_FRAME_20MS_48K;

    static AudioPipelineStats awaitStats(ShardedAudioPipeline pipeline, Predicate<AudioPipelineStats> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AudioPipelineStats stats = pipeline.stats();
        while (!condition.test(stats)) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for pipeline, last stats " + stats);
            }
            LockSupport.parkNanos(150_000L);
            stats = pipeline.stats();
        }
        return stats;
    }

    static boolean drained(AudioPipelineStats s) {
        return s.framesProcessed() + s.framesDroppedQueueFull() == s.framesSubmitted();
    }

    private static void submitPcm(ShardedAudioPipeline pipeline, UUID player, short[] pcm48k) {
        for (int pos = 0; pos < pcm48k.length; pos += FRAME_48K) {
            assertTrue(pipeline.submitPcm48k(player, pcm48k, pos, FRAME_48K));
        }
    }

    @Test
    void shardsDeterministicallyOntoNamedDaemonWorkers() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(
                AudioConfig.builder().workerThreads(4).build(), sink)) {
            assertEquals(4, pipeline.workerCount());
            for (int i = 0; i < 100; i++) {
                UUID id = UUID.randomUUID();
                assertEquals(Math.floorMod(id.hashCode(), 4), pipeline.shardOf(id));
            }
            for (int i = 0; i < 4; i++) {
                Thread worker = pipeline.workerThread(i);
                assertEquals("voicewarden-audio-worker-" + i, worker.getName());
                assertTrue(worker.isDaemon());
            }
        }
    }

    @Test
    void decodesOpusEndToEndIntoLiveChunksAndFinalUtterance() {
        short[] pcm = TestSignals.concat(
                new short[FRAME_48K * 10],
                TestSignals.sine(300, AudioFormat.SAMPLE_RATE_48K, FRAME_48K * 50, 8_000, 0),
                new short[FRAME_48K * 30]);
        List<byte[]> packets = TestSignals.encodeOpus(pcm);
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        UUID player = UUID.randomUUID();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(AudioConfig.defaults(), sink)) {
            for (byte[] packet : packets) {
                assertTrue(pipeline.submitOpus(player, packet, 0, packet.length));
            }
            AudioPipelineStats stats = awaitStats(pipeline, s -> s.finalUtterancesEmitted() == 1 && drained(s));
            assertEquals(packets.size(), stats.framesProcessed());
            assertEquals(0, stats.framesDroppedQueueFull());
            assertEquals(1, stats.activeSessions());
            assertEquals(0, stats.decodeErrors());
            FinalUtterance utterance = sink.finals.get(0);
            assertEquals(player, utterance.playerId());
            assertTrue(utterance.durationMs() >= 1000 && utterance.durationMs() <= 1460, "duration " + utterance.durationMs());
            assertTrue(stats.liveChunksEmitted() >= 5);
            double tone = TestSignals.goertzelPower(utterance.pcm16k(), 3200, 3200 + 12_000, 300, 16_000);
            double rms = TestSignals.rms(utterance.pcm16k(), 3200, 3200 + 12_000);
            assertTrue(tone > 0.8 * rms * rms, "300 Hz should survive decode and decimation");
        }
    }

    @Test
    void sustainedClippingReachesTheSinkThroughTheWorkerAndIsCounted() {
        short[] square = new short[FRAME_48K * 100];
        for (int i = 0; i < square.length; i++) {
            square[i] = (i / 160) % 2 == 0 ? Short.MAX_VALUE : Short.MIN_VALUE;
        }
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        UUID player = UUID.randomUUID();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(AudioConfig.defaults(), sink)) {
            submitPcm(pipeline, player, square);
            AudioPipelineStats stats = awaitStats(pipeline, s -> s.acousticAbuseEvents() == 1 && drained(s));
            assertEquals(0, stats.processingErrors());
            assertEquals(1, sink.abuse.size());
            assertEquals(player, sink.abuse.getFirst().playerId());
        }
    }

    @Test
    void speakingStoppedFrameClosesUtteranceInOrder() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        UUID player = UUID.randomUUID();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(
                AudioConfig.builder().silenceThresholdMs(5_000).build(), sink)) {
            submitPcm(pipeline, player, TestSignals.sine(300, 48_000, FRAME_48K * 40, 8_000, 0));
            pipeline.markSpeakingStopped(player);
            AudioPipelineStats stats = awaitStats(pipeline, s -> s.finalUtterancesEmitted() == 1);
            assertEquals(41, stats.framesSubmitted());
            assertEquals(1, sink.finals.size());
            assertEquals(800, sink.finals.get(0).durationMs());
        }
    }

    @Test
    void idleSweepClosesUtteranceWhenPacketsStopWithoutStopSignal() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        UUID player = UUID.randomUUID();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(
                AudioConfig.builder().silenceThresholdMs(100).build(), sink)) {
            submitPcm(pipeline, player, TestSignals.sine(300, 48_000, FRAME_48K * 40, 8_000, 0));
            awaitStats(pipeline, s -> s.finalUtterancesEmitted() == 1);
        }
    }

    @Test
    void stalledWorkerBacklogIsNotMistakenForPacketsStopping() {
        TestSignals.RecordingSink inner = new TestSignals.RecordingSink();
        AudioEventSink slowFirstChunk = new AudioEventSink() {
            private boolean stalled;

            @Override
            public void onLiveChunk(LiveAudioChunk chunk) {
                if (!stalled) {
                    stalled = true;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(400));
                }
                inner.onLiveChunk(chunk);
            }

            @Override
            public void onFinalUtterance(FinalUtterance utterance) {
                inner.onFinalUtterance(utterance);
            }
        };
        UUID player = UUID.randomUUID();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(
                AudioConfig.builder().workerThreads(1).silenceThresholdMs(100).build(), slowFirstChunk)) {
            submitPcm(pipeline, player, TestSignals.sine(300, 48_000, FRAME_48K * 50, 8_000, 0));
            pipeline.markSpeakingStopped(player);
            AudioPipelineStats stats = awaitStats(pipeline, s -> drained(s) && s.finalUtterancesEmitted() >= 1);
            assertEquals(1, stats.finalUtterancesEmitted());
            assertEquals(0, stats.utterancesDiscardedTooShort());
            assertEquals(1000, inner.finals.get(0).durationMs());
        }
    }

    @Test
    void shedsOldestFramesWhenWorkerQueueIsFull() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        AudioConfig config = AudioConfig.builder().workerThreads(1).workerQueueCapacity(16).framePoolCapacity(64).build();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(config, sink, () -> new BlockingDecoder(blocked, release))) {
            UUID player = UUID.randomUUID();
            byte[] packet = new byte[FRAME_48K * 2];
            assertTrue(pipeline.submitOpus(player, packet, 0, 640));
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 200; i++) {
                assertTrue(pipeline.submitOpus(player, packet, 0, 640));
            }
            AudioPipelineStats stalled = pipeline.stats();
            assertEquals(201, stalled.framesSubmitted());
            assertEquals(200 - 16, stalled.framesDroppedQueueFull());
            assertEquals(16, stalled.queuedFrames());
            assertEquals(0, stalled.framesDroppedPoolExhausted());

            release.countDown();
            AudioPipelineStats done = awaitStats(pipeline, ShardedAudioPipelineTest::drained);
            assertEquals(17, done.framesProcessed());
            assertEquals(0, done.processingErrors());
        }
    }

    @Test
    void poolExhaustionRejectsWithoutAllocating() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        AudioConfig config = AudioConfig.builder().workerThreads(1).workerQueueCapacity(64).framePoolCapacity(16).build();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(
                config, new TestSignals.RecordingSink(), () -> new BlockingDecoder(blocked, release))) {
            UUID player = UUID.randomUUID();
            byte[] packet = new byte[640];
            pipeline.submitOpus(player, packet, 0, packet.length);
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            int accepted = 0;
            for (int i = 0; i < 40; i++) {
                if (pipeline.submitOpus(player, packet, 0, packet.length)) {
                    accepted++;
                }
            }
            assertEquals(15, accepted);
            assertEquals(25, pipeline.stats().framesDroppedPoolExhausted());
            release.countDown();
            awaitStats(pipeline, ShardedAudioPipelineTest::drained);
        }
    }

    @Test
    void rejectsInvalidFrames() {
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(AudioConfig.defaults(), new TestSignals.RecordingSink())) {
            UUID player = UUID.randomUUID();
            assertFalse(pipeline.submitOpus(player, new byte[2000], 0, 2000));
            assertFalse(pipeline.submitOpus(player, new byte[10], 5, 10));
            assertFalse(pipeline.submitOpus(player, new byte[10], 0, 0));
            assertFalse(pipeline.submitPcm48k(player, new short[4000], 0, 4000));
            assertFalse(pipeline.submitOpus(null, new byte[10], 0, 10));
            assertEquals(5, pipeline.stats().framesRejectedInvalid());
            assertEquals(0, pipeline.stats().framesSubmitted());
        }
    }

    @Test
    void removePlayerProcessesQueuedFramesThenFreesTheSession() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        UUID player = UUID.randomUUID();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(
                AudioConfig.builder().workerThreads(1).build(), sink)) {
            submitPcm(pipeline, player, TestSignals.sine(300, 48_000, FRAME_48K * 50, 8_000, 0));
            pipeline.removePlayer(player);
            AudioPipelineStats stats = awaitStats(pipeline, s -> drained(s) && s.activeSessions() == 0
                    && s.finalUtterancesEmitted() == 1);
            assertEquals(50, stats.framesProcessed());
            assertEquals(1000, sink.finals.get(0).durationMs());
        }
    }

    @Test
    void dropUtteranceStopsLiveChunksMidSentence() throws InterruptedException {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        UUID player = UUID.randomUUID();
        short[] speech = TestSignals.sine(300, 48_000, FRAME_48K * 300, 8_000, 0);
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(AudioConfig.defaults(), sink)) {
            for (int pos = 0; pos < FRAME_48K * 20; pos += FRAME_48K) {
                pipeline.submitPcm48k(player, speech, pos, FRAME_48K);
            }
            awaitStats(pipeline, s -> s.liveChunksEmitted() >= 1 && drained(s));
            long utteranceId = sink.chunks.get(0).utteranceId();
            pipeline.dropUtterance(player, utteranceId);
            long chunksAtDrop = pipeline.stats().liveChunksEmitted();
            awaitStats(pipeline, s -> s.utterancesDropped() == 1);
            for (int pos = FRAME_48K * 20; pos < speech.length; pos += FRAME_48K) {
                pipeline.submitPcm48k(player, speech, pos, FRAME_48K);
            }
            pipeline.markSpeakingStopped(player);
            AudioPipelineStats stats = awaitStats(pipeline, ShardedAudioPipelineTest::drained);
            assertEquals(chunksAtDrop, stats.liveChunksEmitted());
            assertEquals(0, stats.finalUtterancesEmitted());
            assertTrue(sink.chunks.stream().allMatch(c -> c.utteranceId() == utteranceId));
        }
    }

    @Test
    void throwingSinkDoesNotKillTheWorker() {
        AudioEventSink exploding = new AudioEventSink() {
            @Override
            public void onLiveChunk(LiveAudioChunk chunk) {
                throw new IllegalStateException("sink failure");
            }

            @Override
            public void onFinalUtterance(FinalUtterance utterance) {
                throw new IllegalStateException("sink failure");
            }
        };
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(
                AudioConfig.builder().workerThreads(1).build(), exploding)) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            submitPcm(pipeline, a, TestSignals.sine(300, 48_000, FRAME_48K * 50, 8_000, 0));
            pipeline.markSpeakingStopped(a);
            submitPcm(pipeline, b, TestSignals.sine(300, 48_000, FRAME_48K * 50, 8_000, 0));
            pipeline.markSpeakingStopped(b);
            AudioPipelineStats stats = awaitStats(pipeline, s -> s.finalUtterancesEmitted() == 2 && drained(s));
            assertTrue(stats.processingErrors() >= 2);
            assertTrue(pipeline.workerThread(0).isAlive());
        }
    }

    @Test
    void utteranceIdsStayUniqueAcrossRejoin() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        UUID player = UUID.randomUUID();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(AudioConfig.defaults(), sink)) {
            for (int round = 0; round < 3; round++) {
                submitPcm(pipeline, player, TestSignals.sine(300, 48_000, FRAME_48K * 40, 8_000, 0));
                pipeline.removePlayer(player);
                int expected = round + 1;
                awaitStats(pipeline, s -> s.finalUtterancesEmitted() == expected && s.activeSessions() == 0);
            }
            Set<Long> ids = new HashSet<>();
            sink.finals.forEach(f -> ids.add(f.utteranceId()));
            assertEquals(3, ids.size());
        }
    }

    @Test
    void configUpdateReachesWorkerSessions() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        UUID player = UUID.randomUUID();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(AudioConfig.defaults(), sink)) {
            submitPcm(pipeline, player, TestSignals.sine(300, 48_000, FRAME_48K * 40, 8_000, 0));
            pipeline.markSpeakingStopped(player);
            awaitStats(pipeline, s -> s.finalUtterancesEmitted() == 1 && drained(s));
            long chunks = pipeline.stats().liveChunksEmitted();
            pipeline.updateConfig(AudioConfig.builder().liveDetection(false).build());
            submitPcm(pipeline, player, TestSignals.sine(300, 48_000, FRAME_48K * 40, 8_000, 0));
            pipeline.markSpeakingStopped(player);
            AudioPipelineStats stats = awaitStats(pipeline, s -> s.finalUtterancesEmitted() == 2 && drained(s));
            assertEquals(chunks, stats.liveChunksEmitted());
            assertFalse(pipeline.config().liveDetection());
        }
    }

    @Test
    void ingressDoesNotAllocateInSteadyState() {
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(AudioConfig.defaults(), new TestSignals.RecordingSink())) {
            UUID player = UUID.randomUUID();
            byte[] packet = TestSignals.encodeOpus(TestSignals.sine(300, 48_000, FRAME_48K, 8_000, 0)).get(0);
            for (int i = 0; i < 20_000; i++) {
                pipeline.submitOpus(player, packet, 0, packet.length);
            }
            Thread self = Thread.currentThread();
            long before = TestSignals.allocatedBytes(self);
            for (int i = 0; i < 10_000; i++) {
                pipeline.submitOpus(player, packet, 0, packet.length);
            }
            long allocated = TestSignals.allocatedBytes(self) - before;
            assertTrue(allocated < 4096, "ingress allocated " + allocated + " bytes over 10,000 packets");
        }
    }

    private static final class BlockingDecoder extends TestSignals.PassThroughDecoder {
        private final CountDownLatch blocked;
        private final CountDownLatch release;

        BlockingDecoder(CountDownLatch blocked, CountDownLatch release) {
            this.blocked = blocked;
            this.release = release;
        }

        @Override
        public int decode(byte[] packet, int offset, int length, short[] out48k) {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.decode(packet, offset, length, out48k);
        }
    }
}
