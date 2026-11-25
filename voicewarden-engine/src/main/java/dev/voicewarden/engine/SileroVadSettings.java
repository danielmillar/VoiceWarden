package dev.voicewarden.engine;

public record SileroVadSettings(boolean enabled, float threshold, int minSpeechDurationMs, int minSilenceDurationMs) {

    public static final float DEFAULT_THRESHOLD = 0.5f;
    public static final int DEFAULT_MIN_SPEECH_MS = 150;
    public static final int DEFAULT_MIN_SILENCE_MS = 300;
    public static final int MAX_DURATION_MS = 10_000;
    public static final SileroVadSettings DEFAULTS =
            new SileroVadSettings(true, DEFAULT_THRESHOLD, DEFAULT_MIN_SPEECH_MS, DEFAULT_MIN_SILENCE_MS);
    public static final SileroVadSettings DISABLED =
            new SileroVadSettings(false, DEFAULT_THRESHOLD, DEFAULT_MIN_SPEECH_MS, DEFAULT_MIN_SILENCE_MS);

    public SileroVadSettings {
        if (!(threshold > 0f && threshold < 1f)) {
            throw new IllegalArgumentException("Silero VAD threshold must be between 0 and 1, got " + threshold);
        }
        requireDuration(minSpeechDurationMs, "min speech duration");
        requireDuration(minSilenceDurationMs, "min silence duration");
    }

    public int minSpeechWindows() {
        return wholeWindows(minSpeechDurationMs);
    }

    public int minSilenceWindows() {
        return wholeWindows(minSilenceDurationMs);
    }

    static int wholeWindows(int durationMs) {
        long samples = (long) durationMs * SpeechRecognizerEngine.SAMPLE_RATE / 1000;
        return (int) Math.max(1, samples / SpeechSegmenter.WINDOW_SAMPLES);
    }

    private static void requireDuration(int value, String name) {
        if (value < 1 || value > MAX_DURATION_MS) {
            throw new IllegalArgumentException("Silero VAD " + name + " must be 1.." + MAX_DURATION_MS + " ms, got "
                    + value);
        }
    }
}
