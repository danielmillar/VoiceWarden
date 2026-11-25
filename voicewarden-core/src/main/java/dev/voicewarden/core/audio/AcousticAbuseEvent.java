package dev.voicewarden.core.audio;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record AcousticAbuseEvent(UUID playerId, Kind kind, double peakRms, double clippingRatio, int durationMs, short[] pcm16k) {

    private static final double FULL_SCALE = 32_767.0;

    public enum Kind {
        CLIPPING,
        LOUDNESS;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public AcousticAbuseEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(pcm16k, "pcm16k");
    }

    public static double peakLevel(double rms) {
        return Math.max(0, Math.min(1, rms / FULL_SCALE));
    }
}
