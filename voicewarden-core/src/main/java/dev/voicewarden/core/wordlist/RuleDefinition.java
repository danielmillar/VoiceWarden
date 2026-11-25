package dev.voicewarden.core.wordlist;

import dev.voicewarden.api.FlagSeverity;
import java.util.List;
import java.util.Objects;

public record RuleDefinition(
        String id,
        boolean enabled,
        String category,
        FlagSeverity severity,
        List<String> words,
        List<String> phrases,
        List<String> regex,
        List<String> excludeRegex,
        List<String> allow) {

    public RuleDefinition {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Rule id must not be blank");
        }
        category = category == null || category.isBlank() ? id : category.strip();
        Objects.requireNonNull(severity, "severity");
        words = List.copyOf(Objects.requireNonNull(words, "words"));
        phrases = List.copyOf(Objects.requireNonNull(phrases, "phrases"));
        regex = List.copyOf(Objects.requireNonNull(regex, "regex"));
        excludeRegex = List.copyOf(Objects.requireNonNull(excludeRegex, "excludeRegex"));
        allow = List.copyOf(Objects.requireNonNull(allow, "allow"));
    }
}
