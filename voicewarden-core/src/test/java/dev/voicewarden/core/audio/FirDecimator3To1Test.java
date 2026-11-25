package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FirDecimator3To1Test {

    private static final int FRAMES = 20;
    private static final int FRAME = AudioFormat.OPUS_FRAME_20MS_48K;

    @Test
    void coefficientsAreSymmetricOddLengthWithUnityDcGain() {
        FirDecimator3To1 fir = new FirDecimator3To1();
        float[] h = fir.coefficients();
        assertEquals(1, h.length % 2);
        double sum = 0;
        for (int i = 0; i < h.length; i++) {
            assertEquals(h[i], h[h.length - 1 - i], 1e-7f);
            sum += h[i];
        }
        assertEquals(1.0, sum, 1e-5);
    }

    @Test
    void producesExactlyOneThirdOfTheInput() {
        FirDecimator3To1 fir = new FirDecimator3To1();
        short[] out = new short[AudioFormat.MAX_FRAME_SAMPLES_16K];
        assertEquals(320, fir.decimate(new short[FRAME], FRAME, out));
        assertEquals(960, fir.decimate(new short[2880], 2880, out));
        assertEquals(160, fir.decimate(new short[480], 480, out));
    }

    @Test
    void carriesPhaseAcrossLengthsNotDivisibleByThree() {
        FirDecimator3To1 fir = new FirDecimator3To1();
        short[] out = new short[AudioFormat.MAX_FRAME_SAMPLES_16K];
        int total = 0;
        int produced = 0;
        for (int len : new int[] {7, 1, 2, 961, 13, 959, 5}) {
            produced += fir.decimate(new short[len], len, out);
            total += len;
        }
        assertEquals(total / 3, produced);
    }

    @Test
    void preservesOneKilohertzPassband() {
        short[] in = TestSignals.sine(1000, AudioFormat.SAMPLE_RATE_48K, FRAME * FRAMES, 10_000, 0);
        short[] out = decimateInFrames(new FirDecimator3To1(), in, FRAME);
        int settle = new FirDecimator3To1().taps();
        double expected = 10_000 / Math.sqrt(2);
        double actual = TestSignals.rms(out, settle, out.length);
        assertEquals(expected, actual, expected * 0.02, "1 kHz RMS after decimation");
        double tone = TestSignals.goertzelPower(out, settle, out.length, 1000, AudioFormat.SAMPLE_RATE_16K);
        assertTrue(tone > 0.98 * actual * actual, "1 kHz should hold the output energy");
    }

    @Test
    void rejectsSixteenKilohertzAlias() {
        short[] in = TestSignals.sine(16_000, AudioFormat.SAMPLE_RATE_48K, FRAME * FRAMES, 10_000, 1);
        short[] out = decimateInFrames(new FirDecimator3To1(), in, FRAME);
        int settle = new FirDecimator3To1().taps();
        double inputRms = TestSignals.rms(in, 0, in.length);
        double outputRms = TestSignals.rms(out, settle, out.length);
        double attenuationDb = 20 * Math.log10(outputRms / inputRms);
        assertTrue(attenuationDb < -40, "16 kHz alias attenuation was " + attenuationDb + " dB");
    }

    @Test
    void rejectsTwelveKilohertzAlias() {
        short[] in = TestSignals.sine(12_000, AudioFormat.SAMPLE_RATE_48K, FRAME * FRAMES, 10_000, 1);
        short[] out = decimateInFrames(new FirDecimator3To1(), in, FRAME);
        int settle = new FirDecimator3To1().taps();
        double attenuationDb = 20 * Math.log10(TestSignals.rms(out, settle, out.length) / TestSignals.rms(in, 0, in.length));
        assertTrue(attenuationDb < -40, "12 kHz alias attenuation was " + attenuationDb + " dB");
    }

    @Test
    void frameBoundariesMatchOneContinuousPass() {
        short[] in = TestSignals.concat(
                TestSignals.sine(440, AudioFormat.SAMPLE_RATE_48K, FRAME * 6, 12_000, 0),
                TestSignals.sine(3_100, AudioFormat.SAMPLE_RATE_48K, FRAME * 6, 9_000, 0));
        short[] whole = new short[in.length / 3];
        new FirDecimator3To1().decimate(in, in.length, whole);
        assertArrayEquals(whole, decimateInFrames(new FirDecimator3To1(), in, FRAME));
        assertArrayEquals(whole, decimateInFrames(new FirDecimator3To1(), in, 7));
        assertArrayEquals(whole, decimateInFrames(new FirDecimator3To1(), in, 1001));
    }

    @Test
    void smoothSineHasNoDiscontinuityAtFrameEdges() {
        short[] in = TestSignals.sine(500, AudioFormat.SAMPLE_RATE_48K, FRAME * FRAMES, 10_000, 0);
        short[] out = decimateInFrames(new FirDecimator3To1(), in, FRAME);
        double maxStep = 2 * Math.PI * 500 / AudioFormat.SAMPLE_RATE_16K * 10_000 * 1.05;
        for (int i = 32; i < out.length; i++) {
            assertTrue(Math.abs(out[i] - out[i - 1]) <= maxStep, "jump at sample " + i);
        }
    }

    @Test
    void doesNotAllocatePerCall() {
        FirDecimator3To1 fir = new FirDecimator3To1();
        short[] in = TestSignals.sine(1000, AudioFormat.SAMPLE_RATE_48K, FRAME, 8_000, 0);
        short[] out = new short[320];
        for (int i = 0; i < 20_000; i++) {
            fir.decimate(in, FRAME, out);
        }
        Thread self = Thread.currentThread();
        long before = TestSignals.allocatedBytes(self);
        for (int i = 0; i < 10_000; i++) {
            fir.decimate(in, FRAME, out);
        }
        long allocated = TestSignals.allocatedBytes(self) - before;
        assertTrue(allocated < 1024, "decimate allocated " + allocated + " bytes over 10,000 frames");
    }

    @Test
    void rejectsUndersizedOutput() {
        FirDecimator3To1 fir = new FirDecimator3To1();
        assertThrows(IllegalArgumentException.class, () -> fir.decimate(new short[FRAME], FRAME, new short[100]));
    }

    private static short[] decimateInFrames(FirDecimator3To1 fir, short[] in, int frame) {
        short[] out = new short[in.length / 3];
        short[] scratch = new short[frame / 3 + 1];
        int written = 0;
        for (int pos = 0; pos < in.length; pos += frame) {
            int len = Math.min(frame, in.length - pos);
            short[] chunk = new short[len];
            System.arraycopy(in, pos, chunk, 0, len);
            int produced = fir.decimate(chunk, len, scratch);
            System.arraycopy(scratch, 0, out, written, produced);
            written += produced;
        }
        assertEquals(out.length, written);
        return out;
    }
}
