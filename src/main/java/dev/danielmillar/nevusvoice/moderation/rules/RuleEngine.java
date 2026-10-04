package dev.danielmillar.nevusvoice.moderation.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable, reusable transcript matcher. Compile on configuration reload, then share freely between
 * workers. Matching never mutates the engine; regex timeouts are local to the current invocation.
 */
public final class RuleEngine {
    private static final long REGEX_BUDGET_NANOS = 50_000_000L;
    private static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            | Pattern.UNICODE_CHARACTER_CLASS;
    private static final RuleEngine EMPTY = new Builder().finish();
    private static final Comparator<Hit> PRIORITY = Comparator
            .comparingInt((Hit hit) -> hit.rule.action == RuleAction.MUTE ? 0 : 1)
            .thenComparing(Comparator.comparingInt((Hit hit) -> hit.rule.weight).reversed())
            .thenComparing(Comparator.comparingInt((Hit hit) -> hit.end - hit.start).reversed())
            .thenComparingInt(Hit::start)
            .thenComparing(hit -> hit.rule.id)
            .thenComparing(hit -> hit.rule.category);

    private final Map<String, List<CompiledRule>> exactWords;
    private final Trie prefixes;
    private final Trie suffixes;
    private final SubstringTerm[] substrings;
    private final Map<String, List<Phrase>> phrasesByFirst;
    private final Phrase[] wildcardPhrases;
    private final RegexTerm[] regex;
    private final Set<String> globalAllowlist;
    private final int ruleCount;
    private final int termCount;
    private final boolean hasWordPatterns;
    private final List<String> warnings;

    private RuleEngine(Builder builder) {
        exactWords = freezeLists(builder.exactWords);
        prefixes = Trie.freeze(builder.prefixes);
        suffixes = Trie.freeze(builder.suffixes);
        substrings = builder.substrings.entrySet().stream()
                .map(entry -> new SubstringTerm(entry.getKey(), List.copyOf(entry.getValue())))
                .toArray(SubstringTerm[]::new);
        phrasesByFirst = freezeLists(builder.phrasesByFirst);
        wildcardPhrases = builder.wildcardPhrases.toArray(Phrase[]::new);
        regex = builder.regex.toArray(RegexTerm[]::new);
        globalAllowlist = Set.copyOf(builder.globalAllowlist);
        ruleCount = builder.ruleCount;
        termCount = builder.termCount;
        hasWordPatterns = !exactWords.isEmpty() || prefixes.children.length != 0
                || suffixes.children.length != 0 || substrings.length != 0;
        warnings = List.copyOf(builder.warnings);
    }

    public static RuleEngine compile(Collection<RuleDefinition> rules, Collection<String> globalAllowlist) {
        Builder builder = new Builder();
        builder.globalAllowlist.addAll(allowWords(globalAllowlist, "Global allowlist", builder.warnings));
        if (rules == null) {
            builder.warnings.add("Missing rule collection; using no rules");
        } else {
            for (RuleDefinition definition : rules) {
                builder.add(definition);
            }
        }
        return builder.finish();
    }

    public static RuleEngine empty() {
        return EMPTY;
    }

    public int ruleCount() {
        return ruleCount;
    }

    /** Number of distinct, successfully compiled terms within enabled rules. */
    public int termCount() {
        return termCount;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Returns immutable, non-overlapping matches in original-transcript order. */
    public List<RuleMatch> match(String transcript) {
        if (transcript == null || ruleCount == 0 || transcript.isBlank()) {
            return List.of();
        }
        List<RuleText.Token> tokens = hasWordPatterns || !phrasesByFirst.isEmpty() || wildcardPhrases.length != 0
                ? RuleText.tokens(transcript) : List.of();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            RuleText.Token token = tokens.get(i);
            if (hasWordPatterns) {
                matchWord(token.text(), tokens, i, i, hits);
            }
            List<Phrase> indexed = phrasesByFirst.get(token.text());
            if (indexed != null) {
                for (Phrase phrase : indexed) {
                    matchPhrase(phrase, tokens, i, hits);
                }
            }
            for (Phrase phrase : wildcardPhrases) {
                matchPhrase(phrase, tokens, i, hits);
            }
            if (hasWordPatterns && i + 1 < tokens.size()) {
                RuleText.Token next = tokens.get(i + 1);
                if (next.start() == token.end() + 1 && transcript.charAt(token.end()) == '-') {
                    matchWord(token.text() + next.text(), tokens, i, i + 1, hits);
                }
            }
        }
        if (hasWordPatterns) {
            matchSpelledWords(tokens, hits);
        }
        for (RegexTerm term : regex) {
            matchRegex(term, transcript, hits);
        }
        return deduplicate(transcript, hits);
    }

    private void matchWord(String text, List<RuleText.Token> tokens, int first, int last, List<Hit> hits) {
        if (globalAllowlist.contains(text)) {
            return;
        }
        for (int i = first; i <= last; i++) {
            if (globalAllowlist.contains(tokens.get(i).text())) {
                return;
            }
        }
        List<CompiledRule> exact = exactWords.get(text);
        if (exact != null) {
            addWordHits(exact, text, tokens, first, last, hits);
        }
        matchTrie(prefixes, false, text, tokens, first, last, hits);
        matchTrie(suffixes, true, text, tokens, first, last, hits);
        for (SubstringTerm term : substrings) {
            if (text.contains(term.text)) {
                addWordHits(term.rules, text, tokens, first, last, hits);
            }
        }
    }

    private void matchTrie(Trie root, boolean reversed, String text, List<RuleText.Token> tokens,
                           int first, int last, List<Hit> hits) {
        Trie node = root;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(reversed ? text.length() - 1 - i : i);
            node = node.child(character);
            if (node == null) {
                return;
            }
            if (!node.rules.isEmpty()) {
                addWordHits(node.rules, text, tokens, first, last, hits);
            }
        }
    }

    private void addWordHits(List<CompiledRule> rules, String text, List<RuleText.Token> tokens,
                             int first, int last, List<Hit> hits) {
        for (CompiledRule rule : rules) {
            if (rule.allow.contains(text)) {
                continue;
            }
            boolean allowed = false;
            for (int i = first; i <= last; i++) {
                if (rule.allow.contains(tokens.get(i).text())) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                hits.add(new Hit(rule, tokens.get(first).start(), tokens.get(last).end()));
            }
        }
    }

    private void matchPhrase(Phrase phrase, List<RuleText.Token> tokens, int first, List<Hit> hits) {
        if (tokens.size() - first < phrase.parts.size()) {
            return;
        }
        for (int offset = 0; offset < phrase.parts.size(); offset++) {
            String text = tokens.get(first + offset).text();
            if (globalAllowlist.contains(text) || phrase.rule.allow.contains(text)
                    || !phrase.parts.get(offset).matches(text)) {
                return;
            }
        }
        hits.add(new Hit(phrase.rule, tokens.get(first).start(), tokens.get(first + phrase.parts.size() - 1).end()));
    }

    private void matchSpelledWords(List<RuleText.Token> tokens, List<Hit> hits) {
        int cursor = 0;
        while (cursor < tokens.size()) {
            if (!tokens.get(cursor).singleLetter()) {
                cursor++;
                continue;
            }
            int end = cursor + 1;
            while (end < tokens.size() && tokens.get(end).singleLetter()) {
                end++;
            }
            // Check sub-runs too: a neighbouring pronoun ("I K Y S") must not hide a spelled word.
            for (int first = cursor; first < end - 1; first++) {
                StringBuilder joined = new StringBuilder(tokens.get(first).text());
                for (int last = first + 1; last < end; last++) {
                    joined.append(tokens.get(last).text());
                    matchWord(joined.toString(), tokens, first, last, hits);
                }
            }
            cursor = end;
        }
    }

    private static void matchRegex(RegexTerm term, String transcript, List<Hit> hits) {
        int checkpoint = hits.size();
        DeadlineText text = new DeadlineText(transcript, 0, transcript.length(), System.nanoTime() + REGEX_BUDGET_NANOS);
        try {
            Matcher matcher = term.pattern.matcher(text);
            while (true) {
                text.checkDeadline();
                if (!matcher.find()) {
                    break;
                }
                text.checkDeadline();
                if (matcher.end() > matcher.start()) {
                    hits.add(new Hit(term.rule, matcher.start(), matcher.end()));
                }
            }
            text.checkDeadline();
        } catch (RuntimeException | StackOverflowError ignored) {
            // No partial results: an expensive/malformed regex must not take down a worker or
            // report an earlier hit when the rest of that same regex did not finish safely.
            hits.subList(checkpoint, hits.size()).clear();
        }
    }

    private static List<RuleMatch> deduplicate(String transcript, List<Hit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        hits.sort(PRIORITY);
        TreeMap<Integer, Hit> accepted = new TreeMap<>();
        for (Hit hit : hits) {
            Map.Entry<Integer, Hit> before = accepted.floorEntry(hit.start);
            if (before != null && before.getValue().end > hit.start) {
                continue;
            }
            Map.Entry<Integer, Hit> after = accepted.ceilingEntry(hit.start);
            if (after != null && after.getKey() < hit.end) {
                continue;
            }
            accepted.put(hit.start, hit);
        }
        List<RuleMatch> result = new ArrayList<>(accepted.size());
        for (Hit hit : accepted.values()) {
            result.add(new RuleMatch(hit.rule.id, hit.rule.category, hit.rule.action, hit.rule.weight,
                    transcript.substring(hit.start, hit.end), hit.start, hit.end));
        }
        return List.copyOf(result);
    }

    private static Set<String> allowWords(Collection<String> entries, String context, List<String> warnings) {
        Set<String> result = new HashSet<>();
        if (entries == null) {
            return result;
        }
        for (String entry : entries) {
            List<RuleText.Token> tokens = RuleText.tokens(entry);
            if (entry == null || entry.indexOf('*') >= 0 || entry.indexOf('＊') >= 0 || tokens.size() != 1) {
                warnings.add(context + ": expected an exact word, got '" + entry + "'; skipped");
            } else {
                result.add(tokens.getFirst().text());
            }
        }
        return result;
    }

    private static <T> Map<String, List<T>> freezeLists(Map<String, List<T>> source) {
        Map<String, List<T>> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private enum Mode { EXACT, PREFIX, SUFFIX, SUBSTRING }

    private record WordPattern(String text, Mode mode) {
        boolean matches(String candidate) {
            return switch (mode) {
                case EXACT -> candidate.equals(text);
                case PREFIX -> candidate.startsWith(text);
                case SUFFIX -> candidate.endsWith(text);
                case SUBSTRING -> candidate.contains(text);
            };
        }
    }

    private record CompiledRule(String id, String category, RuleAction action, int weight, Set<String> allow) { }
    private record Hit(CompiledRule rule, int start, int end) { }
    private record Phrase(CompiledRule rule, List<WordPattern> parts) { }
    private record SubstringTerm(String text, List<CompiledRule> rules) { }
    private record RegexTerm(CompiledRule rule, Pattern pattern) { }

    private static final class Builder {
        private final Map<String, List<CompiledRule>> exactWords = new HashMap<>();
        private final MutableTrie prefixes = new MutableTrie();
        private final MutableTrie suffixes = new MutableTrie();
        private final Map<String, List<CompiledRule>> substrings = new LinkedHashMap<>();
        private final Map<String, List<Phrase>> phrasesByFirst = new HashMap<>();
        private final List<Phrase> wildcardPhrases = new ArrayList<>();
        private final List<RegexTerm> regex = new ArrayList<>();
        private final Set<String> globalAllowlist = new HashSet<>();
        private final Set<String> ids = new HashSet<>();
        private final List<String> warnings = new ArrayList<>();
        private int ruleCount;
        private int termCount;

        private RuleEngine finish() {
            return new RuleEngine(this);
        }

        private void add(RuleDefinition definition) {
            if (definition == null) {
                warnings.add("Null rule; skipped");
                return;
            }
            if (!definition.enabled()) {
                return;
            }
            if (definition.id() == null || definition.id().isBlank()) {
                warnings.add("Rule has no id; skipped");
                return;
            }
            String id = definition.id().strip();
            String context = "Rule '" + id + "'";
            if (ids.contains(id)) {
                warnings.add(context + ": duplicate id; skipped");
                return;
            }
            RuleAction action = definition.action();
            if (action == null) {
                warnings.add(context + ": missing action; using FLAG");
                action = RuleAction.FLAG;
            }
            int weight = definition.weight();
            if (weight < 1) {
                warnings.add(context + ": weight must be at least 1; using 1");
                weight = 1;
            }
            String category = definition.category();
            if (category == null || category.isBlank()) {
                category = RuleConfigLoader.title(id);
            }
            CompiledRule rule = new CompiledRule(id, category, action, weight,
                    Set.copyOf(allowWords(definition.allow(), context + " allowlist", warnings)));
            Set<Object> seen = new HashSet<>();
            int previousCount = termCount;
            for (String entry : definition.words()) {
                addPattern(rule, entry, seen);
            }
            for (String entry : definition.phrases()) {
                addPattern(rule, entry, seen);
            }
            for (String entry : definition.regex()) {
                if (entry == null || entry.isBlank()) {
                    warnings.add(context + ": empty regex; skipped");
                    continue;
                }
                if (!seen.add(entry)) {
                    continue;
                }
                try {
                    regex.add(new RegexTerm(rule, Pattern.compile(entry, REGEX_FLAGS)));
                    termCount++;
                } catch (PatternSyntaxException | StackOverflowError exception) {
                    warnings.add(context + ": invalid regex '" + entry + "'; skipped ("
                            + (exception instanceof PatternSyntaxException syntax ? syntax.getDescription()
                            : "excessive nesting") + ")");
                }
            }
            if (termCount == previousCount) {
                warnings.add(context + ": no valid terms; skipped");
            } else {
                ids.add(id);
                ruleCount++;
            }
        }

        private void addPattern(CompiledRule rule, String entry, Set<Object> seen) {
            List<WordPattern> parts;
            try {
                parts = parsePatterns(entry);
            } catch (IllegalArgumentException exception) {
                warnings.add("Rule '" + rule.id + "': invalid term '" + entry + "'; skipped ("
                        + exception.getMessage() + ")");
                return;
            }
            if (!seen.add(parts)) {
                return;
            }
            termCount++;
            WordPattern first = parts.getFirst();
            if (parts.size() == 1) {
                switch (first.mode) {
                    case EXACT -> exactWords.computeIfAbsent(first.text, ignored -> new ArrayList<>()).add(rule);
                    case PREFIX -> prefixes.add(first.text, false, rule);
                    case SUFFIX -> suffixes.add(first.text, true, rule);
                    case SUBSTRING -> substrings.computeIfAbsent(first.text, ignored -> new ArrayList<>()).add(rule);
                }
            } else {
                Phrase phrase = new Phrase(rule, parts);
                if (first.mode == Mode.EXACT) {
                    phrasesByFirst.computeIfAbsent(first.text, ignored -> new ArrayList<>()).add(phrase);
                } else {
                    wildcardPhrases.add(phrase);
                }
            }
        }
    }

    private static List<WordPattern> parsePatterns(String entry) {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("empty pattern");
        }
        String normalized = RuleText.normalize(entry);
        List<WordPattern> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < normalized.length()) {
            int cp = normalized.codePointAt(cursor);
            if (!Character.isLetterOrDigit(cp) && cp != '*') {
                cursor += Character.charCount(cp);
                continue;
            }
            StringBuilder token = new StringBuilder();
            while (cursor < normalized.length()) {
                cp = normalized.codePointAt(cursor);
                if (Character.isLetterOrDigit(cp) || cp == '*') {
                    token.appendCodePoint(cp);
                    cursor += Character.charCount(cp);
                } else if (RuleText.apostrophe(cp) && !token.isEmpty()
                        && Character.isLetterOrDigit(token.codePointBefore(token.length()))
                        && cursor + Character.charCount(cp) < normalized.length()
                        && Character.isLetterOrDigit(normalized.codePointAt(cursor + Character.charCount(cp)))) {
                    cursor += Character.charCount(cp);
                } else {
                    break;
                }
            }
            String value = token.toString();
            boolean suffix = value.startsWith("*");
            boolean prefix = value.endsWith("*");
            int start = suffix ? 1 : 0;
            int end = value.length() - (prefix ? 1 : 0);
            if (end <= start || value.substring(start, end).indexOf('*') >= 0) {
                throw new IllegalArgumentException("wildcards are supported only at the edges of a word");
            }
            result.add(new WordPattern(value.substring(start, end), suffix
                    ? prefix ? Mode.SUBSTRING : Mode.SUFFIX : prefix ? Mode.PREFIX : Mode.EXACT));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("pattern contains no words");
        }
        return List.copyOf(result);
    }

    private static final class MutableTrie {
        private final Map<Character, MutableTrie> children = new HashMap<>();
        private final List<CompiledRule> rules = new ArrayList<>();

        private void add(String text, boolean reversed, CompiledRule rule) {
            MutableTrie node = this;
            for (int i = 0; i < text.length(); i++) {
                char character = text.charAt(reversed ? text.length() - 1 - i : i);
                node = node.children.computeIfAbsent(character, ignored -> new MutableTrie());
            }
            node.rules.add(rule);
        }
    }

    private static final class Trie {
        private final char[] characters;
        private final Trie[] children;
        private final List<CompiledRule> rules;

        private Trie(char[] characters, Trie[] children, List<CompiledRule> rules) {
            this.characters = characters;
            this.children = children;
            this.rules = rules;
        }

        private Trie child(char character) {
            int index = Arrays.binarySearch(characters, character);
            return index < 0 ? null : children[index];
        }

        private static Trie freeze(MutableTrie source) {
            List<Character> keys = source.children.keySet().stream().sorted().toList();
            char[] characters = new char[keys.size()];
            Trie[] children = new Trie[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                characters[i] = keys.get(i);
                children[i] = freeze(source.children.get(keys.get(i)));
            }
            return new Trie(characters, children, List.copyOf(source.rules));
        }
    }

    private static final class RegexDeadlineExceeded extends RuntimeException {
        private static final RegexDeadlineExceeded INSTANCE = new RegexDeadlineExceeded();

        private RegexDeadlineExceeded() {
            super("Regex deadline exceeded", null, false, false);
        }
    }

    /** Sub-sequences retain the same deadline, so lookarounds/backtracking cannot escape the budget. */
    private static final class DeadlineText implements CharSequence {
        private final String original;
        private final int start;
        private final int end;
        private final long deadline;

        private DeadlineText(String original, int start, int end, long deadline) {
            this.original = original;
            this.start = start;
            this.end = end;
            this.deadline = deadline;
        }

        private void checkDeadline() {
            if (System.nanoTime() - deadline >= 0) {
                throw RegexDeadlineExceeded.INSTANCE;
            }
        }

        @Override
        public int length() {
            checkDeadline();
            return end - start;
        }

        @Override
        public char charAt(int index) {
            checkDeadline();
            if (index < 0 || index >= end - start) {
                throw new IndexOutOfBoundsException(index);
            }
            return original.charAt(start + index);
        }

        @Override
        public CharSequence subSequence(int from, int to) {
            checkDeadline();
            if (from < 0 || to < from || to > end - start) {
                throw new IndexOutOfBoundsException();
            }
            return new DeadlineText(original, start + from, start + to, deadline);
        }

        @Override
        public String toString() {
            checkDeadline();
            String result = original.substring(start, end);
            checkDeadline();
            return result;
        }
    }
}
