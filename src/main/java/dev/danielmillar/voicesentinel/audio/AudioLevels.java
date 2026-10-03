package dev.danielmillar.voicesentinel.audio;

/** Loudness helpers. */
public final class AudioLevels {

    private AudioLevels() {
    }

    /** Mean square of {@code pcm[from, to)} normalised to full scale (0..1). */
    public static double meanSquare(short[] pcm, int from, int to) {
        if (to <= from) {
            return 0;
        }
        long sum = 0;
        for (int i = from; i < to; i++) {
            int s = pcm[i];
            sum += (long) s * s;
        }
        return sum / ((double) (to - from) * 32768.0 * 32768.0);
    }

    public static double toDbfs(double meanSquare) {
        return meanSquare <= 1e-12 ? -120 : 10 * Math.log10(meanSquare);
    }

    public static double fromDbfs(double dbfs) {
        return Math.pow(10, dbfs / 10);
    }

    /** Converts to float samples in [-1, 1) as expected by the speech model. */
    public static float[] toFloat(short[] pcm) {
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = pcm[i] / 32768f;
        }
        return out;
    }

    /** Loudest frame mean square; a brief quiet utterance is not diluted by surrounding silence. */
    public static double loudestFrameMeanSquare(short[] pcm, int from, int to, int frameSamples) {
        double loudest = 0;
        for (int start = from; start < to; start += frameSamples) {
            loudest = Math.max(loudest, meanSquare(pcm, start, Math.min(to, start + frameSamples)));
        }
        return loudest;
    }

    /**
     * Boosts quiet input for VAD and recognition without changing captured PCM or amplifying beyond full scale.
     * The loudest 20 ms frame determines the gain, so trailing silence does not increase amplification.
     * Already-loud audio is unchanged. Gain is bounded independently of the capture silence gate.
     */
    public static float[] toFloatWithGain(short[] pcm, int sampleRate, double targetDbfs, double maxGainDb) {
        float[] samples = toFloat(pcm);
        double peak = 0;
        for (float sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        if (peak == 0) {
            return samples;
        }
        double level = toDbfs(loudestFrameMeanSquare(pcm, 0, pcm.length, Math.max(1, sampleRate / 50)));
        double gain = Math.pow(10, Math.min(maxGainDb, targetDbfs - level) / 20);
        gain = Math.min(gain, 0.98 / peak);
        if (gain > 1) {
            for (int i = 0; i < samples.length; i++) {
                samples[i] *= (float) gain;
            }
        }
        return samples;
    }
}
