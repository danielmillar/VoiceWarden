package dev.voicewarden.core.wordlist;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ParsedWordlist(List<WordlistEntry> entries, Set<String> languages, List<String> warnings) {

    public static final ParsedWordlist EMPTY = new ParsedWordlist(List.of(), Set.of(), List.of());

    public ParsedWordlist {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        languages = Set.copyOf(Objects.requireNonNull(languages, "languages"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    public List<WordlistEntry> entries(WordlistTier tier) {
        return entries.stream().filter(entry -> entry.tier() == tier).toList();
    }

    public List<WordlistEntry> entries(String language, WordlistTier tier) {
        return entries.stream()
                .filter(entry -> entry.tier() == tier && entry.language().equals(language))
                .toList();
    }

    public int size() {
        return entries.size();
    }
}
