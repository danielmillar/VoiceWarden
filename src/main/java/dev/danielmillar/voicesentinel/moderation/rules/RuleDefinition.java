package dev.danielmillar.voicesentinel.moderation.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Uncompiled owner configuration. Lists are immutable snapshots; null lists mean no entries. */
public record RuleDefinition(String id, String category, RuleAction action, int weight, boolean enabled,
                             List<String> words, List<String> phrases, List<String> regex, List<String> allow) {
    public RuleDefinition {
        words = snapshot(words);
        phrases = snapshot(phrases);
        regex = snapshot(regex);
        allow = snapshot(allow);
    }

    private static List<String> snapshot(List<String> source) {
        // Retain malformed/null entries so compile() can report them instead of failing construction.
        return source == null || source.isEmpty() ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(source));
    }
}
