package dev.danielmillar.nevusvoice.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DecimatorTest {
    @Test void passbandPreservesOneKilohertzWithinHalfADecibel() {
        assertEquals(0, gainDb(1_000), 0.5);
    }

    @Test void nineKilohertzIsAttenuatedMoreThanFortyDecibels() {
        assertTrue(gainDb(9_000) < -40);
    }

    @Test void twelveKilohertzIsAttenuatedMoreThanSixtyDecibels() {
        assertTrue(gainDb(12_000) < -60);
    }

    @Test void twentyMillisecondChunksMatchOneContinuousArray() {
        short[] input = noise(48_000);
        assertArrayEquals(convert(input), chunks(input, 960));
    }

    @Test void phaseAndHistorySurviveNonMultipleOfThreeChunks() {
        short[] input = noise(48_000);
        assertArrayEquals(convert(input), chunks(input, 137));
    }

    @ParameterizedTest @ValueSource(ints = {0, 3, 96, 960, 48_000})
    void outputLengthIsExactlyOneThird(int length) {
        assertEquals(length / 3, convert(noise(length)).length);
    }

    @Test void resetClearsHistoryAndPhase() {
        Decimator decimator = new Decimator();
        decimator.process(noise(137), 137, new PcmBuffer(1));
        decimator.reset();
        short[] input = noise(960);
        PcmBuffer output = new PcmBuffer(1);
        decimator.process(input, input.length, output);
        assertArrayEquals(convert(input), output.copy(0, output.size()));
    }

    private static double gainDb(int frequency) {
        short[] input = new short[48_000];
        for (int i = 0; i < input.length; i++) {
            input[i] = (short) Math.round(20_000 * Math.sin(2 * Math.PI * frequency * i / 48_000));
        }
        short[] output = convert(input);
        // Skip a full second's first 20 ms, well beyond the 95-tap settling transient.
        double inRms = rms(input, 960);
        double outRms = rms(output, 320);
        return 20 * Math.log10(outRms / inRms);
    }

    private static double rms(short[] samples, int from) {
        double sum = 0;
        for (int i = from; i < samples.length; i++) sum += (double) samples[i] * samples[i];
        return Math.sqrt(sum / (samples.length - from));
    }

    private static short[] noise(int length) {
        Random random = new Random(42);
        short[] samples = new short[length];
        for (int i = 0; i < length; i++) samples[i] = (short) random.nextInt(Short.MIN_VALUE, Short.MAX_VALUE + 1);
        return samples;
    }

    private static short[] convert(short[] input) { return chunks(input, Math.max(1, input.length)); }

    private static short[] chunks(short[] input, int size) {
        Decimator decimator = new Decimator();
        PcmBuffer output = new PcmBuffer(1);
        for (int offset = 0; offset < input.length; offset += size) {
            short[] chunk = Arrays.copyOfRange(input, offset, Math.min(input.length, offset + size));
            decimator.process(chunk, chunk.length, output);
        }
        return output.copy(0, output.size());
    }
}
