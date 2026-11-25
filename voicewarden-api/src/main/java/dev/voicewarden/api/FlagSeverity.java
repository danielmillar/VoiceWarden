package dev.voicewarden.api;

import java.util.Objects;

public enum FlagSeverity {
    MILD,
    MODERATE,
    SEVERE;

    public static FlagSeverity highest(FlagSeverity a, FlagSeverity b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return a.compareTo(b) >= 0 ? a : b;
    }

    public boolean isAtLeast(FlagSeverity other) {
        return compareTo(Objects.requireNonNull(other, "other")) >= 0;
    }
}
