package dev.voicewarden.core.wordlist;

import dev.voicewarden.api.FlagSeverity;
import java.util.Locale;
import java.util.Optional;

public enum WordlistTier {
    PROFANITY(FlagSeverity.MILD),
    MODERATE(FlagSeverity.MODERATE),
    MUTE(FlagSeverity.SEVERE),
    ALLOW(null);

    private final FlagSeverity severity;

    WordlistTier(FlagSeverity severity) {
        this.severity = severity;
    }

    public Optional<FlagSeverity> severity() {
        return Optional.ofNullable(severity);
    }

    public boolean isAllow() {
        return severity == null;
    }

    public static Optional<WordlistTier> fromSectionName(String name) {
        String upper = name.trim().toUpperCase(Locale.ROOT);
        for (WordlistTier tier : values()) {
            if (tier.name().equals(upper)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
