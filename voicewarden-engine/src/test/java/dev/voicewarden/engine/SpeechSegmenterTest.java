package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpeechSegmenterTest {

    private static final float THRESHOLD = 0.5f;
    private static final int W = SpeechSegmenter.WINDOW_SAMPLES;

    private static float[] probs(float... values) {
        return values;
    }

    private static float[] silenceWith(int length, int from, int to, float value) {
        float[] p = new float[length];
        Arrays.fill(p, 0.02f);
        Arrays.fill(p, from, to, value);
        return p;
    }

    @Test
    void quietAudioHasNoSegments() {
        assertEquals(List.of(), SpeechSegmenter.segments(new float[40], THRESHOLD, 4, 9));
        assertEquals(List.of(), SpeechSegmenter.segments(silenceWith(40, 5, 30, 0.45f), THRESHOLD, 4, 9),
                "probabilities that never reach the threshold are not speech");
    }

    @Test
    void burstsShorterThanMinSpeechAreDropped() {
        assertEquals(List.of(), SpeechSegmenter.segments(silenceWith(40, 10, 13, 0.9f), THRESHOLD, 4, 9));
        assertEquals(List.of(new SpeechSegmenter.Segment(10, 14)),
                SpeechSegmenter.segments(silenceWith(40, 10, 14, 0.9f), THRESHOLD, 4, 9));
    }

    @Test
    void hysteresisKeepsASegmentOpenAboveTheNegativeThreshold() {
        float[] p = silenceWith(40, 10, 11, 0.9f);
        Arrays.fill(p, 11, 16, 0.4f);
        assertEquals(List.of(new SpeechSegmenter.Segment(10, 16)), SpeechSegmenter.segments(p, THRESHOLD, 4, 9));
    }

    @Test
    void shortPausesAreBridgedAndLongPausesSplit() {
        float[] bridged = silenceWith(60, 5, 10, 0.9f);
        Arrays.fill(bridged, 15, 20, 0.9f);
        assertEquals(List.of(new SpeechSegmenter.Segment(5, 20)), SpeechSegmenter.segments(bridged, THRESHOLD, 4, 9));

        float[] split = silenceWith(60, 5, 10, 0.9f);
        Arrays.fill(split, 25, 30, 0.9f);
        assertEquals(List.of(new SpeechSegmenter.Segment(5, 10), new SpeechSegmenter.Segment(25, 30)),
                SpeechSegmenter.segments(split, THRESHOLD, 4, 9));
    }

    @Test
    void speechRunningToTheEndOfTheBufferIsKept() {
        assertEquals(List.of(new SpeechSegmenter.Segment(36, 40)),
                SpeechSegmenter.segments(silenceWith(40, 36, 40, 0.8f), THRESHOLD, 4, 9));
        assertEquals(List.of(new SpeechSegmenter.Segment(0, 5)),
                SpeechSegmenter.segments(probs(0.9f, 0.9f, 0.9f, 0.9f, 0.9f), THRESHOLD, 4, 9));
    }

    @Test
    void settingsRoundDurationsDownToWholeWindowsSoShortWordsAreNotSwallowed() {
        SileroVadSettings defaults = SileroVadSettings.DEFAULTS;
        assertEquals(4, defaults.minSpeechWindows(), "150 ms must need 4 whole windows (128 ms), not 5 (160 ms)");
        assertEquals(9, defaults.minSilenceWindows());
        assertEquals(1, SileroVadSettings.wholeWindows(1));
        assertTrue(SileroVadSettings.wholeWindows(250) * W <= 250 * SpeechRecognizerEngine.SAMPLE_RATE / 1000);
    }

    @Test
    void settingsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new SileroVadSettings(true, 0f, 150, 300));
        assertThrows(IllegalArgumentException.class, () -> new SileroVadSettings(true, 1f, 150, 300));
        assertThrows(IllegalArgumentException.class, () -> new SileroVadSettings(true, Float.NaN, 150, 300));
        assertThrows(IllegalArgumentException.class, () -> new SileroVadSettings(true, 0.5f, 0, 300));
        assertThrows(IllegalArgumentException.class, () -> new SileroVadSettings(true, 0.5f, 150, 20_000));
    }
}
