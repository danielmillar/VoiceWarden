package dev.voicewarden.core.wordlist;

import java.util.Objects;

public record WordlistEntry(String term, WordlistTier tier, String language) {

    public WordlistEntry {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(language, "language");
        if (term.isBlank()) {
            throw new IllegalArgumentException("Wordlist term must not be blank");
        }
    }
}
