package dev.voicewarden.core.moderation;

import dev.voicewarden.api.FlagSeverity;
import java.util.Objects;

public record MatchedWord(String word, FlagSeverity severity) {

    public MatchedWord {
        Objects.requireNonNull(word, "word");
        Objects.requireNonNull(severity, "severity");
    }
}
