package dev.voicewarden.engine;

import java.util.ArrayList;
import java.util.List;

final class SpeechSegmenter {

    static final int WINDOW_SAMPLES = 512;
    static final float HYSTERESIS = 0.15f;
    static final float MIN_NEGATIVE_THRESHOLD = 0.01f;

    private SpeechSegmenter() {
    }

    record Segment(int startWindow, int endWindow) {
    }

    static List<Segment> segments(float[] probabilities, float threshold, int minSpeechWindows, int minSilenceWindows) {
        float negative = Math.max(MIN_NEGATIVE_THRESHOLD, threshold - HYSTERESIS);
        List<Segment> out = new ArrayList<>();
        int start = -1;
        int lastSpeech = -1;
        for (int i = 0; i < probabilities.length; i++) {
            float p = probabilities[i];
            if (start < 0) {
                if (p >= threshold) {
                    start = i;
                    lastSpeech = i;
                }
            } else if (p >= negative) {
                lastSpeech = i;
            } else if (i - lastSpeech >= minSilenceWindows) {
                keep(out, start, lastSpeech + 1, minSpeechWindows);
                start = -1;
            }
        }
        if (start >= 0) {
            keep(out, start, lastSpeech + 1, minSpeechWindows);
        }
        return List.copyOf(out);
    }

    private static void keep(List<Segment> out, int start, int end, int minSpeechWindows) {
        if (end - start >= minSpeechWindows) {
            out.add(new Segment(start, end));
        }
    }
}
