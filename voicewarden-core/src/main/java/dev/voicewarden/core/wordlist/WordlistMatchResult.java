package dev.voicewarden.core.wordlist;

import dev.voicewarden.api.FlagSeverity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record WordlistMatchResult(List<WordMatch> matches, FlagSeverity highestSeverity, List<String> matchedWords) {

    public static final WordlistMatchResult EMPTY = new WordlistMatchResult(List.of(), null, List.of());

    public WordlistMatchResult {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        matchedWords = List.copyOf(Objects.requireNonNull(matchedWords, "matchedWords"));
    }

    public static WordlistMatchResult of(List<WordMatch> matches) {
        if (matches.isEmpty()) {
            return EMPTY;
        }
        FlagSeverity highest = null;
        Set<String> words = new LinkedHashSet<>();
        for (WordMatch match : matches) {
            highest = highest == null ? match.severity() : FlagSeverity.highest(highest, match.severity());
            words.add(match.word());
        }
        return new WordlistMatchResult(matches, highest, new ArrayList<>(words));
    }

    public boolean hasMatches() {
        return !matches.isEmpty();
    }
}
