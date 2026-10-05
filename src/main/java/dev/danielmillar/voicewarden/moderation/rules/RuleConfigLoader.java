package dev.danielmillar.voicewarden.moderation.rules;

import org.bukkit.configuration.ConfigurationSection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owner-facing parsers. Invalid items produce warnings and do not prevent other rules loading. */
public final class RuleConfigLoader {
    private static final Pattern HEADER = Pattern.compile("\\[([A-Za-z0-9_]+)-([A-Za-z]+)]");

    private RuleConfigLoader() {
    }

    public record LoadResult(List<RuleDefinition> rules, Set<String> allowlist, List<String> warnings) {
        public LoadResult {
            rules = List.copyOf(rules);
            allowlist = Collections.unmodifiableSet(new LinkedHashSet<>(allowlist));
            warnings = List.copyOf(warnings);
        }
    }

    public static LoadResult loadRulesYaml(ConfigurationSection root) {
        List<RuleDefinition> rules = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (root == null) {
            return new LoadResult(rules, Set.of(), List.of("rules.yml: missing configuration"));
        }
        Set<String> allowlist = new LinkedHashSet<>(strings(root, "allowlist", "rules.yml", warnings));
        ConfigurationSection section = root.getConfigurationSection("rules");
        if (section == null) {
            warnings.add("rules.yml: missing or non-section 'rules'");
            return new LoadResult(rules, allowlist, warnings);
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection rule = section.getConfigurationSection(id);
            if (rule == null) {
                warnings.add("Rule '" + id + "': expected a configuration section; skipped");
                continue;
            }
            String context = "Rule '" + id + "'";
            RuleAction action = action(rule.getString("action"), context, warnings);
            int weight = rule.getInt("weight", 1);
            if (weight < 1) {
                warnings.add(context + ": weight must be at least 1; using 1");
                weight = 1;
            }
            String category = rule.getString("category");
            if (category == null || category.isBlank()) {
                category = title(id);
            }
            List<String> words = strings(rule, "words", context, warnings);
            List<String> phrases = strings(rule, "phrases", context, warnings);
            List<String> regex = strings(rule, "regex", context, warnings);
            List<String> allow = strings(rule, "allow", context, warnings);
            if (words.isEmpty() && phrases.isEmpty() && regex.isEmpty()) {
                warnings.add(context + ": no terms; skipped");
                continue;
            }
            rules.add(new RuleDefinition(id, category, action, weight, rule.getBoolean("enabled", true),
                    words, phrases, regex, allow));
        }
        return new LoadResult(rules, allowlist, warnings);
    }

    /** Reads without closing the caller-owned reader. Partial results survive an I/O failure. */
    public static LoadResult loadWordList(Reader reader, Set<String> enabledLanguages) {
        List<String> warnings = new ArrayList<>();
        if (reader == null) {
            return new LoadResult(List.of(), Set.of(), List.of("wordlist.txt: missing reader"));
        }
        Set<String> languages = new LinkedHashSet<>();
        if (enabledLanguages != null) {
            for (String language : enabledLanguages) {
                if (language != null && !language.isBlank()) {
                    languages.add(language.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, WordSection> sections = new LinkedHashMap<>();
        WordSection current = null;
        boolean headerSeen = false;
        int lineNumber = 0;
        BufferedReader buffered = reader instanceof BufferedReader br ? br : new BufferedReader(reader);
        try {
            String line;
            while ((line = buffered.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[")) {
                    headerSeen = true;
                    current = null;
                    Matcher matcher = HEADER.matcher(line);
                    if (!matcher.matches()) {
                        warnings.add("wordlist.txt line " + lineNumber + ": malformed section header; skipped");
                        continue;
                    }
                    String language = matcher.group(1).toLowerCase(Locale.ROOT);
                    String type = matcher.group(2).toUpperCase(Locale.ROOT);
                    if (!type.equals("PROFANITY") && !type.equals("MUTE")) {
                        warnings.add("wordlist.txt line " + lineNumber + ": unknown type '" + type + "'; skipped");
                        continue;
                    }
                    if (!languages.isEmpty() && !languages.contains(language)) {
                        continue;
                    }
                    String id = "wordlist-" + language + "-" + type.toLowerCase(Locale.ROOT);
                    current = sections.computeIfAbsent(id, ignored -> new WordSection(id, type));
                } else if (current != null) {
                    current.terms.add(line);
                } else if (!headerSeen) {
                    warnings.add("wordlist.txt line " + lineNumber + ": term before any section; skipped");
                }
            }
        } catch (IOException exception) {
            warnings.add("wordlist.txt line " + lineNumber + ": read failed: " + exception.getMessage());
        }
        List<RuleDefinition> rules = new ArrayList<>();
        for (WordSection section : sections.values()) {
            if (section.terms.isEmpty()) {
                warnings.add("Section '" + section.id + "': no terms; skipped");
                continue;
            }
            boolean mute = section.type.equals("MUTE");
            rules.add(new RuleDefinition(section.id, mute ? "Mute list" : "Profanity",
                    mute ? RuleAction.MUTE : RuleAction.FLAG, 1, true,
                    List.copyOf(section.terms), List.of(), List.of(), List.of()));
        }
        return new LoadResult(rules, Set.of(), warnings);
    }

    public static LoadResult fromOverrides(List<String> profanityWords, List<String> muteWords) {
        List<RuleDefinition> rules = new ArrayList<>(2);
        addOverride(rules, "config-profanity", "Profanity", RuleAction.FLAG, profanityWords);
        addOverride(rules, "config-mute", "Mute list", RuleAction.MUTE, muteWords);
        return new LoadResult(rules, Set.of(), List.of());
    }

    private static void addOverride(List<RuleDefinition> output, String id, String category,
                                    RuleAction action, List<String> source) {
        if (source == null) {
            return;
        }
        List<String> words = source.stream().filter(word -> word != null && !word.isBlank()).toList();
        if (!words.isEmpty()) {
            output.add(new RuleDefinition(id, category, action, 1, true, words, List.of(), List.of(), List.of()));
        }
    }

    private static RuleAction action(String value, String context, List<String> warnings) {
        if (value != null) {
            try {
                return RuleAction.valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Owner typo: keep the alerting rule, without enabling automatic muting.
            }
        }
        warnings.add(context + ": missing/unknown action '" + value + "'; using FLAG");
        return RuleAction.FLAG;
    }

    static String title(String id) {
        if (id == null || id.isBlank()) {
            return "Uncategorised";
        }
        String cleaned = id.replace('-', ' ').replace('_', ' ').strip();
        if (cleaned.isEmpty()) {
            return "Uncategorised";
        }
        String[] parts = cleaned.split("\\s+");
        StringBuilder title = new StringBuilder();
        for (String part : parts) {
            if (!title.isEmpty()) {
                title.append(' ');
            }
            String lower = part.toLowerCase(Locale.ROOT);
            int first = lower.codePointAt(0);
            title.appendCodePoint(Character.toTitleCase(first)).append(lower.substring(Character.charCount(first)));
        }
        return title.toString();
    }

    private static List<String> strings(ConfigurationSection section, String key,
                                         String context, List<String> warnings) {
        Object value = section.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            warnings.add(context + ": '" + key + "' must be a list; skipped");
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                warnings.add(context + ": empty/non-string entry in '" + key + "'; skipped");
            } else {
                result.add(text.strip());
            }
        }
        return result;
    }

    private static final class WordSection {
        private final String id;
        private final String type;
        private final Set<String> terms = new LinkedHashSet<>();

        private WordSection(String id, String type) {
            this.id = id;
            this.type = type;
        }
    }
}
