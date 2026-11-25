package dev.voicewarden.core.wordlist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record WordlistConfig(
        List<String> enabledLanguages,
        boolean partialMatch,
        boolean caseSensitive,
        List<String> extraProfanityWords,
        List<String> extraModerateWords,
        List<String> extraMuteWords,
        List<String> extraAllowWords) {

    public static final String ALL_LANGUAGES = "*";
    public static final boolean DEFAULT_PARTIAL_MATCH = false;
    public static final boolean DEFAULT_CASE_SENSITIVE = false;

    public WordlistConfig {
        enabledLanguages = normalizeLanguages(enabledLanguages);
        extraProfanityWords = copy(extraProfanityWords);
        extraModerateWords = copy(extraModerateWords);
        extraMuteWords = copy(extraMuteWords);
        extraAllowWords = copy(extraAllowWords);
    }

    public static WordlistConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .enabledLanguages(enabledLanguages)
                .partialMatch(partialMatch)
                .caseSensitive(caseSensitive)
                .extraProfanityWords(extraProfanityWords)
                .extraModerateWords(extraModerateWords)
                .extraMuteWords(extraMuteWords)
                .extraAllowWords(extraAllowWords);
    }

    public boolean allLanguagesEnabled() {
        return enabledLanguages.isEmpty() || enabledLanguages.contains(ALL_LANGUAGES);
    }

    public boolean isLanguageEnabled(String language) {
        return allLanguagesEnabled() || enabledLanguages.contains(language.trim().toLowerCase(Locale.ROOT));
    }

    public List<String> extraWords(WordlistTier tier) {
        return switch (tier) {
            case PROFANITY -> extraProfanityWords;
            case MODERATE -> extraModerateWords;
            case MUTE -> extraMuteWords;
            case ALLOW -> extraAllowWords;
        };
    }

    private static List<String> normalizeLanguages(List<String> languages) {
        if (languages == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(languages.size());
        for (String language : languages) {
            if (language != null && !language.isBlank()) {
                String code = language.trim().toLowerCase(Locale.ROOT);
                if (!normalized.contains(code)) {
                    normalized.add(code);
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> copy(List<String> words) {
        if (words == null) {
            return List.of();
        }
        List<String> kept = new ArrayList<>(words.size());
        for (String word : words) {
            if (word != null && !word.isBlank()) {
                kept.add(word);
            }
        }
        return List.copyOf(kept);
    }

    public static final class Builder {
        private List<String> enabledLanguages = List.of();
        private boolean partialMatch = DEFAULT_PARTIAL_MATCH;
        private boolean caseSensitive = DEFAULT_CASE_SENSITIVE;
        private List<String> extraProfanityWords = List.of();
        private List<String> extraModerateWords = List.of();
        private List<String> extraMuteWords = List.of();
        private List<String> extraAllowWords = List.of();

        private Builder() {
        }

        public Builder enabledLanguages(List<String> value) {
            enabledLanguages = value;
            return this;
        }

        public Builder enabledLanguages(String... value) {
            return enabledLanguages(List.of(value));
        }

        public Builder partialMatch(boolean value) {
            partialMatch = value;
            return this;
        }

        public Builder caseSensitive(boolean value) {
            caseSensitive = value;
            return this;
        }

        public Builder extraProfanityWords(List<String> value) {
            extraProfanityWords = value;
            return this;
        }

        public Builder extraModerateWords(List<String> value) {
            extraModerateWords = value;
            return this;
        }

        public Builder extraMuteWords(List<String> value) {
            extraMuteWords = value;
            return this;
        }

        public Builder extraAllowWords(List<String> value) {
            extraAllowWords = value;
            return this;
        }

        public WordlistConfig build() {
            return new WordlistConfig(enabledLanguages, partialMatch, caseSensitive,
                    extraProfanityWords, extraModerateWords, extraMuteWords, extraAllowWords);
        }
    }
}
