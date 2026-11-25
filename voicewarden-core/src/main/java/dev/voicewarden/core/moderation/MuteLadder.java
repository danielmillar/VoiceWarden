package dev.voicewarden.core.moderation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class MuteLadder {

    private final MuteLadderSettings settings;

    public MuteLadder(MuteLadderSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public MuteLadderSettings settings() {
        return settings;
    }

    public Instant ladderWindowStart(Instant now) {
        return now.minus(settings.resetAfter());
    }

    public Duration durationFor(int priorMutes) {
        if (!settings.enabled() || settings.tiers().isEmpty()) {
            return settings.fallbackDuration();
        }
        int tier = Math.max(0, priorMutes) + 1;
        Map.Entry<Integer, Duration> entry = settings.tiers().floorEntry(tier);
        Duration duration = entry != null ? entry.getValue() : settings.tiers().firstEntry().getValue();
        return duration.compareTo(settings.maxTierDuration()) > 0 ? settings.maxTierDuration() : duration;
    }
}
