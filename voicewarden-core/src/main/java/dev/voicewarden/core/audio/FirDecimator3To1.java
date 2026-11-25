package dev.voicewarden.core.audio;

import java.util.Arrays;

public final class FirDecimator3To1 {

    public static final int DEFAULT_TAPS = 31;
    public static final double DEFAULT_CUTOFF = 0.15;

    private final float[] coefficients;
    private final int taps;
    private final float[] history;
    private int position;
    private int phase;

    public FirDecimator3To1() {
        this(DEFAULT_TAPS, DEFAULT_CUTOFF);
    }

    public FirDecimator3To1(int taps, double normalizedCutoff) {
        if (taps < 3 || (taps & 1) == 0) {
            throw new IllegalArgumentException("FIR length must be odd and at least 3, got " + taps);
        }
        if (!(normalizedCutoff > 0.0 && normalizedCutoff < 0.5)) {
            throw new IllegalArgumentException("Cutoff must be in (0, 0.5) of the input rate, got " + normalizedCutoff);
        }
        this.taps = taps;
        this.coefficients = designLowPass(taps, normalizedCutoff);
        this.history = new float[taps * 2];
    }

    static float[] designLowPass(int taps, double cutoff) {
        double[] h = new double[taps];
        int mid = taps / 2;
        double sum = 0.0;
        for (int n = 0; n < taps; n++) {
            int k = n - mid;
            double sinc = k == 0 ? 2.0 * cutoff : Math.sin(2.0 * Math.PI * cutoff * k) / (Math.PI * k);
            double window = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * n / (taps - 1));
            h[n] = sinc * window;
            sum += h[n];
        }
        float[] out = new float[taps];
        for (int n = 0; n < taps; n++) {
            out[n] = (float) (h[n] / sum);
        }
        return out;
    }

    public int decimate(short[] input48k, int inputLength, short[] output16k) {
        return decimate(input48k, 0, inputLength, output16k, 0);
    }

    public int decimate(short[] input48k, int inputOffset, int inputLength, short[] output16k, int outputOffset) {
        int maxOut = outputLength(inputLength);
        if (outputOffset + maxOut > output16k.length) {
            throw new IllegalArgumentException("Output buffer holds " + (output16k.length - outputOffset)
                    + " samples but " + maxOut + " are needed");
        }
        float[] hist = history;
        float[] coeff = coefficients;
        int n = taps;
        int pos = position;
        int ph = phase;
        int out = outputOffset;
        int end = inputOffset + inputLength;
        for (int i = inputOffset; i < end; i++) {
            float sample = input48k[i];
            hist[pos] = sample;
            hist[pos + n] = sample;
            pos++;
            if (pos == n) {
                pos = 0;
            }
            ph++;
            if (ph == AudioFormat.DECIMATION_FACTOR) {
                ph = 0;
                float acc = 0f;
                for (int t = 0; t < n; t++) {
                    acc += coeff[t] * hist[pos + t];
                }
                output16k[out++] = clampToShort(acc);
            }
        }
        position = pos;
        phase = ph;
        return out - outputOffset;
    }

    public int outputLength(int inputLength) {
        return (phase + inputLength) / AudioFormat.DECIMATION_FACTOR;
    }

    public void reset() {
        Arrays.fill(history, 0f);
        position = 0;
        phase = 0;
    }

    public int taps() {
        return taps;
    }

    float[] coefficients() {
        return coefficients.clone();
    }

    private static short clampToShort(float value) {
        int rounded = Math.round(value);
        if (rounded > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (rounded < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) rounded;
    }
}
