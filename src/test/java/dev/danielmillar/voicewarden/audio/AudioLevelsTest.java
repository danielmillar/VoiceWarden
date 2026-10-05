package dev.danielmillar.voicewarden.audio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AudioLevelsTest {
    @Test void quietAudioReachesTargetWithoutChangingEvidence() {
        short[] pcm = alternating(320, 100);
        short[] original = pcm.clone();
        float[] boosted = AudioLevels.toFloatWithGain(pcm, 16_000, -20, 40);
        for (int i = 0; i < boosted.length; i++) {
            assertEquals(i % 2 == 0 ? 0.1f : -0.1f, boosted[i], 1e-6f);
        }
        assertArrayEquals(original, pcm);
    }

    @Test void gainCapLimitsVeryQuietInput() {
        short[] pcm = alternating(320, 1);
        float[] boosted = AudioLevels.toFloatWithGain(pcm, 16_000, -20, 40);
        assertEquals(100f / 32768, boosted[0], 1e-7f);
        assertEquals(-100f / 32768, boosted[1], 1e-7f);
    }

    @Test void peaksLimitGainWithoutClipping() {
        short[] pcm = alternating(16_000, 10);
        pcm[0] = 15_000;
        float[] boosted = AudioLevels.toFloatWithGain(pcm, 16_000, -20, 40);
        assertEquals(0.98f, boosted[0], 1e-6f);
        assertTrue(boosted[2] > 10f / 32768);
        for (float sample : boosted) assertTrue(Math.abs(sample) <= 0.980001);
    }

    @Test void alreadyLoudSpeechAndZeroGainRemainUnchanged() {
        short[] loud = alternating(320, 25_000);
        assertArrayEquals(AudioLevels.toFloat(loud), AudioLevels.toFloatWithGain(loud, 16_000, -20, 40));
        short[] quiet = alternating(320, 100);
        assertArrayEquals(AudioLevels.toFloat(quiet), AudioLevels.toFloatWithGain(quiet, 16_000, -20, 0));
    }

    @Test void silenceAndEmptyInputRemainSilence() {
        assertArrayEquals(new float[320], AudioLevels.toFloatWithGain(new short[320], 16_000, -20, 40));
        assertArrayEquals(new float[0], AudioLevels.toFloatWithGain(new short[0], 16_000, -20, 40));
    }

    @Test void trailingSilenceDoesNotInflateGainAndPartialFramesAreMeasured() {
        short[] speech = alternating(160, 100);
        short[] padded = Arrays.copyOf(alternating(320, 100), 16_000);
        float[] shortResult = AudioLevels.toFloatWithGain(speech, 16_000, -20, 40);
        float[] paddedResult = AudioLevels.toFloatWithGain(padded, 16_000, -20, 40);
        assertEquals(shortResult[0], paddedResult[0]);
        assertEquals(0, paddedResult[15_999]);
        assertEquals(AudioLevels.meanSquare(speech, 0, speech.length),
                AudioLevels.loudestFrameMeanSquare(speech, 0, speech.length, 320));
    }

    private static short[] alternating(int samples, int amplitude) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) pcm[i] = (short) (i % 2 == 0 ? amplitude : -amplitude);
        return pcm;
    }
}
