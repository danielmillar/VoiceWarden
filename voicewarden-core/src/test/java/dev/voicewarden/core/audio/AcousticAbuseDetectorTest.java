package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.VoiceAction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AcousticAbuseDetectorTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    private static final int FRAME_MS = 20;
    private static final int FRAME = FRAME_MS * AudioFormat.SAMPLES_PER_MS_16K;
    private static final int RATE = AudioFormat.SAMPLE_RATE_16K;

    private long nowMs = 1_000_000L;

    private static MicSpamConfig config(int cooldownSeconds) {
        MicSpamConfig defaults = MicSpamConfig.defaults();
        return new MicSpamConfig(true, defaults.clippingSampleThreshold(), defaults.clippingRatio(), defaults.rmsThreshold(),
                defaults.minDurationMs(), cooldownSeconds, defaults.escalation(), defaults.windowMinutes(), defaults.muteDuration(), true);
    }

    private List<AcousticAbuseEvent> feed(AcousticAbuseDetector detector, short[] pcm) {
        List<AcousticAbuseEvent> events = new ArrayList<>();
        for (int pos = 0; pos < pcm.length; pos += FRAME) {
            detector.process(pcm, pos, Math.min(FRAME, pcm.length - pos), nowMs).ifPresent(events::add);
            nowMs += FRAME_MS;
        }
        return events;
    }

    private static short[] clippedSine(int ms, double drive) {
        short[] out = new short[ms * AudioFormat.SAMPLES_PER_MS_16K];
        for (int i = 0; i < out.length; i++) {
            double value = drive * Math.sin(2.0 * Math.PI * 180 * i / RATE);
            out[i] = (short) Math.max(-32_768, Math.min(32_767, Math.round(value)));
        }
        return out;
    }

    private static short[] quietNoise(int ms) {
        short[] out = new short[ms * AudioFormat.SAMPLES_PER_MS_16K];
        long seed = 7;
        for (int i = 0; i < out.length; i++) {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            out[i] = (short) ((seed >>> 52) - 2048);
        }
        return out;
    }

    private static short[] shouting(int ms, double peak, int syllableMs, int gapMs) {
        short[] out = new short[ms * AudioFormat.SAMPLES_PER_MS_16K];
        int period = (syllableMs + gapMs) * AudioFormat.SAMPLES_PER_MS_16K;
        int voiced = syllableMs * AudioFormat.SAMPLES_PER_MS_16K;
        for (int i = 0; i < out.length; i++) {
            int phase = i % period;
            if (phase >= voiced) {
                continue;
            }
            double envelope = Math.sin(Math.PI * phase / voiced);
            double t = (double) i / RATE;
            double voice = 0.6 * Math.sin(2 * Math.PI * 210 * t) + 0.3 * Math.sin(2 * Math.PI * 420 * t)
                    + 0.2 * Math.sin(2 * Math.PI * 630 * t) + 0.1 * Math.sin(2 * Math.PI * 1260 * t);
            double value = peak * envelope * voice / 1.2;
            out[i] = (short) Math.max(-32_768, Math.min(32_767, Math.round(value)));
        }
        return out;
    }

    @Test
    void normalSpeechAndExcitedShoutingDoNotTrigger() {
        AcousticAbuseDetector detector = new AcousticAbuseDetector(PLAYER, config(0));

        assertTrue(feed(detector, TestSignals.sine(250, RATE, 5 * RATE, 6_000, 0)).isEmpty());
        assertTrue(feed(detector, shouting(6_000, 30_000, 220, 80)).isEmpty());
        assertTrue(feed(detector, shouting(6_000, 40_000, 180, 120)).isEmpty(), "peaks that clip between syllables are still speech");
        assertTrue(feed(detector, TestSignals.sine(200, RATE, 4 * RATE, 20_000, 0)).isEmpty(), "a held loud vowel stays below the RMS limit");
    }

    @Test
    void briefSpikesInsideOngoingAudioDoNotTrigger() {
        AcousticAbuseDetector detector = new AcousticAbuseDetector(PLAYER, config(0));

        List<AcousticAbuseEvent> events = new ArrayList<>();
        events.addAll(feed(detector, quietNoise(1_000)));
        events.addAll(feed(detector, clippedSine(60, 80_000)));
        events.addAll(feed(detector, quietNoise(1_000)));
        events.addAll(feed(detector, clippedSine(300, 80_000)));
        events.addAll(feed(detector, quietNoise(1_000)));
        events.addAll(feed(detector, clippedSine(500, 80_000)));
        events.addAll(feed(detector, quietNoise(200)));
        events.addAll(feed(detector, clippedSine(500, 80_000)));
        events.addAll(feed(detector, quietNoise(1_000)));

        assertTrue(events.isEmpty(), "pops and bursts shorter than min_duration_ms are not mic spam: " + events);
    }

    @Test
    void sustainedClippingTriggersOnceWithTheCapturedClip() {
        AcousticAbuseDetector detector = new AcousticAbuseDetector(PLAYER, config(15));

        List<AcousticAbuseEvent> events = new ArrayList<>();
        events.addAll(feed(detector, quietNoise(2_000)));
        events.addAll(feed(detector, clippedSine(3_000, 90_000)));

        assertEquals(1, events.size());
        AcousticAbuseEvent event = events.getFirst();
        assertEquals(PLAYER, event.playerId());
        assertEquals(AcousticAbuseEvent.Kind.CLIPPING, event.kind());
        assertEquals(MicSpamConfig.DEFAULT_MIN_DURATION_MS, event.durationMs());
        assertTrue(event.clippingRatio() >= 0.5, "clipping ratio " + event.clippingRatio());
        assertTrue(event.peakRms() > 28_000, "peak RMS " + event.peakRms());
        int captureSamples = (MicSpamConfig.DEFAULT_MIN_DURATION_MS + AcousticAbuseDetector.CAPTURE_LEAD_MS) * AudioFormat.SAMPLES_PER_MS_16K;
        assertEquals(captureSamples, event.pcm16k().length);
    }

    @Test
    void sustainedEarrapeWithoutClippingTriggersOnLoudness() {
        AcousticAbuseDetector detector = new AcousticAbuseDetector(PLAYER, config(15));

        List<AcousticAbuseEvent> events = feed(detector, TestSignals.sine(300, RATE, 2 * RATE, 26_000, 0));

        assertEquals(1, events.size());
        AcousticAbuseEvent event = events.getFirst();
        assertEquals(AcousticAbuseEvent.Kind.LOUDNESS, event.kind());
        assertEquals(0.0, event.clippingRatio());
        assertTrue(event.peakRms() >= MicSpamConfig.DEFAULT_RMS_THRESHOLD, "peak RMS " + event.peakRms());
    }

    @Test
    void fewCleanBlocksInsideSustainedSpamStillTrigger() {
        AcousticAbuseDetector detector = new AcousticAbuseDetector(PLAYER, config(15));
        short[] stuttered = new short[0];
        for (int i = 0; i < 10; i++) {
            stuttered = TestSignals.concat(stuttered, clippedSine(180, 90_000), new short[20 * AudioFormat.SAMPLES_PER_MS_16K]);
        }

        assertEquals(1, feed(detector, stuttered).size());
    }

    @Test
    void cooldownLetsOneBurstTriggerOnceAndContinuedSpamTriggerAgainAfterIt() {
        AcousticAbuseDetector detector = new AcousticAbuseDetector(PLAYER, config(15));
        List<Long> triggeredAt = new ArrayList<>();
        short[] spam = clippedSine(20_000, 90_000);
        for (int pos = 0; pos < spam.length; pos += FRAME) {
            if (detector.process(spam, pos, FRAME, nowMs).isPresent()) {
                triggeredAt.add(nowMs);
            }
            nowMs += FRAME_MS;
        }

        assertEquals(2, triggeredAt.size(), "triggers at " + triggeredAt);
        assertTrue(triggeredAt.get(1) - triggeredAt.get(0) >= 15_000);
        assertTrue(triggeredAt.get(1) - triggeredAt.get(0) <= 15_000 + MicSpamConfig.DEFAULT_MIN_DURATION_MS);
    }

    @Test
    void zeroCooldownStillNeedsAFreshFullWindowBetweenTriggers() {
        AcousticAbuseDetector detector = new AcousticAbuseDetector(PLAYER, config(0));

        assertEquals(5, feed(detector, clippingWindowMultiple(5)).size());
    }

    private static short[] clippingWindowMultiple(int windows) {
        return clippedSine(windows * MicSpamConfig.DEFAULT_MIN_DURATION_MS, 90_000);
    }

    @Test
    void packetGapStartsAFreshWindowSoSeparateBurstsAreNotStitchedTogether() {
        AcousticAbuseDetector detector = new AcousticAbuseDetector(PLAYER, config(0));

        assertTrue(feed(detector, clippedSine(600, 90_000)).isEmpty());
        nowMs += 2_000;
        assertTrue(feed(detector, clippedSine(600, 90_000)).isEmpty());
        detector.reset();
        assertTrue(feed(detector, clippedSine(600, 90_000)).isEmpty());
    }

    @Test
    void reconfigureKeepsTheCooldownAndDisabledDetectsNothing() {
        AcousticAbuseDetector detector = new AcousticAbuseDetector(PLAYER, config(15));
        assertEquals(1, feed(detector, clippedSine(1_000, 90_000)).size());

        detector.reconfigure(config(15));
        assertTrue(feed(detector, clippedSine(2_000, 90_000)).isEmpty(), "a reload must not reset the cooldown");

        MicSpamConfig defaults = MicSpamConfig.defaults();
        detector.reconfigure(new MicSpamConfig(false, defaults.clippingSampleThreshold(), defaults.clippingRatio(),
                defaults.rmsThreshold(), defaults.minDurationMs(), 0, defaults.escalation(), defaults.windowMinutes(),
                defaults.muteDuration(), true));
        nowMs += 60_000;
        assertTrue(feed(detector, clippedSine(3_000, 90_000)).isEmpty());
    }

    @Test
    void sessionReportsSustainedClippingThroughTheFortyEightKilohertzPath() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        PlayerAudioSession session = new PlayerAudioSession(PLAYER, AudioConfig.defaults(), new TestSignals.PassThroughDecoder(), sink,
                new AtomicLong()::incrementAndGet, new PipelineCounters());
        short[] square = squareWave48k(3_000);
        PooledOpusFrame frame = new PooledOpusFrame(AudioConfig.DEFAULT_MAX_OPUS_FRAME_BYTES);
        long at = 5_000;
        for (int pos = 0; pos < square.length; pos += AudioFormat.OPUS_FRAME_20MS_48K) {
            frame.fillPcm48k(PLAYER, square, pos, AudioFormat.OPUS_FRAME_20MS_48K, at);
            session.onFrame(frame);
            at += FRAME_MS;
        }

        assertEquals(1, sink.abuse.size());
        assertEquals(AcousticAbuseEvent.Kind.CLIPPING, sink.abuse.getFirst().kind());
    }

    @Test
    void sessionReportsEarrapeThatWentThroughTheOpusCodec() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        PlayerAudioSession session = new PlayerAudioSession(PLAYER, AudioConfig.defaults(), new ConcentusOpusAudioDecoder(), sink,
                new AtomicLong()::incrementAndGet, new PipelineCounters());
        PooledOpusFrame frame = new PooledOpusFrame(AudioConfig.DEFAULT_MAX_OPUS_FRAME_BYTES);
        long at = 5_000;
        for (byte[] packet : TestSignals.encodeOpus(squareWave48k(3_000))) {
            frame.fillOpus(PLAYER, packet, 0, packet.length, at);
            session.onFrame(frame);
            at += FRAME_MS;
        }

        assertEquals(1, sink.abuse.size(), "a full-scale square wave must survive Opus and the decimator");
        AcousticAbuseEvent event = sink.abuse.getFirst();
        assertEquals(AcousticAbuseEvent.Kind.CLIPPING, event.kind(), event.toString());
        assertTrue(event.peakRms() >= MicSpamConfig.DEFAULT_RMS_THRESHOLD, event.toString());
    }

    @Test
    void sessionReportsAnOverdrivenScreamThatWentThroughTheOpusCodecAsClipping() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        PlayerAudioSession session = new PlayerAudioSession(PLAYER, AudioConfig.defaults(), new ConcentusOpusAudioDecoder(), sink,
                new AtomicLong()::incrementAndGet, new PipelineCounters());
        short[] scream = new short[3_000 * AudioFormat.SAMPLES_PER_MS_48K];
        for (int i = 0; i < scream.length; i++) {
            double t = (double) i / AudioFormat.SAMPLE_RATE_48K;
            double harmonics = 0;
            for (int h = 1; h <= 10; h++) {
                harmonics += Math.sin(2 * Math.PI * 350 * h * t) / h;
            }
            scream[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(1.5 * 32_767 * harmonics)));
        }
        PooledOpusFrame frame = new PooledOpusFrame(AudioConfig.DEFAULT_MAX_OPUS_FRAME_BYTES);
        long at = 5_000;
        for (byte[] packet : TestSignals.encodeOpus(scream)) {
            frame.fillOpus(PLAYER, packet, 0, packet.length, at);
            session.onFrame(frame);
            at += FRAME_MS;
        }

        assertEquals(1, sink.abuse.size());
        AcousticAbuseEvent event = sink.abuse.getFirst();
        assertEquals(AcousticAbuseEvent.Kind.CLIPPING, event.kind(), event.toString());
        assertTrue(event.clippingRatio() >= MicSpamConfig.DEFAULT_CLIPPING_RATIO, event.toString());
    }

    @Test
    void sessionIgnoresLoudSpeechThatWentThroughTheOpusCodec() {
        TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
        PlayerAudioSession session = new PlayerAudioSession(PLAYER, AudioConfig.defaults(), new ConcentusOpusAudioDecoder(), sink,
                new AtomicLong()::incrementAndGet, new PipelineCounters());
        short[] speech16k = shouting(4_000, 34_000, 220, 80);
        short[] speech48k = new short[speech16k.length * 3];
        for (int i = 0; i < speech48k.length; i++) {
            speech48k[i] = speech16k[i / 3];
        }
        PooledOpusFrame frame = new PooledOpusFrame(AudioConfig.DEFAULT_MAX_OPUS_FRAME_BYTES);
        long at = 5_000;
        for (byte[] packet : TestSignals.encodeOpus(speech48k)) {
            frame.fillOpus(PLAYER, packet, 0, packet.length, at);
            session.onFrame(frame);
            at += FRAME_MS;
        }

        assertTrue(sink.abuse.isEmpty());
        assertTrue(sink.finals.size() + sink.chunks.size() > 0, "the shouting is still passed on as speech");
    }

    private static short[] squareWave48k(int ms) {
        short[] out = new short[ms * AudioFormat.SAMPLES_PER_MS_48K];
        int halfPeriod = AudioFormat.SAMPLE_RATE_48K / 150 / 2;
        for (int i = 0; i < out.length; i++) {
            out[i] = (i / halfPeriod) % 2 == 0 ? Short.MAX_VALUE : Short.MIN_VALUE;
        }
        return out;
    }
}
