package dev.voicewarden.core.moderation;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public record MuteLadderSettings(
        boolean enabled,
        NavigableMap<Integer, Duration> tiers,
        Duration maxTierDuration,
        Duration resetAfter,
        Duration fallbackDuration) {

    public MuteLadderSettings {
        Objects.requireNonNull(tiers, "tiers");
        Objects.requireNonNull(maxTierDuration, "maxTierDuration");
        Objects.requireNonNull(resetAfter, "resetAfter");
        Objects.requireNonNull(fallbackDuration, "fallbackDuration");
        TreeMap<Integer, Duration> copy = new TreeMap<>();
        for (Map.Entry<Integer, Duration> tier : tiers.entrySet()) {
            if (tier.getKey() >= 1 && tier.getValue() != null && tier.getValue().isPositive()) {
                copy.put(tier.getKey(), tier.getValue());
            }
        }
        tiers = Collections.unmodifiableNavigableMap(copy);
        requirePositive(maxTierDuration, "maxTierDuration");
        requirePositive(resetAfter, "resetAfter");
        requirePositive(fallbackDuration, "fallbackDuration");
    }

    private static void requirePositive(Duration duration, String name) {
        if (!duration.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
