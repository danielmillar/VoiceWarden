package dev.voicewarden.core.audio;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AcousticAbuseDetector {

    public static final int BLOCK_MS = 10;
    public static final int BLOCK_SAMPLES = BLOCK_MS * AudioFormat.SAMPLES_PER_MS_16K;
    public static final double MIN_HOT_COVERAGE = 0.8;
    public static final int CAPTURE_LEAD_MS = 1_000;
    public static final long MAX_FRAME_GAP_MS = 250L;

    private static final byte CLIPPED = 1;
    private static final byte LOUD = 2;
    private static final long NEVER = Long.MIN_VALUE;

    private final UUID playerId;
    private MicSpamConfig config;
    private int windowBlocks;
    private int requiredHotBlocks;
    private int clippedSamplesPerHotBlock;
    private double loudMeanSquare;
    private byte[] blockFlags = new byte[0];
    private int[] blockClipped = new int[0];
    private double[] blockMeanSquare = new double[0];
    private short[] capture = new short[0];
    private int blockHead;
    private int blocksFilled;
    private int hotBlocks;
    private long pendingSumSquares;
    private int pendingClipped;
    private int pendingSamples;
    private int captureWrite;
    private int captureFilled;
    private long lastSeenMs = NEVER;
    private long lastTriggerMs = NEVER;

    public AcousticAbuseDetector(UUID playerId, MicSpamConfig config) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        reconfigure(config);
    }

    public void reconfigure(MicSpamConfig newConfig) {
        config = Objects.requireNonNull(newConfig, "newConfig");
        if (config.enabled()) {
            windowBlocks = Math.ceilDiv(config.minDurationMs(), BLOCK_MS);
            requiredHotBlocks = (int) Math.ceil(windowBlocks * MIN_HOT_COVERAGE);
            clippedSamplesPerHotBlock = (int) Math.ceil(config.clippingRatio() * BLOCK_SAMPLES);
            loudMeanSquare = config.rmsThreshold() * config.rmsThreshold();
            blockFlags = new byte[windowBlocks];
            blockClipped = new int[windowBlocks];
            blockMeanSquare = new double[windowBlocks];
            capture = new short[(windowBlocks * BLOCK_MS + CAPTURE_LEAD_MS) * AudioFormat.SAMPLES_PER_MS_16K];
        } else {
            windowBlocks = 0;
            blockFlags = new byte[0];
            blockClipped = new int[0];
            blockMeanSquare = new double[0];
            capture = new short[0];
        }
        reset();
    }

    public Optional<AcousticAbuseEvent> process(short[] pcm16k, int offset, int length, long nowMs) {
        if (!config.enabled() || length <= 0) {
            return Optional.empty();
        }
        if (lastSeenMs != NEVER && nowMs - lastSeenMs > MAX_FRAME_GAP_MS) {
            reset();
        }
        lastSeenMs = nowMs;
        writeCapture(pcm16k, offset, length);
        int clipLevel = config.clippingSampleThreshold();
        AcousticAbuseEvent event = null;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            int sample = pcm16k[i];
            pendingSumSquares += (long) sample * sample;
            if (Math.abs(sample) >= clipLevel) {
                pendingClipped++;
            }
            if (++pendingSamples == BLOCK_SAMPLES) {
                closeBlock();
                if (event == null && blocksFilled == windowBlocks && hotBlocks >= requiredHotBlocks && !coolingDown(nowMs)) {
                    event = buildEvent();
                    lastTriggerMs = nowMs;
                    clearBlocks();
                }
            }
        }
        return Optional.ofNullable(event);
    }

    public void reset() {
        clearBlocks();
        captureWrite = 0;
        captureFilled = 0;
        lastSeenMs = NEVER;
    }

    private boolean coolingDown(long nowMs) {
        return lastTriggerMs != NEVER && nowMs - lastTriggerMs < config.cooldownSeconds() * 1000L;
    }

    private void closeBlock() {
        double meanSquare = (double) pendingSumSquares / BLOCK_SAMPLES;
        byte flags = 0;
        if (pendingClipped >= clippedSamplesPerHotBlock) {
            flags |= CLIPPED;
        }
        if (meanSquare >= loudMeanSquare) {
            flags |= LOUD;
        }
        if (blocksFilled == windowBlocks) {
            if (blockFlags[blockHead] != 0) {
                hotBlocks--;
            }
        } else {
            blocksFilled++;
        }
        blockFlags[blockHead] = flags;
        blockClipped[blockHead] = pendingClipped;
        blockMeanSquare[blockHead] = meanSquare;
        if (flags != 0) {
            hotBlocks++;
        }
        blockHead = blockHead + 1 == windowBlocks ? 0 : blockHead + 1;
        pendingSumSquares = 0;
        pendingClipped = 0;
        pendingSamples = 0;
    }

    private AcousticAbuseEvent buildEvent() {
        long clipped = 0;
        double peakMeanSquare = 0;
        int clippedBlocks = 0;
        for (int i = 0; i < windowBlocks; i++) {
            clipped += blockClipped[i];
            peakMeanSquare = Math.max(peakMeanSquare, blockMeanSquare[i]);
            if ((blockFlags[i] & CLIPPED) != 0) {
                clippedBlocks++;
            }
        }
        AcousticAbuseEvent.Kind kind = clippedBlocks * 2 >= hotBlocks
                ? AcousticAbuseEvent.Kind.CLIPPING
                : AcousticAbuseEvent.Kind.LOUDNESS;
        double clippingRatio = (double) clipped / ((long) windowBlocks * BLOCK_SAMPLES);
        return new AcousticAbuseEvent(playerId, kind, Math.sqrt(peakMeanSquare), clippingRatio, windowBlocks * BLOCK_MS, captured());
    }

    private void clearBlocks() {
        blockHead = 0;
        blocksFilled = 0;
        hotBlocks = 0;
        pendingSumSquares = 0;
        pendingClipped = 0;
        pendingSamples = 0;
    }

    private void writeCapture(short[] pcm, int offset, int length) {
        int capacity = capture.length;
        int src = offset;
        int remaining = length;
        if (remaining > capacity) {
            src += remaining - capacity;
            remaining = capacity;
        }
        captureFilled = Math.min(capacity, captureFilled + remaining);
        while (remaining > 0) {
            int run = Math.min(remaining, capacity - captureWrite);
            System.arraycopy(pcm, src, capture, captureWrite, run);
            captureWrite = captureWrite + run == capacity ? 0 : captureWrite + run;
            src += run;
            remaining -= run;
        }
    }

    private short[] captured() {
        short[] out = new short[captureFilled];
        int start = captureWrite - captureFilled;
        if (start < 0) {
            start += capture.length;
        }
        int first = Math.min(captureFilled, capture.length - start);
        System.arraycopy(capture, start, out, 0, first);
        System.arraycopy(capture, 0, out, first, captureFilled - first);
        return out;
    }
}
