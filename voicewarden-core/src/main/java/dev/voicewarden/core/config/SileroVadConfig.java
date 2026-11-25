package dev.voicewarden.core.config;

public record SileroVadConfig(boolean enabled, double threshold, int minSpeechDurationMs, int minSilenceDurationMs) {

    public static final double MIN_THRESHOLD = 0.05;
    public static final double MAX_THRESHOLD = 0.95;
    public static final int MIN_DURATION_MS = 32;
    public static final int MAX_SPEECH_DURATION_MS = 2_000;
    public static final int MAX_SILENCE_DURATION_MS = 5_000;
}
