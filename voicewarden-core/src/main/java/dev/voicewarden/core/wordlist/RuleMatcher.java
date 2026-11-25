package dev.voicewarden.core.wordlist;

import dev.voicewarden.api.FlagSeverity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleMatcher {

    public static final Duration DEFAULT_REGEX_BUDGET = Duration.ofMillis(25);
    public static final String RULE_LANGUAGE_PREFIX = "rule:";

    private static final Comparator<WordMatch> MATCH_ORDER = Comparator.comparingInt(WordMatch::startIndex)
            .thenComparing(Comparator.comparingInt(WordMatch::endIndex).reversed())
            .thenComparing(WordMatch::word);

    private final AhoCorasickMatcher wordlistMatcher;
    private final List<CompiledRegexRule> regexRules;
    private final Map<String, List<String>> ruleAllows;
    private final long regexBudgetNanos;
    private final int activeRuleCount;
    private final int regexPatternCount;

    private RuleMatcher(
            AhoCorasickMatcher wordlistMatcher,
            List<CompiledRegexRule> regexRules,
            Map<String, List<String>> ruleAllows,
            long regexBudgetNanos,
            int activeRuleCount,
            int regexPatternCount) {
        this.wordlistMatcher = wordlistMatcher;
        this.regexRules = regexRules;
        this.ruleAllows = ruleAllows;
        this.regexBudgetNanos = regexBudgetNanos;
        this.activeRuleCount = activeRuleCount;
        this.regexPatternCount = regexPatternCount;
    }

    public static RuleMatcher empty() {
        return fromWordlist(AhoCorasickMatcher.empty());
    }

    public static RuleMatcher fromWordlist(AhoCorasickMatcher matcher) {
        return new RuleMatcher(Objects.requireNonNull(matcher, "matcher"), List.of(), Map.of(),
                DEFAULT_REGEX_BUDGET.toNanos(), 0, 0);
    }

    public static RuleMatcher compile(ParsedWordlist wordlist, WordlistConfig config, ParsedRules rules) {
        return compile(wordlist, config, rules, DEFAULT_REGEX_BUDGET);
    }

    public static RuleMatcher compile(ParsedWordlist wordlist, WordlistConfig config, ParsedRules rules, Duration regexBudget) {
        Objects.requireNonNull(wordlist, "wordlist");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(regexBudget, "regexBudget");
        List<WordlistEntry> merged = new ArrayList<>(wordlist.entries());
        Set<String> languages = new TreeSet<>(wordlist.languages());
        for (String allow : rules.allowlist()) {
            merged.add(new WordlistEntry(allow, WordlistTier.ALLOW, "rules"));
        }
        List<CompiledRegexRule> compiledRegex = new ArrayList<>();
        Map<String, List<String>> scopedAllows = new HashMap<>();
        int enabledCount = 0;
        int totalRegex = 0;
        for (RuleDefinition rule : rules.enabledRules()) {
            enabledCount++;
            WordlistTier tier = tierFor(rule.severity());
            String source = RULE_LANGUAGE_PREFIX + rule.id();
            for (String word : rule.words()) {
                merged.add(new WordlistEntry(word, tier, source));
            }
            for (String phrase : rule.phrases()) {
                merged.add(new WordlistEntry(phrase, tier, source));
                String folded = foldApostrophes(phrase);
                if (!folded.equals(phrase)) {
                    merged.add(new WordlistEntry(folded, tier, source));
                }
            }
            List<String> lowerAllows = rule.allow().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .toList();
            if (!lowerAllows.isEmpty()) {
                scopedAllows.put(source, lowerAllows);
            }
            if (!rule.regex().isEmpty()) {
                List<CompiledPattern> patterns = new ArrayList<>(rule.regex().size());
                for (String raw : rule.regex()) {
                    patterns.add(new CompiledPattern(Pattern.compile(raw, RulesParser.REGEX_FLAGS), RegexPrefilter.analyze(raw)));
                    totalRegex++;
                }
                List<Pattern> excludes = rule.excludeRegex().stream()
                        .map(raw -> Pattern.compile(raw, RulesParser.REGEX_FLAGS))
                        .toList();
                compiledRegex.add(new CompiledRegexRule(rule.id(), rule.category(), rule.severity(), patterns, excludes, lowerAllows));
            }
        }
        AhoCorasickMatcher unified = AhoCorasickMatcher.compile(new ParsedWordlist(merged, languages, wordlist.warnings()), config);
        return new RuleMatcher(unified, List.copyOf(compiledRegex), Map.copyOf(scopedAllows),
                Math.max(1_000_000L, regexBudget.toNanos()), enabledCount, totalRegex);
    }

    public AhoCorasickMatcher wordlistMatcher() {
        return wordlistMatcher;
    }

    public int flaggedPatternCount() {
        return wordlistMatcher.flaggedPatternCount();
    }

    public int allowPatternCount() {
        int scoped = 0;
        for (List<String> allows : ruleAllows.values()) {
            scoped += allows.size();
        }
        return wordlistMatcher.allowPatternCount() + scoped;
    }

    public int ruleCount() {
        return activeRuleCount;
    }

    public int regexPatternCount() {
        return regexPatternCount;
    }

    public WordlistMatchResult match(String text) {
        if (text == null || text.isEmpty()) {
            return WordlistMatchResult.EMPTY;
        }
        WordlistMatchResult direct = wordlistMatcher.match(text);
        List<WordMatch> combined = null;
        if (direct.hasMatches()) {
            combined = new ArrayList<>(direct.matches().size());
            for (WordMatch match : direct.matches()) {
                if (!isAllowedForSource(text, match)) {
                    combined.add(match);
                }
            }
        }
        if (hasInternalApostrophe(text)) {
            ApostropheFolded folded = foldInternalApostrophes(text);
            WordlistMatchResult foldedResult = wordlistMatcher.match(folded.text);
            if (foldedResult.hasMatches()) {
                if (combined == null) {
                    combined = new ArrayList<>(foldedResult.matches().size());
                }
                for (WordMatch match : foldedResult.matches()) {
                    int origStart = folded.starts[match.startIndex()];
                    int origEnd = folded.ends[match.endIndex() - 1];
                    WordMatch mapped = new WordMatch(match.word(), match.severity(), match.language(), origStart, origEnd);
                    if (!isAllowedForSource(text, mapped) && !containsSameMatch(combined, mapped)) {
                        combined.add(mapped);
                    }
                }
            }
        }
        if (!regexRules.isEmpty()) {
            List<WordMatch> regexMatches = matchRegexRules(text);
            if (!regexMatches.isEmpty()) {
                if (combined == null) {
                    combined = new ArrayList<>(regexMatches);
                } else {
                    for (WordMatch match : regexMatches) {
                        if (!containsSameMatch(combined, match)) {
                            combined.add(match);
                        }
                    }
                }
            }
        }
        if (combined == null || combined.isEmpty()) {
            return WordlistMatchResult.EMPTY;
        }
        combined.sort(MATCH_ORDER);
        return WordlistMatchResult.of(combined);
    }

    private List<WordMatch> matchRegexRules(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hasDigit = false;
        boolean hasDot = false;
        boolean hasAt = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (c == '.') {
                hasDot = true;
            } else if (c == '@') {
                hasAt = true;
            }
        }
        List<WordMatch> hits = new ArrayList<>();
        for (CompiledRegexRule rule : regexRules) {
            List<WordMatch> ruleHits = null;
            long deadline = System.nanoTime() + regexBudgetNanos;
            for (CompiledPattern compiled : rule.patterns) {
                if (!compiled.prefilter.mightMatch(lower, hasDigit, hasDot, hasAt)) {
                    continue;
                }
                if (System.nanoTime() - deadline > 0L) {
                    break;
                }
                BudgetedCharSequence bounded = new BudgetedCharSequence(text, deadline);
                Matcher matcher = compiled.pattern.matcher(bounded);
                try {
                    while (matcher.find()) {
                        int start = matcher.start();
                        int end = matcher.end();
                        if (end <= start) {
                            continue;
                        }
                        if (isAllowedByRule(text, start, end, rule.allows)) {
                            continue;
                        }
                        if (ruleHits == null) {
                            ruleHits = new ArrayList<>();
                        }
                        String matchedText = text.substring(start, end);
                        ruleHits.add(new WordMatch(matchedText.toLowerCase(Locale.ROOT), rule.severity,
                                RULE_LANGUAGE_PREFIX + rule.id, start, end));
                    }
                } catch (BudgetedCharSequence.BudgetExceededException ignored) {
                    break;
                }
            }
            if (ruleHits != null && !isExcludedByRule(text, rule.excludes, deadline)) {
                hits.addAll(ruleHits);
            }
        }
        return hits;
    }

    private boolean isExcludedByRule(String text, List<Pattern> excludes, long deadline) {
        if (excludes.isEmpty()) {
            return false;
        }
        for (Pattern exclude : excludes) {
            if (System.nanoTime() - deadline > 0L) {
                return false;
            }
            try {
                if (exclude.matcher(new BudgetedCharSequence(text, deadline)).find()) {
                    return true;
                }
            } catch (BudgetedCharSequence.BudgetExceededException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean isAllowedForSource(String text, WordMatch match) {
        List<String> allows = ruleAllows.get(match.language());
        return allows != null && isAllowedByRule(text, match.startIndex(), match.endIndex(), allows);
    }

    private static boolean isAllowedByRule(String text, int start, int end, List<String> allows) {
        if (allows.isEmpty()) {
            return false;
        }
        int textLen = text.length();
        for (String allow : allows) {
            int allowLen = allow.length();
            if (allowLen == 0 || allowLen < end - start || allowLen > textLen) {
                continue;
            }
            int minStart = Math.max(0, end - allowLen);
            int maxStart = Math.min(start, textLen - allowLen);
            for (int pos = minStart; pos <= maxStart; pos++) {
                if (text.regionMatches(true, pos, allow, 0, allowLen)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsSameMatch(List<WordMatch> existing, WordMatch candidate) {
        for (WordMatch item : existing) {
            if (item.startIndex() == candidate.startIndex() && item.endIndex() == candidate.endIndex()
                    && item.severity().isAtLeast(candidate.severity())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInternalApostrophe(String text) {
        int len = text.length();
        for (int i = 1; i < len - 1; i++) {
            char c = text.charAt(i);
            if ((c == '\'' || c == '\u2019')
                    && Character.isLetter(text.charAt(i - 1))
                    && Character.isLetter(text.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private static String foldApostrophes(String text) {
        return hasInternalApostrophe(text) ? foldInternalApostrophes(text).text : text;
    }

    private static ApostropheFolded foldInternalApostrophes(String text) {
        int len = text.length();
        StringBuilder sb = new StringBuilder(len);
        int[] starts = new int[len];
        int[] ends = new int[len];
        int size = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c == '\'' || c == '\u2019') && i > 0 && i + 1 < len
                    && Character.isLetter(text.charAt(i - 1))
                    && Character.isLetter(text.charAt(i + 1))) {
                if (size > 0) {
                    ends[size - 1] = i + 1;
                }
                continue;
            }
            sb.append(c);
            starts[size] = i;
            ends[size] = i + 1;
            size++;
        }
        return new ApostropheFolded(sb.toString(), starts, ends);
    }

    private static WordlistTier tierFor(FlagSeverity severity) {
        return switch (severity) {
            case MILD -> WordlistTier.PROFANITY;
            case MODERATE -> WordlistTier.MODERATE;
            case SEVERE -> WordlistTier.MUTE;
        };
    }

    private record ApostropheFolded(String text, int[] starts, int[] ends) {
    }

    private record CompiledPattern(Pattern pattern, RegexPrefilter prefilter) {
    }

    private record CompiledRegexRule(
            String id,
            String category,
            FlagSeverity severity,
            List<CompiledPattern> patterns,
            List<Pattern> excludes,
            List<String> allows) {
    }
}
