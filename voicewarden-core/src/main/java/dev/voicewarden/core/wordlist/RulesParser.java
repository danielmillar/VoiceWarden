package dev.voicewarden.core.wordlist;

import dev.voicewarden.api.FlagSeverity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

public final class RulesParser {

    public static final String DEFAULT_RESOURCE = "/default-rules.yml";
    public static final int MAX_REGEX_LENGTH = 1024;
    static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final String DEFAULT_RULES = loadDefaultRules();

    private RulesParser() {
    }

    public static String defaultRulesResource() {
        return DEFAULT_RULES;
    }

    private static String loadDefaultRules() {
        try (InputStream in = RulesParser.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled rules " + DEFAULT_RESOURCE + " is missing from the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled rules " + DEFAULT_RESOURCE, e);
        }
    }

    public static ParsedRules parseDefault() {
        return parse(defaultRulesResource());
    }

    public static ParsedRules parse(String yamlContent) {
        Objects.requireNonNull(yamlContent, "yamlContent");
        if (yamlContent.isBlank()) {
            return ParsedRules.EMPTY;
        }
        List<String> warnings = new ArrayList<>();
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlContent);
        } catch (YAMLException e) {
            String message = e.getMessage() == null ? "parse error" : e.getMessage().lines().findFirst().orElse("parse error");
            return new ParsedRules(List.of(), List.of(), List.of("could not be read as YAML (" + message + ")"));
        }
        if (loaded == null) {
            return ParsedRules.EMPTY;
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            return new ParsedRules(List.of(), List.of(), List.of("root of rules.yml must be a YAML mapping"));
        }
        List<String> allowlist = readStringList(root.get("allowlist"), "allowlist", warnings);
        List<RuleDefinition> rules = new ArrayList<>();
        Object rawRules = root.get("rules");
        if (rawRules instanceof Map<?, ?> rulesMap) {
            for (Map.Entry<?, ?> entry : rulesMap.entrySet()) {
                String id = String.valueOf(entry.getKey()).strip();
                if (id.isEmpty()) {
                    warnings.add("rules: skipping rule with blank id");
                    continue;
                }
                if (!(entry.getValue() instanceof Map<?, ?> ruleMap)) {
                    warnings.add("rules." + id + ": expected a mapping; skipping rule");
                    continue;
                }
                RuleDefinition rule = parseRule(id, ruleMap, warnings);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        } else if (rawRules != null) {
            warnings.add("rules: expected a mapping of rule definitions");
        }
        return new ParsedRules(allowlist, rules, warnings);
    }

    private static RuleDefinition parseRule(String id, Map<?, ?> map, List<String> warnings) {
        String prefix = "rules." + id;
        boolean enabled = readBoolean(map.get("enabled"), true, prefix + ".enabled", warnings);
        String category = readString(map.get("category"), id);
        FlagSeverity severity = parseSeverity(map.get("severity"), map.get("action"), prefix, warnings);
        if (severity == null) {
            return null;
        }
        List<String> words = readStringList(map.get("words"), prefix + ".words", warnings);
        List<String> phrases = readStringList(map.get("phrases"), prefix + ".phrases", warnings);
        List<String> regex = validateRegexList(readStringList(map.get("regex"), prefix + ".regex", warnings), prefix + ".regex", warnings);
        List<String> excludeRegex = validateRegexList(
                readStringList(map.get("exclude_regex"), prefix + ".exclude_regex", warnings), prefix + ".exclude_regex", warnings);
        List<String> allow = readStringList(map.get("allow"), prefix + ".allow", warnings);
        return new RuleDefinition(id, enabled, category, severity, words, phrases, regex, excludeRegex, allow);
    }

    private static FlagSeverity parseSeverity(Object rawSeverity, Object rawAction, String prefix, List<String> warnings) {
        if (rawSeverity != null) {
            String value = String.valueOf(rawSeverity).strip().toUpperCase(Locale.ROOT);
            return switch (value) {
                case "MILD", "PROFANITY", "FLAG", "LOW" -> FlagSeverity.MILD;
                case "MODERATE", "WARN", "MEDIUM" -> FlagSeverity.MODERATE;
                case "SEVERE", "MUTE", "HIGH" -> FlagSeverity.SEVERE;
                default -> {
                    warnings.add(prefix + ".severity: unknown severity '" + rawSeverity + "', expected MILD, MODERATE or SEVERE; skipping rule");
                    yield null;
                }
            };
        }
        if (rawAction != null) {
            String value = String.valueOf(rawAction).strip().toUpperCase(Locale.ROOT);
            return switch (value) {
                case "FLAG", "REPORT" -> FlagSeverity.MILD;
                case "WARN" -> FlagSeverity.MODERATE;
                case "MUTE" -> FlagSeverity.SEVERE;
                default -> {
                    warnings.add(prefix + ".action: unknown action '" + rawAction + "'; skipping rule");
                    yield null;
                }
            };
        }
        return FlagSeverity.MILD;
    }

    private static List<String> validateRegexList(List<String> patterns, String path, List<String> warnings) {
        List<String> valid = new ArrayList<>(patterns.size());
        for (String raw : patterns) {
            if (raw.length() > MAX_REGEX_LENGTH) {
                warnings.add(path + ": pattern exceeds " + MAX_REGEX_LENGTH + " characters; skipping");
                continue;
            }
            try {
                Pattern.compile(raw, REGEX_FLAGS);
                valid.add(raw);
            } catch (PatternSyntaxException e) {
                warnings.add(path + ": invalid regex '" + raw + "' (" + e.getDescription() + "); skipping");
            }
        }
        return valid;
    }

    private static boolean readBoolean(Object raw, boolean fallback, String path, List<String> warnings) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = String.valueOf(raw).strip().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "no".equals(text)) {
            return false;
        }
        warnings.add(path + ": expected true or false but found '" + raw + "'; using " + fallback);
        return fallback;
    }

    private static String readString(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw).strip();
        return text.isEmpty() ? fallback : text;
    }

    private static List<String> readStringList(Object raw, String path, List<String> warnings) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    String text = String.valueOf(item).strip();
                    if (!text.isEmpty()) {
                        out.add(text);
                    }
                }
            }
            return out;
        }
        if (raw instanceof String single) {
            String trimmed = single.strip();
            return trimmed.isEmpty() ? List.of() : List.of(trimmed);
        }
        warnings.add(path + ": expected a list of strings; ignoring");
        return List.of();
    }
}
