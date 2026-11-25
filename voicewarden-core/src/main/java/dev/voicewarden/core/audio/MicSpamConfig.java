package dev.voicewarden.core.audio;

import dev.voicewarden.api.VoiceAction;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record MicSpamConfig(
        boolean enabled,
        int clippingSampleThreshold,
        double clippingRatio,
        double rmsThreshold,
        int minDurationMs,
        int cooldownSeconds,
        List<VoiceAction> escalation,
        int windowMinutes,
        Duration muteDuration,
        boolean notifyDiscord) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_CLIPPING_SAMPLE_THRESHOLD = 28_000;
    public static final double DEFAULT_CLIPPING_RATIO = 0.12;
    public static final double DEFAULT_RMS_THRESHOLD = 15_000.0;
    public static final int DEFAULT_MIN_DURATION_MS = 800;
    public static final int DEFAULT_COOLDOWN_SECONDS = 15;
    public static final List<VoiceAction> DEFAULT_ESCALATION = List.of(VoiceAction.WARN, VoiceAction.MUTE);
    public static final int DEFAULT_WINDOW_MINUTES = 5;
    public static final Duration DEFAULT_MUTE_DURATION = Duration.ofMinutes(5);
    public static final boolean DEFAULT_NOTIFY_DISCORD = true;

    public static final int MIN_CLIPPING_SAMPLE_THRESHOLD = 1_000;
    public static final int MAX_CLIPPING_SAMPLE_THRESHOLD = 32_767;
    public static final double MIN_CLIPPING_RATIO = 0.01;
    public static final double MAX_CLIPPING_RATIO = 1.0;
    public static final double MIN_RMS_THRESHOLD = 1_000.0;
    public static final double MAX_RMS_THRESHOLD = 32_767.0;
    public static final int MIN_DURATION_MS = 100;
    public static final int MAX_DURATION_MS = 10_000;
    public static final int MAX_COOLDOWN_SECONDS = 3_600;
    public static final int MIN_WINDOW_MINUTES = 1;
    public static final int MAX_WINDOW_MINUTES = 1_440;

    public MicSpamConfig {
        clippingSampleThreshold = Math.max(MIN_CLIPPING_SAMPLE_THRESHOLD, Math.min(MAX_CLIPPING_SAMPLE_THRESHOLD, clippingSampleThreshold));
        clippingRatio = clamp(clippingRatio, MIN_CLIPPING_RATIO, MAX_CLIPPING_RATIO, DEFAULT_CLIPPING_RATIO);
        rmsThreshold = clamp(rmsThreshold, MIN_RMS_THRESHOLD, MAX_RMS_THRESHOLD, DEFAULT_RMS_THRESHOLD);
        minDurationMs = Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, minDurationMs));
        cooldownSeconds = Math.max(0, Math.min(MAX_COOLDOWN_SECONDS, cooldownSeconds));
        escalation = escalation == null || escalation.isEmpty() ? DEFAULT_ESCALATION : List.copyOf(escalation);
        windowMinutes = Math.max(MIN_WINDOW_MINUTES, Math.min(MAX_WINDOW_MINUTES, windowMinutes));
        Objects.requireNonNull(muteDuration, "muteDuration");
        if (!muteDuration.isPositive()) {
            muteDuration = DEFAULT_MUTE_DURATION;
        }
    }

    public static MicSpamConfig defaults() {
        return new MicSpamConfig(
                DEFAULT_ENABLED,
                DEFAULT_CLIPPING_SAMPLE_THRESHOLD,
                DEFAULT_CLIPPING_RATIO,
                DEFAULT_RMS_THRESHOLD,
                DEFAULT_MIN_DURATION_MS,
                DEFAULT_COOLDOWN_SECONDS,
                DEFAULT_ESCALATION,
                DEFAULT_WINDOW_MINUTES,
                DEFAULT_MUTE_DURATION,
                DEFAULT_NOTIFY_DISCORD);
    }

    public VoiceAction action() {
        return escalation.getFirst();
    }

    public VoiceAction actionForHit(int hitNumber) {
        int index = Math.min(Math.max(0, hitNumber - 1), escalation.size() - 1);
        return escalation.get(index);
    }

    public Duration cooldown() {
        return Duration.ofSeconds(cooldownSeconds);
    }

    public Duration window() {
        return Duration.ofMinutes(windowMinutes);
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
