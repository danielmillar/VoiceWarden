package dev.voicewarden.core.wordlist;

import java.util.List;
import java.util.Objects;

public record ParsedRules(
        List<String> allowlist,
        List<RuleDefinition> rules,
        List<String> warnings) {

    public static final ParsedRules EMPTY = new ParsedRules(List.of(), List.of(), List.of());

    public ParsedRules {
        allowlist = List.copyOf(Objects.requireNonNull(allowlist, "allowlist"));
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    public List<RuleDefinition> enabledRules() {
        return rules.stream().filter(RuleDefinition::enabled).toList();
    }
}
