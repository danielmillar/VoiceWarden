package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.core.discord.DiscordMessages;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RollingOpusBufferTest {

    private static final Duration RETENTION = Duration.ofSeconds(45);
    private static final long NOW = 1_000_000L;
    private static final int FRAME = AudioFormat.OPUS_FRAME_20MS_48K;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void framesOlderThanTheRetentionWindowExpire() {
        RollingOpusBuffer buffer = new RollingOpusBuffer(RETENTION);
        buffer.append(alice, new byte[] {1}, 0, 1, NOW - 50_000);
        buffer.append(alice, new byte[] {2}, 0, 1, NOW - 10_000);
        buffer.append(alice, new byte[] {3}, 0, 1, NOW);

        List<RollingOpusBuffer.OpusFrame> recent = buffer.snapshot(alice, RETENTION, NOW);
        assertEquals(List.of(NOW - 10_000, NOW), recent.stream().map(RollingOpusBuffer.OpusFrame::timestampMs).toList());
        assertEquals(1, buffer.snapshot(alice, Duration.ofSeconds(5), NOW).size());

        buffer.prune(NOW + 44_000);
        assertEquals(1, buffer.snapshot(alice, RETENTION, NOW + 44_000).size());
        buffer.prune(NOW + 46_000);
        assertEquals(0, buffer.playerCount());
        assertFalse(buffer.hasAudio(alice, RETENTION, NOW + 46_000));
    }

    @Test
    void windowNeverReachesPastTheRetention() {
        RollingOpusBuffer buffer = new RollingOpusBuffer(Duration.ofSeconds(10));
        buffer.append(alice, new byte[] {1}, 0, 1, NOW - 9_000);
        buffer.append(alice, new byte[] {2}, 0, 1, NOW);

        assertEquals(1, buffer.snapshot(alice, Duration.ofMinutes(5), NOW + 2_000).size());
        assertFalse(buffer.hasAudio(alice, Duration.ofMinutes(5), NOW + 11_000));
    }

    @Test
    void perPlayerByteCapDropsTheOldestFramesFirst() {
        int cap = 10 * (100 + RollingOpusBuffer.FRAME_OVERHEAD_BYTES);
        RollingOpusBuffer buffer = new RollingOpusBuffer(RETENTION, cap);
        for (int i = 0; i < 50; i++) {
            byte[] packet = new byte[100];
            packet[0] = (byte) i;
            buffer.append(alice, packet, 0, packet.length, NOW + i * 20L);
        }
        buffer.append(bob, new byte[] {9}, 0, 1, NOW);

        List<RollingOpusBuffer.OpusFrame> kept = buffer.snapshot(alice, RETENTION, NOW + 1_000);
        assertEquals(10, kept.size());
        assertEquals(40, kept.get(0).data()[0]);
        assertEquals(49, kept.get(9).data()[0]);
        assertTrue(buffer.bufferedBytes(alice) <= cap);
        assertEquals(1, buffer.snapshot(bob, RETENTION, NOW).size(), "one player's flood must not evict another's audio");
    }

    @Test
    void tinyPacketFloodsAreBoundedByPerFrameOverhead() {
        RollingOpusBuffer buffer = new RollingOpusBuffer(RETENTION);
        for (int i = 0; i < 100_000; i++) {
            buffer.append(alice, new byte[] {1}, 0, 1, NOW);
        }
        assertTrue(buffer.bufferedBytes(alice) <= RollingOpusBuffer.DEFAULT_MAX_BYTES_PER_PLAYER);
        assertTrue(buffer.snapshot(alice, RETENTION, NOW).size()
                <= RollingOpusBuffer.DEFAULT_MAX_BYTES_PER_PLAYER / (1 + RollingOpusBuffer.FRAME_OVERHEAD_BYTES));
    }

    @Test
    void clearForgetsOnlyThatPlayer() {
        RollingOpusBuffer buffer = new RollingOpusBuffer(RETENTION);
        buffer.append(alice, new byte[] {1}, 0, 1, NOW);
        buffer.append(bob, new byte[] {2}, 0, 1, NOW);

        buffer.clear(alice);

        assertTrue(buffer.snapshot(alice, RETENTION, NOW).isEmpty());
        assertEquals(0, buffer.decodeToPcm16k(alice, RETENTION, NOW, TestSignals.PassThroughDecoder::new).length);
        assertEquals(1, buffer.snapshot(bob, RETENTION, NOW).size());
        buffer.clearAll();
        assertEquals(0, buffer.playerCount());
    }

    @Test
    void zeroRetentionDisablesCaptureAndClearsWhatWasHeld() {
        RollingOpusBuffer off = new RollingOpusBuffer(Duration.ZERO);
        assertFalse(off.enabled());
        assertFalse(off.append(alice, new byte[] {1}, 0, 1, NOW));
        assertEquals(0, off.playerCount());

        RollingOpusBuffer buffer = new RollingOpusBuffer(RETENTION);
        buffer.append(alice, new byte[] {1}, 0, 1, NOW);
        buffer.updateRetention(Duration.ZERO);
        assertEquals(0, buffer.playerCount());
        assertFalse(buffer.append(alice, new byte[] {1}, 0, 1, NOW));
        buffer.updateRetention(Duration.ofMinutes(10));
        assertEquals(Duration.ofSeconds(RollingOpusBuffer.MAX_RETENTION_SECONDS), buffer.retention());
    }

    @Test
    void appendCopiesExactlyTheRequestedSliceAndRejectsBadInput() {
        RollingOpusBuffer buffer = new RollingOpusBuffer(RETENTION);
        byte[] source = {9, 8, 7, 6, 5};
        assertTrue(buffer.append(alice, source, 1, 3, NOW));
        source[2] = 0;

        RollingOpusBuffer.OpusFrame frame = buffer.snapshot(alice, RETENTION, NOW).get(0);
        assertArrayEquals(new byte[] {8, 7, 6}, frame.data());
        frame.data()[0] = 0;
        assertArrayEquals(new byte[] {8, 7, 6}, buffer.snapshot(alice, RETENTION, NOW).get(0).data());

        assertFalse(buffer.append(alice, new byte[RollingOpusBuffer.MAX_PACKET_BYTES + 1], 0,
                RollingOpusBuffer.MAX_PACKET_BYTES + 1, NOW));
        assertFalse(buffer.append(alice, source, 4, 3, NOW));
        assertFalse(buffer.append(alice, source, -1, 2, NOW));
        assertFalse(buffer.append(alice, source, 0, 0, NOW));
        assertFalse(buffer.append(null, source, 0, 1, NOW));
        assertFalse(buffer.append(alice, null, 0, 1, NOW));
        assertEquals(1, buffer.snapshot(alice, RETENTION, NOW).size());
    }

    @Test
    void decodesBufferedOpusIntoA16kClipThatKeepsTheTone() {
        short[] input = TestSignals.sine(1000, AudioFormat.SAMPLE_RATE_48K, FRAME * 100, 10_000, 0);
        List<byte[]> packets = TestSignals.encodeOpus(input);
        RollingOpusBuffer buffer = new RollingOpusBuffer(RETENTION);
        long start = NOW - 3_000;
        for (int i = 0; i < packets.size(); i++) {
            byte[] packet = packets.get(i);
            buffer.append(alice, packet, 0, packet.length, start + i * 20L + (i % 3));
        }
        assertTrue(buffer.bufferedBytes(alice) < 20_000, "two seconds of Opus should stay small");

        short[] clip = buffer.decodeToPcm16k(alice, RETENTION, NOW, ConcentusOpusAudioDecoder::new);

        assertEquals(input.length / AudioFormat.DECIMATION_FACTOR, clip.length);
        int from = clip.length / 5;
        double rms = TestSignals.rms(clip, from, clip.length);
        assertEquals(10_000 / Math.sqrt(2), rms, 10_000 / Math.sqrt(2) * 0.2, "decoded RMS");
        double tone = TestSignals.goertzelPower(clip, from, clip.length, 1000, AudioFormat.SAMPLE_RATE_16K);
        assertTrue(tone > 0.9 * rms * rms, "1 kHz should dominate the decoded clip");
        byte[] wav = WavEncoder.encodePcm16Mono(clip, DiscordMessages.AUDIO_SAMPLE_RATE);
        assertArrayEquals(clip, WavEncoder.decodePcm16Mono(wav));
    }

    @Test
    void silenceBetweenBurstsIsShortenedAndTheDecoderIsReset() {
        RollingOpusBuffer buffer = new RollingOpusBuffer(RETENTION);
        byte[] loud = pcmPacket((short) 3000);
        for (int i = 0; i < 5; i++) {
            buffer.append(alice, loud, 0, loud.length, NOW - 10_000 + i * 20L);
        }
        buffer.append(alice, new byte[] {(byte) 0xFF}, 0, 1, NOW - 10_000 + 100);
        for (int i = 0; i < 5; i++) {
            buffer.append(alice, loud, 0, loud.length, NOW - 2_000 + i * 20L);
        }
        TestSignals.PassThroughDecoder decoder = new TestSignals.PassThroughDecoder();

        short[] clip = buffer.decodeToPcm16k(alice, RETENTION, NOW, () -> decoder);

        int perFrame = FRAME / AudioFormat.DECIMATION_FACTOR;
        int silence = (int) (RollingOpusBuffer.MAX_GAP_SILENCE_MS * AudioFormat.SAMPLES_PER_MS_16K);
        assertEquals(11 * perFrame + silence, clip.length);
        assertEquals(1, decoder.resets);
        assertEquals(0, clip[6 * perFrame + silence / 2]);
    }

    @Test
    void clipIsCappedToFitADiscordAttachmentKeepingTheNewestAudio() {
        assertTrue(WavEncoder.HEADER_BYTES + 2L * RollingOpusBuffer.MAX_CLIP_SAMPLES <= DiscordMessages.MAX_ATTACHMENT_BYTES);
        RollingOpusBuffer buffer = new RollingOpusBuffer(Duration.ofSeconds(RollingOpusBuffer.MAX_RETENTION_SECONDS));
        int frames = RollingOpusBuffer.MAX_CLIP_SAMPLES / (AudioFormat.MAX_FRAME_SAMPLES_16K) + 40;
        for (int i = 0; i < frames; i++) {
            buffer.append(alice, new byte[] {(byte) (i < 10 ? 70 : 2)}, 0, 1, NOW);
        }

        short[] clip = buffer.decodeToPcm16k(alice, RETENTION, NOW, MaxFrameDecoder::new);

        assertEquals(RollingOpusBuffer.MAX_CLIP_SAMPLES, clip.length);
        assertTrue(WavEncoder.encodePcm16Mono(clip, DiscordMessages.AUDIO_SAMPLE_RATE).length <= DiscordMessages.MAX_ATTACHMENT_BYTES);
        int loudest = 0;
        for (short sample : clip) {
            loudest = Math.max(loudest, Math.abs(sample));
        }
        assertTrue(loudest <= 3, "the oldest frames should be the ones dropped, loudest=" + loudest);
    }

    @Test
    void concurrentAppendsPrunesAndClearsStayWithinTheByteCap() throws Exception {
        RollingOpusBuffer buffer = new RollingOpusBuffer(Duration.ofSeconds(1), 64 * 1024);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int t = 0; t < 3; t++) {
                int thread = t;
                tasks.add(pool.submit(() -> {
                    go.await();
                    byte[] packet = new byte[60];
                    for (int i = 0; i < 20_000; i++) {
                        buffer.append(i % 2 == 0 ? alice : bob, packet, 0, packet.length, NOW + i + thread);
                    }
                    return null;
                }));
            }
            tasks.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 2_000; i++) {
                    buffer.prune(NOW + i * 10L);
                    if (i % 100 == 0) {
                        buffer.clear(bob);
                    }
                }
                return null;
            }));
            go.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID player : List.of(alice, bob)) {
            long expected = buffer.snapshot(player, Duration.ofSeconds(1), NOW + 20_003).stream()
                    .mapToLong(frame -> frame.data().length + RollingOpusBuffer.FRAME_OVERHEAD_BYTES)
                    .sum();
            assertTrue(buffer.bufferedBytes(player) <= 64 * 1024);
            assertTrue(expected <= buffer.bufferedBytes(player));
        }
    }

    private static byte[] pcmPacket(short value) {
        byte[] packet = new byte[FRAME * 2];
        for (int i = 0; i < FRAME; i++) {
            packet[2 * i] = (byte) value;
            packet[2 * i + 1] = (byte) (value >> 8);
        }
        return packet;
    }

    private static final class MaxFrameDecoder implements OpusAudioDecoder {

        @Override
        public int decode(byte[] packet, int offset, int length, short[] out48k) {
            Arrays.fill(out48k, 0, AudioFormat.MAX_FRAME_SAMPLES_48K, packet[offset]);
            return AudioFormat.MAX_FRAME_SAMPLES_48K;
        }

        @Override
        public int conceal(short[] out48k) {
            return 0;
        }

        @Override
        public void reset() {
        }
    }
}
