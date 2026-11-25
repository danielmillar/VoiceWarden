package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

class AudioPipelineStressTest {

    private static final int SPEAKERS = 50;
    private static final int FRAMES_PER_SPEAKER = 100;
    private static final int TOTAL_FRAMES = SPEAKERS * FRAMES_PER_SPEAKER;
    private static final int FRAME_48K = AudioFormat.OPUS_FRAME_20MS_48K;
    private static final int VOICED_FRAMES = 60;
    private static final double SIMULATED_SECONDS = FRAMES_PER_SPEAKER * 0.02;

    private static List<List<byte[]>> speakerPackets;

    @BeforeAll
    static void encodeSpeakers() {
        speakerPackets = new ArrayList<>();
        for (int s = 0; s < SPEAKERS; s++) {
            short[] pcm = new short[FRAME_48K * FRAMES_PER_SPEAKER];
            double pitch = 110 + s * 5.3;
            for (int i = 0; i < FRAME_48K * VOICED_FRAMES; i++) {
                double t = (double) i / AudioFormat.SAMPLE_RATE_48K;
                double envelope = 0.6 + 0.4 * Math.sin(2 * Math.PI * 4 * t);
                double voice = Math.sin(2 * Math.PI * pitch * t)
                        + 0.5 * Math.sin(2 * Math.PI * pitch * 2 * t)
                        + 0.25 * Math.sin(2 * Math.PI * pitch * 3 * t);
                pcm[i] = (short) Math.round(5_000 * envelope * voice);
            }
            speakerPackets.add(TestSignals.encodeOpus(pcm));
        }
    }

    @Test
    void fiftySpeakerBurstProcessesFarFasterThanRealTime(TestReporter reporter) {
        AudioConfig config = AudioConfig.builder()
                .workerQueueCapacity(TOTAL_FRAMES)
                .framePoolCapacity(TOTAL_FRAMES * 2)
                .build();
        runBurst(config, uuids(SPEAKERS, 1_000));

        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(config, sink)) {
            List<UUID> players = uuids(SPEAKERS, 2_000);
            long[] workerAllocBefore = workerAllocations(pipeline);
            long start = System.nanoTime();
            submitInterleaved(pipeline, players);
            long submitted = System.nanoTime();
            AudioPipelineStats stats = ShardedAudioPipelineTest.awaitStats(pipeline,
                    s -> s.framesProcessed() == TOTAL_FRAMES);
            long processed = System.nanoTime();
            AudioPipelineStats closed = ShardedAudioPipelineTest.awaitStats(pipeline,
                    s -> s.finalUtterancesEmitted() == SPEAKERS);
            long workerAllocated = sum(workerAllocations(pipeline)) - sum(workerAllocBefore);

            double wallMs = (processed - start) / 1e6;
            double audioSeconds = SPEAKERS * SIMULATED_SECONDS;
            String report = String.format(Locale.ROOT,
                    "burst: %d speakers x %d frames = %d Opus frames on %d workers | ingress %.1f ms | "
                            + "decode+FIR+VAD+chunk wall %.1f ms | %.0f frames/s | %.1f us/frame | "
                            + "%.0fx real time | live chunks %d | finals %d | worker alloc incl. 50 session setups %.0f B/frame | %s",
                    SPEAKERS, FRAMES_PER_SPEAKER, TOTAL_FRAMES, pipeline.workerCount(),
                    (submitted - start) / 1e6, wallMs, TOTAL_FRAMES / (wallMs / 1000), wallMs * 1000 / TOTAL_FRAMES,
                    audioSeconds / (wallMs / 1000), closed.liveChunksEmitted(), closed.finalUtterancesEmitted(),
                    (double) workerAllocated / TOTAL_FRAMES, closed);
            System.out.println(report);
            reporter.publishEntry("audio-stress-burst", report);

            assertEquals(TOTAL_FRAMES, stats.framesSubmitted());
            assertEquals(0, closed.framesDroppedQueueFull());
            assertEquals(0, closed.framesDroppedPoolExhausted());
            assertEquals(0, closed.decodeErrors());
            assertEquals(0, closed.processingErrors());
            assertEquals(SPEAKERS, closed.activeSessions());
            assertEquals(SPEAKERS, sink.finals.size());
            assertTrue(closed.liveChunksEmitted() >= SPEAKERS * 6L, "live chunks " + closed.liveChunksEmitted());
            for (FinalUtterance utterance : sink.finals) {
                assertTrue(utterance.durationMs() >= 1_100 && utterance.durationMs() <= 1_500,
                        "utterance " + utterance.durationMs() + " ms");
            }
            assertTrue(wallMs < SIMULATED_SECONDS * 1000 / 2,
                    "50 speakers took " + wallMs + " ms for " + SIMULATED_SECONDS + " s of audio");
        }
    }

    @Test
    void fiftySpeakersPacedInRealTimeWithDefaultConfigNeverShed(TestReporter reporter) throws InterruptedException {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        runBurst(AudioConfig.builder().workerQueueCapacity(TOTAL_FRAMES).framePoolCapacity(TOTAL_FRAMES * 2).build(),
                uuids(SPEAKERS, 3_000));
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(AudioConfig.defaults(), sink)) {
            List<UUID> players = uuids(SPEAKERS, 4_000);
            int maxQueued = 0;
            long tickNanos = TimeUnit.MILLISECONDS.toNanos(20);
            long start = System.nanoTime();
            for (int f = 0; f < FRAMES_PER_SPEAKER; f++) {
                for (int s = 0; s < SPEAKERS; s++) {
                    byte[] packet = speakerPackets.get(s).get(f);
                    assertTrue(pipeline.submitOpus(players.get(s), packet, 0, packet.length));
                }
                maxQueued = Math.max(maxQueued, pipeline.stats().queuedFrames());
                long next = start + (f + 1) * tickNanos;
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
            }
            AudioPipelineStats stats = ShardedAudioPipelineTest.awaitStats(pipeline,
                    s -> s.framesProcessed() == TOTAL_FRAMES && s.finalUtterancesEmitted() == SPEAKERS);
            String report = String.format(Locale.ROOT,
                    "paced: %d speakers at 50 frames/s for %.1f s with default config | max queued %d | %s",
                    SPEAKERS, SIMULATED_SECONDS, maxQueued, stats);
            System.out.println(report);
            reporter.publishEntry("audio-stress-paced", report);

            assertEquals(0, stats.framesDroppedQueueFull());
            assertEquals(0, stats.framesDroppedPoolExhausted());
            assertEquals(0, stats.processingErrors());
            assertEquals(SPEAKERS, sink.finals.size());
        }
    }

    @Test
    void reportsSteadyStateAllocationPerStage(TestReporter reporter) {
        List<byte[]> packets = speakerPackets.get(0);
        ConcentusOpusAudioDecoder decoder = new ConcentusOpusAudioDecoder();
        FirDecimator3To1 fir = new FirDecimator3To1();
        VoiceActivityDetector vad = new VoiceActivityDetector(AudioConfig.defaults());
        short[] pcm48k = new short[AudioFormat.MAX_FRAME_SAMPLES_48K];
        short[] pcm16k = new short[AudioFormat.MAX_FRAME_SAMPLES_16K];
        for (int round = 0; round < 50; round++) {
            for (byte[] packet : packets) {
                int n = decoder.decode(packet, 0, packet.length, pcm48k);
                vad.process(pcm16k, fir.decimate(pcm48k, n, pcm16k));
            }
        }
        Thread self = Thread.currentThread();
        long before = TestSignals.allocatedBytes(self);
        for (byte[] packet : packets) {
            decoder.decode(packet, 0, packet.length, pcm48k);
        }
        long decodeBytes = TestSignals.allocatedBytes(self) - before;
        short[] silent = new short[FRAME_48K];
        before = TestSignals.allocatedBytes(self);
        for (int i = 0; i < packets.size(); i++) {
            vad.process(pcm16k, fir.decimate(silent, FRAME_48K, pcm16k));
        }
        long dspBytes = TestSignals.allocatedBytes(self) - before;
        String report = String.format(Locale.ROOT,
                "steady-state allocation: Concentus decode %.0f B/frame (library internals) | FIR+VAD on silence %.1f B/frame",
                (double) decodeBytes / packets.size(), (double) dspBytes / packets.size());
        System.out.println(report);
        reporter.publishEntry("audio-stress-allocation", report);
        assertTrue(dspBytes < 1024, report);
    }

    private static void runBurst(AudioConfig config, List<UUID> players) {
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(config, new TestSignals.RecordingSink())) {
            submitInterleaved(pipeline, players);
            ShardedAudioPipelineTest.awaitStats(pipeline, s -> s.framesProcessed() == TOTAL_FRAMES);
        }
    }

    private static void submitInterleaved(ShardedAudioPipeline pipeline, List<UUID> players) {
        for (int f = 0; f < FRAMES_PER_SPEAKER; f++) {
            for (int s = 0; s < SPEAKERS; s++) {
                byte[] packet = speakerPackets.get(s).get(f);
                assertTrue(pipeline.submitOpus(players.get(s), packet, 0, packet.length));
            }
        }
    }

    private static List<UUID> uuids(int count, long seed) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new UUID(seed, i * 0x9E3779B97F4A7C15L));
        }
        return out;
    }

    private static long[] workerAllocations(ShardedAudioPipeline pipeline) {
        long[] out = new long[pipeline.workerCount()];
        for (int i = 0; i < out.length; i++) {
            out[i] = TestSignals.allocatedBytes(pipeline.workerThread(i));
        }
        return out;
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long v : values) {
            total += v;
        }
        return total;
    }
}
