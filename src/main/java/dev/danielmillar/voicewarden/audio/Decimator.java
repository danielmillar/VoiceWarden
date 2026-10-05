package dev.danielmillar.voicewarden.audio;

import java.util.Arrays;

/**
 * Converts 48 kHz mono PCM (Simple Voice Chat's Opus output) to 16 kHz (what the speech model expects) with a
 * linear-phase windowed-sinc low-pass filter evaluated only at the kept samples (polyphase decimation by 3).
 *
 * <p>Cost is ~95 multiply-adds per output sample (~1.5 M/s per active speaker), negligible next to the model.
 * Stateful across calls so 20 ms frames join seamlessly. Not thread-safe: one instance per speaker.
 */
public final class Decimator {

    public static final int INPUT_RATE = 48_000;
    public static final int OUTPUT_RATE = 16_000;
    private static final int FACTOR = INPUT_RATE / OUTPUT_RATE;
    /** Passband edge ~6.8 kHz, stopband from ~8 kHz (Blackman window, ~74 dB stopband attenuation). */
    private static final float[] TAPS = design(95, 7_400.0 / INPUT_RATE);
    private static final int HISTORY = TAPS.length - 1;

    private float[] work = new float[HISTORY + 960];
    private int phase;

    /** Clears the filter history, e.g. at the start of a new utterance. */
    public void reset() {
        Arrays.fill(work, 0, HISTORY, 0f);
        phase = 0;
    }

    /** Filters {@code count} samples of {@code in} and appends the 16 kHz result to {@code out}. */
    public void process(short[] in, int count, PcmBuffer out) {
        if (work.length < HISTORY + count) {
            work = Arrays.copyOf(work, HISTORY + count);
        }
        float[] w = work;
        for (int i = 0; i < count; i++) {
            w[HISTORY + i] = in[i];
        }
        int total = HISTORY + count;
        int i = HISTORY + phase;
        out.ensureCapacity(out.size() + count / FACTOR + 1);
        for (; i < total; i += FACTOR) {
            int base = i - HISTORY;
            float acc = 0f;
            for (int k = 0; k < TAPS.length; k++) {
                acc += TAPS[k] * w[base + k];
            }
            out.append(acc >= Short.MAX_VALUE ? Short.MAX_VALUE : acc <= Short.MIN_VALUE ? Short.MIN_VALUE : (short) Math.round(acc));
        }
        phase = i - total;
        System.arraycopy(w, total - HISTORY, w, 0, HISTORY);
    }

    static float[] design(int n, double cutoff) {
        double[] h = new double[n];
        int m = n - 1;
        double sum = 0;
        for (int k = 0; k < n; k++) {
            double x = k - m / 2.0;
            double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
            double window = 0.42 - 0.5 * Math.cos(2 * Math.PI * k / m) + 0.08 * Math.cos(4 * Math.PI * k / m);
            h[k] = sinc * window;
            sum += h[k];
        }
        float[] taps = new float[n];
        for (int k = 0; k < n; k++) {
            taps[k] = (float) (h[k] / sum);
        }
        return taps;
    }
}
