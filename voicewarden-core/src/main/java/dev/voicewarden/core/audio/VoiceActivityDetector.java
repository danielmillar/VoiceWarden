package dev.voicewarden.core.audio;

public final class VoiceActivityDetector {

    public static final int ONSET_CONFIRM_MS = 40;
    public static final int MIN_ZERO_CROSSINGS_PER_10MS = 1;
    public static final int MIN_LOUD_SAMPLES_DIVISOR = 16;

    public enum Decision {
        SILENCE,
        SPEECH_START,
        SPEECH,
        SPEECH_END
    }

    private final double rmsThreshold;
    private final int hangoverSamples;
    private final int onsetSamples;
    private final int preRollSamples;
    private final short[] ring;
    private int ringWrite;
    private int ringFilled;
    private int consecutiveActiveSamples;
    private int silentSamples;
    private boolean inSpeech;
    private boolean lastFrameActive;
    private int lastPreRollLength;
    private int lastOnsetLength;

    public VoiceActivityDetector(AudioConfig config) {
        this(config.vadRmsThreshold(), config.silenceThresholdMs(), config.preRollMs());
    }

    public VoiceActivityDetector(double rmsThreshold, int hangoverMs, int preRollMs) {
        this.rmsThreshold = rmsThreshold;
        this.hangoverSamples = Math.max(1, hangoverMs) * AudioFormat.SAMPLES_PER_MS_16K;
        this.onsetSamples = ONSET_CONFIRM_MS * AudioFormat.SAMPLES_PER_MS_16K;
        this.preRollSamples = Math.max(0, preRollMs) * AudioFormat.SAMPLES_PER_MS_16K;
        this.ring = new short[preRollSamples + onsetSamples + AudioFormat.MAX_FRAME_SAMPLES_16K];
    }

    public Decision process(short[] pcm16k, int length) {
        return process(pcm16k, 0, length);
    }

    public Decision process(short[] pcm16k, int offset, int length) {
        if (length <= 0) {
            return inSpeech ? Decision.SPEECH : Decision.SILENCE;
        }
        lastFrameActive = isActive(pcm16k, offset, length, rmsThreshold);
        writeRing(pcm16k, offset, length);
        if (inSpeech) {
            if (lastFrameActive) {
                silentSamples = 0;
                return Decision.SPEECH;
            }
            silentSamples += length;
            if (silentSamples >= hangoverSamples) {
                inSpeech = false;
                silentSamples = 0;
                consecutiveActiveSamples = 0;
                return Decision.SPEECH_END;
            }
            return Decision.SPEECH;
        }
        if (!lastFrameActive) {
            consecutiveActiveSamples = 0;
            return Decision.SILENCE;
        }
        consecutiveActiveSamples += length;
        if (consecutiveActiveSamples < onsetSamples) {
            return Decision.SILENCE;
        }
        inSpeech = true;
        silentSamples = 0;
        lastOnsetLength = Math.min(consecutiveActiveSamples, ringFilled);
        lastPreRollLength = Math.min(ringFilled, lastOnsetLength + preRollSamples);
        return Decision.SPEECH_START;
    }

    public int preRollLength() {
        return lastPreRollLength;
    }

    public int onsetLength() {
        return lastOnsetLength;
    }

    public int copyPreRoll(short[] dest, int destOffset) {
        int count = Math.min(lastPreRollLength, dest.length - destOffset);
        int start = ringWrite - lastPreRollLength;
        if (start < 0) {
            start += ring.length;
        }
        int first = Math.min(count, ring.length - start);
        System.arraycopy(ring, start, dest, destOffset, first);
        if (count > first) {
            System.arraycopy(ring, 0, dest, destOffset + first, count - first);
        }
        return count;
    }

    public boolean lastFrameActive() {
        return lastFrameActive;
    }

    public boolean inSpeech() {
        return inSpeech;
    }

    public void reset() {
        ringWrite = 0;
        ringFilled = 0;
        consecutiveActiveSamples = 0;
        silentSamples = 0;
        inSpeech = false;
        lastFrameActive = false;
        lastPreRollLength = 0;
        lastOnsetLength = 0;
    }

    static boolean isActive(short[] pcm, int offset, int length, double rmsThreshold) {
        long sum = 0;
        long sumSquares = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            int s = pcm[i];
            sum += s;
            sumSquares += (long) s * s;
        }
        double mean = (double) sum / length;
        double variance = (double) sumSquares / length - mean * mean;
        if (variance < rmsThreshold * rmsThreshold) {
            return false;
        }
        int loud = 0;
        int crossings = 0;
        boolean previousPositive = pcm[offset] >= mean;
        for (int i = offset; i < end; i++) {
            double centered = pcm[i] - mean;
            if (Math.abs(centered) >= rmsThreshold) {
                loud++;
            }
            boolean positive = centered >= 0;
            if (positive != previousPositive) {
                crossings++;
                previousPositive = positive;
            }
        }
        int minCrossings = Math.max(1, length * MIN_ZERO_CROSSINGS_PER_10MS / (10 * AudioFormat.SAMPLES_PER_MS_16K));
        return loud >= Math.max(2, length / MIN_LOUD_SAMPLES_DIVISOR) && crossings >= minCrossings;
    }

    private void writeRing(short[] pcm, int offset, int length) {
        int capacity = ring.length;
        int src = offset;
        int remaining = length;
        if (remaining > capacity) {
            src += remaining - capacity;
            remaining = capacity;
        }
        while (remaining > 0) {
            int run = Math.min(remaining, capacity - ringWrite);
            System.arraycopy(pcm, src, ring, ringWrite, run);
            ringWrite += run;
            if (ringWrite == capacity) {
                ringWrite = 0;
            }
            src += run;
            remaining -= run;
        }
        ringFilled = Math.min(capacity, ringFilled + length);
    }
}
