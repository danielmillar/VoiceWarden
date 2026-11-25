package dev.voicewarden.core.audio;

import dev.voicewarden.core.discord.DiscordMessages;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class RollingOpusBuffer {

    public static final int MAX_RETENTION_SECONDS = 120;
    public static final int DEFAULT_MAX_BYTES_PER_PLAYER = 512 * 1024;
    public static final int MAX_PACKET_BYTES = 4000;
    public static final int FRAME_OVERHEAD_BYTES = 48;
    public static final int MAX_CLIP_SAMPLES = (DiscordMessages.MAX_ATTACHMENT_BYTES - WavEncoder.HEADER_BYTES) / 2;
    static final long GAP_THRESHOLD_MS = 200L;
    static final long MAX_GAP_SILENCE_MS = 1_000L;

    public record OpusFrame(long timestampMs, byte[] data) {

        public OpusFrame {
            Objects.requireNonNull(data, "data");
        }
    }

    private final Map<UUID, PlayerFrames> buffers = new ConcurrentHashMap<>();
    private final int maxBytesPerPlayer;
    private volatile long retentionMs;

    public RollingOpusBuffer(Duration retention) {
        this(retention, DEFAULT_MAX_BYTES_PER_PLAYER);
    }

    public RollingOpusBuffer(Duration retention, int maxBytesPerPlayer) {
        if (maxBytesPerPlayer < FRAME_OVERHEAD_BYTES + 1) {
            throw new IllegalArgumentException("maxBytesPerPlayer must hold at least one frame, got " + maxBytesPerPlayer);
        }
        this.maxBytesPerPlayer = maxBytesPerPlayer;
        this.retentionMs = retentionMillis(retention);
    }

    public Duration retention() {
        return Duration.ofMillis(retentionMs);
    }

    public boolean enabled() {
        return retentionMs > 0;
    }

    public void updateRetention(Duration retention) {
        retentionMs = retentionMillis(retention);
        if (retentionMs == 0) {
            clearAll();
        }
    }

    public boolean append(UUID playerId, byte[] opus, int offset, int length, long timestampMs) {
        long retention = retentionMs;
        if (retention == 0 || playerId == null || opus == null || length <= 0 || length > MAX_PACKET_BYTES
                || offset < 0 || offset > opus.length - length) {
            return false;
        }
        byte[] copy = Arrays.copyOfRange(opus, offset, offset + length);
        buffers.compute(playerId, (id, frames) -> {
            PlayerFrames target = frames == null ? new PlayerFrames() : frames;
            target.add(new OpusFrame(timestampMs, copy), timestampMs - retention, maxBytesPerPlayer);
            return target;
        });
        return true;
    }

    public boolean hasAudio(UUID playerId, Duration window, long nowMs) {
        PlayerFrames frames = buffers.get(playerId);
        return frames != null && frames.anyWithin(windowStart(window, nowMs), nowMs);
    }

    public List<OpusFrame> snapshot(UUID playerId, Duration window, long nowMs) {
        PlayerFrames frames = buffers.get(playerId);
        if (frames == null) {
            return List.of();
        }
        List<OpusFrame> shared = frames.within(windowStart(window, nowMs), nowMs);
        List<OpusFrame> copies = new ArrayList<>(shared.size());
        for (OpusFrame frame : shared) {
            copies.add(new OpusFrame(frame.timestampMs(), frame.data().clone()));
        }
        return copies;
    }

    public short[] decodeToPcm16k(UUID playerId, Duration window, long nowMs, Supplier<OpusAudioDecoder> decoders) {
        Objects.requireNonNull(decoders, "decoders");
        PlayerFrames frames = buffers.get(playerId);
        if (frames == null) {
            return new short[0];
        }
        List<OpusFrame> recent = frames.within(windowStart(window, nowMs), nowMs);
        if (recent.isEmpty()) {
            return new short[0];
        }
        try (OpusAudioDecoder decoder = decoders.get()) {
            return decode(recent, decoder);
        }
    }

    public void prune(long nowMs) {
        long cutoff = nowMs - retentionMs;
        for (UUID playerId : buffers.keySet()) {
            buffers.computeIfPresent(playerId, (id, frames) -> frames.dropBefore(cutoff) ? null : frames);
        }
    }

    public void clear(UUID playerId) {
        if (playerId != null) {
            buffers.remove(playerId);
        }
    }

    public void clearAll() {
        buffers.clear();
    }

    public int playerCount() {
        return buffers.size();
    }

    public long bufferedBytes(UUID playerId) {
        PlayerFrames frames = buffers.get(playerId);
        return frames == null ? 0 : frames.bytes();
    }

    private static short[] decode(List<OpusFrame> recent, OpusAudioDecoder decoder) {
        FirDecimator3To1 decimator = new FirDecimator3To1();
        short[] frame48k = new short[AudioFormat.MAX_FRAME_SAMPLES_48K];
        short[] frame16k = new short[AudioFormat.MAX_FRAME_SAMPLES_16K + 1];
        PcmTail out = new PcmTail(MAX_CLIP_SAMPLES);
        long expectedNextMs = Long.MIN_VALUE;
        for (OpusFrame frame : recent) {
            long gap = expectedNextMs == Long.MIN_VALUE ? 0 : frame.timestampMs() - expectedNextMs;
            if (gap > GAP_THRESHOLD_MS) {
                out.appendSilence((int) (Math.min(gap, MAX_GAP_SILENCE_MS) * AudioFormat.SAMPLES_PER_MS_16K));
                decoder.reset();
                decimator.reset();
            }
            byte[] data = frame.data();
            int samples = decoder.decode(data, 0, data.length, frame48k);
            if (samples == OpusAudioDecoder.DECODE_FAILED) {
                samples = decoder.conceal(frame48k);
            }
            if (samples <= 0) {
                continue;
            }
            int written = decimator.decimate(frame48k, samples, frame16k);
            out.append(frame16k, written);
            expectedNextMs = Math.max(expectedNextMs, frame.timestampMs()) + samples / AudioFormat.SAMPLES_PER_MS_48K;
        }
        return out.toArray();
    }

    private long windowStart(Duration window, long nowMs) {
        long windowMs = Math.min(retentionMs, Math.max(0L, window.toMillis()));
        return nowMs - windowMs;
    }

    private static long retentionMillis(Duration retention) {
        Objects.requireNonNull(retention, "retention");
        if (retention.isNegative()) {
            throw new IllegalArgumentException("retention must not be negative, got " + retention);
        }
        return Math.min(retention.toMillis(), MAX_RETENTION_SECONDS * 1000L);
    }

    private static final class PlayerFrames {

        private final ArrayDeque<OpusFrame> frames = new ArrayDeque<>();
        private long bytes;

        synchronized void add(OpusFrame frame, long cutoffMs, int maxBytes) {
            frames.addLast(frame);
            bytes += cost(frame);
            dropBefore(cutoffMs);
            while (bytes > maxBytes && !frames.isEmpty()) {
                bytes -= cost(frames.removeFirst());
            }
        }

        synchronized boolean dropBefore(long cutoffMs) {
            Iterator<OpusFrame> it = frames.iterator();
            while (it.hasNext()) {
                OpusFrame frame = it.next();
                if (frame.timestampMs() >= cutoffMs) {
                    break;
                }
                bytes -= cost(frame);
                it.remove();
            }
            return frames.isEmpty();
        }

        synchronized boolean anyWithin(long fromMs, long toMs) {
            for (OpusFrame frame : frames) {
                if (frame.timestampMs() >= fromMs && frame.timestampMs() <= toMs) {
                    return true;
                }
            }
            return false;
        }

        synchronized List<OpusFrame> within(long fromMs, long toMs) {
            List<OpusFrame> out = new ArrayList<>();
            for (OpusFrame frame : frames) {
                if (frame.timestampMs() >= fromMs && frame.timestampMs() <= toMs) {
                    out.add(frame);
                }
            }
            return out;
        }

        synchronized long bytes() {
            return bytes;
        }

        private static long cost(OpusFrame frame) {
            return frame.data().length + (long) FRAME_OVERHEAD_BYTES;
        }
    }

    private static final class PcmTail {

        private final int capacity;
        private short[] samples = new short[AudioFormat.SAMPLE_RATE_16K];
        private int length;

        PcmTail(int capacity) {
            this.capacity = capacity;
        }

        void appendSilence(int count) {
            ensure(count);
            Arrays.fill(samples, length, length + count, (short) 0);
            length += count;
            trim();
        }

        void append(short[] source, int count) {
            ensure(count);
            System.arraycopy(source, 0, samples, length, count);
            length += count;
            trim();
        }

        short[] toArray() {
            return Arrays.copyOf(samples, length);
        }

        private void ensure(int extra) {
            int needed = length + extra;
            if (needed > samples.length) {
                samples = Arrays.copyOf(samples, Math.max(needed, Math.min(samples.length * 2, capacity * 2)));
            }
        }

        private void trim() {
            if (length <= capacity) {
                return;
            }
            int excess = length - capacity;
            System.arraycopy(samples, excess, samples, 0, capacity);
            length = capacity;
        }
    }
}
