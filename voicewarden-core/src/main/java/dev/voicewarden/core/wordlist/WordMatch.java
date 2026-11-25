package dev.voicewarden.core.wordlist;

import dev.voicewarden.api.FlagSeverity;
import java.util.Objects;

public record WordMatch(String word, FlagSeverity severity, String language, int startIndex, int endIndex) {

    public WordMatch {
        Objects.requireNonNull(word, "word");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(language, "language");
        if (startIndex < 0 || endIndex <= startIndex) {
            throw new IllegalArgumentException("Invalid match span [" + startIndex + ", " + endIndex + ")");
        }
    }

    public int length() {
        return endIndex - startIndex;
    }
}
