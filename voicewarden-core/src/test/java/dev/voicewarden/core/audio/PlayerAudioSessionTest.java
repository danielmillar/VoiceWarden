package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PlayerAudioSessionTest {

    private static final int FRAME = 320;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final TestSignals.RecordingSink sink = new TestSignals.RecordingSink();
    private final PipelineCounters counters = new PipelineCounters();

    private PlayerAudioSession session(AudioConfig config) {
        return new PlayerAudioSession(PLAYER, config, new TestSignals.PassThroughDecoder(), sink,
                new AtomicLong()::incrementAndGet, counters);
    }

    private static short[] speech(int ms) {
        return TestSignals.sine(250, 16_000, ms * 16, 6_000, 0);
    }

    private static short[] silence(int ms) {
        return new short[ms * 16];
    }

    private static void feed(PlayerAudioSession session, short[] pcm16k) {
        for (int pos = 0; pos < pcm16k.length; pos += FRAME) {
            session.processPcm16k(pcm16k, pos, Math.min(FRAME, pcm16k.length - pos));
        }
    }

    @Test
    void emitsLiveChunksEveryTwoHundredMillisecondsAndAFinalUtterance() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        feed(session, silence(500));
        feed(session, speech(1000));
        feed(session, silence(1000));

        assertEquals(1, sink.finals.size());
        FinalUtterance utterance = sink.finals.get(0);
        int postRoll = PlayerAudioSession.POST_ROLL_MS * AudioFormat.SAMPLES_PER_MS_16K;
        assertEquals(3200 + 16_000 + postRoll, utterance.pcm16k().length);
        assertEquals(1200 + PlayerAudioSession.POST_ROLL_MS, utterance.durationMs());
        assertArrayEquals(speech(1000), Arrays.copyOfRange(utterance.pcm16k(), 3200, 3200 + 16_000));

        assertTrue(sink.chunks.size() >= 6);
        for (int i = 0; i < sink.chunks.size(); i++) {
            LiveAudioChunk chunk = sink.chunks.get(i);
            assertEquals(i, chunk.chunkIndex());
            assertEquals(utterance.utteranceId(), chunk.utteranceId());
            boolean last = i == sink.chunks.size() - 1;
            assertEquals(last, chunk.endOfUtterance());
            if (!last) {
                assertEquals(3200, chunk.pcm16k().length);
                assertEquals(200, chunk.durationMs());
            }
        }
        short[] joined = new short[0];
        for (LiveAudioChunk chunk : sink.chunks) {
            joined = TestSignals.concat(joined, chunk.pcm16k());
        }
        assertArrayEquals(utterance.pcm16k(), Arrays.copyOf(joined, utterance.pcm16k().length));
    }

    @Test
    void liveDetectionDisabledEmitsOnlyFinal() {
        PlayerAudioSession session = session(AudioConfig.builder().liveDetection(false).build());
        feed(session, speech(1000));
        feed(session, silence(600));
        assertEquals(0, sink.chunks.size());
        assertEquals(1, sink.finals.size());
    }

    @Test
    void discardsUtterancesShorterThanMinimumSpeech() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        feed(session, silence(400));
        feed(session, speech(120));
        feed(session, silence(600));
        assertEquals(0, sink.finals.size());
        assertEquals(1, counters.utterancesDiscardedTooShort.sum());
        assertTrue(sink.chunks.get(sink.chunks.size() - 1).endOfUtterance());

        feed(session, speech(300));
        feed(session, silence(600));
        assertEquals(1, sink.finals.size());
    }

    @Test
    void finalUtterancePreservesQuietTrailingPostRoll() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        short[] quietTail = TestSignals.sine(250, 16_000, PlayerAudioSession.POST_ROLL_MS * 16, 100, 0);
        feed(session, speech(300));
        feed(session, quietTail);
        feed(session, silence(600));

        assertEquals(1, sink.finals.size());
        short[] pcm = sink.finals.get(0).pcm16k();
        assertEquals(300 * 16 + quietTail.length, pcm.length);
        assertArrayEquals(quietTail, Arrays.copyOfRange(pcm, 300 * 16, pcm.length));
    }

    @Test
    void capsUtteranceAtMaxSecondsAndStartsAFreshOne() {
        PlayerAudioSession session = session(AudioConfig.builder().maxUtteranceSeconds(5).build());
        feed(session, silence(300));
        feed(session, speech(12_000));
        feed(session, silence(600));

        assertEquals(3, sink.finals.size());
        assertEquals(80_000, sink.finals.get(0).pcm16k().length);
        assertEquals(80_000, sink.finals.get(1).pcm16k().length);
        assertEquals(5000, sink.finals.get(0).durationMs());
        int postRoll = PlayerAudioSession.POST_ROLL_MS * AudioFormat.SAMPLES_PER_MS_16K;
        int total = sink.finals.stream().mapToInt(f -> f.pcm16k().length).sum();
        assertEquals(3200 + 12_000 * 16 + postRoll, total);
        assertEquals(3, sink.finals.stream().mapToLong(FinalUtterance::utteranceId).distinct().count());
    }

    @Test
    void droppedUtteranceStopsEmittingUntilSilenceEvenAcrossTheCap() {
        PlayerAudioSession session = session(AudioConfig.builder().maxUtteranceSeconds(5).build());
        feed(session, speech(400));
        long dropped = sink.chunks.get(0).utteranceId();
        int chunksBeforeDrop = sink.chunks.size();
        session.dropUtterance(dropped);
        assertEquals(dropped, session.droppedUtteranceId());

        feed(session, speech(7_000));
        assertEquals(chunksBeforeDrop, sink.chunks.size());
        assertEquals(0, sink.finals.size());
        assertEquals(1, counters.utterancesDropped.sum());

        feed(session, silence(600));
        feed(session, speech(800));
        feed(session, silence(600));
        assertEquals(1, sink.finals.size());
        assertNotEquals(dropped, sink.finals.get(0).utteranceId());
        assertTrue(sink.chunks.size() > chunksBeforeDrop);
    }

    @Test
    void dropForAnOldUtteranceIsIgnored() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        feed(session, speech(800));
        feed(session, silence(600));
        long first = sink.finals.get(0).utteranceId();
        feed(session, speech(800));
        session.dropUtterance(first);
        feed(session, silence(600));
        assertEquals(2, sink.finals.size());
    }

    @Test
    void speakingStoppedFinalizesImmediately() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        feed(session, speech(800));
        assertTrue(session.utteranceOpen());
        session.speakingStopped();
        assertFalse(session.utteranceOpen());
        assertEquals(1, sink.finals.size());
    }

    @Test
    void idleCheckClosesUtteranceWhenPacketsStop() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        PooledOpusFrame frame = new PooledOpusFrame(64);
        frame.fillPcm48k(PLAYER, TestSignals.sine(250, 48_000, 960, 6_000, 0), 0, 960, 1_000L);
        for (int i = 0; i < 40; i++) {
            session.onFrame(frame);
        }
        assertTrue(session.utteranceOpen());
        assertFalse(session.checkIdle(1_000L + 499));
        assertTrue(session.checkIdle(1_000L + 500));
        assertEquals(1, sink.finals.size());
        assertFalse(session.checkIdle(5_000L));
    }

    @Test
    void sinkReceivesIndependentCopies() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        feed(session, speech(1000));
        for (LiveAudioChunk chunk : sink.chunks) {
            Arrays.fill(chunk.pcm16k(), (short) 0);
        }
        feed(session, silence(600));
        assertArrayEquals(speech(1000), Arrays.copyOf(sink.finals.get(0).pcm16k(), 16_000));
    }

    @Test
    void corruptPacketIsConcealedAndCounted() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        session.processOpus(new byte[] {(byte) 0xFF}, 0, 1);
        assertEquals(1, counters.decodeErrors.sum());
    }

    @Test
    void configUpdateFinalizesAndAppliesNewSettings() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        feed(session, speech(800));
        session.updateConfig(AudioConfig.builder().liveDetection(false).build());
        assertEquals(1, sink.finals.size());
        int chunks = sink.chunks.size();
        feed(session, speech(800));
        feed(session, silence(600));
        assertEquals(chunks, sink.chunks.size());
        assertEquals(2, sink.finals.size());
    }

    @Test
    void silentFramesDoNotAllocateThroughDecimatorAndVad() {
        PlayerAudioSession session = session(AudioConfig.defaults());
        short[] quiet = new short[AudioFormat.OPUS_FRAME_20MS_48K];
        for (int i = 0; i < 20_000; i++) {
            session.processPcm48k(quiet, 0, quiet.length);
        }
        Thread self = Thread.currentThread();
        long before = TestSignals.allocatedBytes(self);
        for (int i = 0; i < 10_000; i++) {
            session.processPcm48k(quiet, 0, quiet.length);
        }
        long allocated = TestSignals.allocatedBytes(self) - before;
        assertTrue(allocated < 1024, "session allocated " + allocated + " bytes over 10,000 silent frames");
    }
}
