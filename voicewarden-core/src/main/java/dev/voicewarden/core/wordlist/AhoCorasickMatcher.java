package dev.voicewarden.core.wordlist;

import dev.voicewarden.api.FlagSeverity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AhoCorasickMatcher {

    private static final int ROOT = 0;
    private static final int NONE = -1;
    private static final Comparator<WordMatch> MATCH_ORDER = Comparator.comparingInt(WordMatch::startIndex)
            .thenComparing(Comparator.comparingInt(WordMatch::endIndex).reversed())
            .thenComparing(WordMatch::word);

    private final boolean caseSensitive;
    private final boolean partialMatch;
    private final int[] edgeOffsets;
    private final char[] edgeChars;
    private final int[] edgeTargets;
    private final int[] failLinks;
    private final int[] outputLinks;
    private final int[] depths;
    private final String[] words;
    private final FlagSeverity[] severities;
    private final String[] languages;
    private final boolean[] allowed;
    private final int flaggedPatterns;
    private final int allowPatterns;

    private AhoCorasickMatcher(boolean caseSensitive, boolean partialMatch, Trie trie) {
        this.caseSensitive = caseSensitive;
        this.partialMatch = partialMatch;
        int states = trie.children.size();
        this.edgeOffsets = new int[states + 1];
        int edgeCount = 0;
        for (Map<Character, Integer> children : trie.children) {
            edgeCount += children.size();
        }
        this.edgeChars = new char[edgeCount];
        this.edgeTargets = new int[edgeCount];
        int cursor = 0;
        for (int state = 0; state < states; state++) {
            edgeOffsets[state] = cursor;
            Character[] keys = trie.children.get(state).keySet().toArray(new Character[0]);
            Arrays.sort(keys);
            for (Character key : keys) {
                edgeChars[cursor] = key;
                edgeTargets[cursor] = trie.children.get(state).get(key);
                cursor++;
            }
        }
        edgeOffsets[states] = cursor;
        this.depths = toIntArray(trie.depths);
        this.words = trie.words.toArray(new String[0]);
        this.severities = trie.severities.toArray(new FlagSeverity[0]);
        this.languages = trie.languages.toArray(new String[0]);
        this.allowed = new boolean[states];
        int flagged = 0;
        int allow = 0;
        for (int state = 0; state < states; state++) {
            allowed[state] = trie.allowed.get(state);
            if (allowed[state]) {
                allow++;
            } else if (severities[state] != null) {
                flagged++;
            }
        }
        this.flaggedPatterns = flagged;
        this.allowPatterns = allow;
        this.failLinks = new int[states];
        this.outputLinks = new int[states];
        buildLinks(states);
    }

    public static AhoCorasickMatcher empty() {
        return compile(ParsedWordlist.EMPTY, WordlistConfig.defaults());
    }

    public static AhoCorasickMatcher compile(ParsedWordlist wordlist, WordlistConfig config) {
        Objects.requireNonNull(wordlist, "wordlist");
        Objects.requireNonNull(config, "config");
        Trie trie = new Trie();
        for (WordlistEntry entry : wordlist.entries()) {
            String key = TextNormalizer.normalizeTerm(entry.term(), config.caseSensitive());
            if (!key.isEmpty()) {
                trie.insert(key, entry);
            }
        }
        return new AhoCorasickMatcher(config.caseSensitive(), config.partialMatch(), trie);
    }

    public boolean caseSensitive() {
        return caseSensitive;
    }

    public boolean partialMatch() {
        return partialMatch;
    }

    public int flaggedPatternCount() {
        return flaggedPatterns;
    }

    public int allowPatternCount() {
        return allowPatterns;
    }

    public int stateCount() {
        return depths.length;
    }

    public WordlistMatchResult match(String text) {
        if (text == null || text.isEmpty() || flaggedPatterns == 0) {
            return WordlistMatchResult.EMPTY;
        }
        TextNormalizer.Normalized normalized = TextNormalizer.normalizeText(text, caseSensitive);
        List<WordMatch> flagged = null;
        SpanList allowSpans = null;
        int state = ROOT;
        for (int i = 0; i < normalized.length; i++) {
            state = step(state, normalized.chars[i]);
            int output = words[state] != null ? state : outputLinks[state];
            while (output != NONE) {
                int start = normalized.starts[i - depths[output] + 1];
                int end = normalized.ends[i];
                if (partialMatch || WordBoundaries.isWholeWord(text, start, end)) {
                    if (allowed[output]) {
                        if (allowSpans == null) {
                            allowSpans = new SpanList();
                        }
                        allowSpans.add(start, end);
                    } else {
                        if (flagged == null) {
                            flagged = new ArrayList<>();
                        }
                        flagged.add(new WordMatch(words[output], severities[output], languages[output], start, end));
                    }
                }
                output = outputLinks[output];
            }
        }
        if (flagged == null) {
            return WordlistMatchResult.EMPTY;
        }
        flagged.sort(MATCH_ORDER);
        if (allowSpans != null) {
            flagged = suppressAllowed(flagged, allowSpans);
        }
        return WordlistMatchResult.of(flagged);
    }

    private static List<WordMatch> suppressAllowed(List<WordMatch> flagged, SpanList allowSpans) {
        allowSpans.sortByStart();
        List<WordMatch> kept = new ArrayList<>(flagged.size());
        int next = 0;
        int coveredUntil = -1;
        for (WordMatch match : flagged) {
            while (next < allowSpans.size && allowSpans.starts[next] <= match.startIndex()) {
                coveredUntil = Math.max(coveredUntil, allowSpans.ends[next]);
                next++;
            }
            if (coveredUntil < match.endIndex()) {
                kept.add(match);
            }
        }
        return kept;
    }

    private int step(int state, char c) {
        int current = state;
        while (true) {
            int target = transition(current, c);
            if (target != NONE) {
                return target;
            }
            if (current == ROOT) {
                return ROOT;
            }
            current = failLinks[current];
        }
    }

    private int transition(int state, char c) {
        int low = edgeOffsets[state];
        int high = edgeOffsets[state + 1] - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            char key = edgeChars[mid];
            if (key < c) {
                low = mid + 1;
            } else if (key > c) {
                high = mid - 1;
            } else {
                return edgeTargets[mid];
            }
        }
        return NONE;
    }

    private void buildLinks(int states) {
        Arrays.fill(outputLinks, NONE);
        failLinks[ROOT] = ROOT;
        ArrayDeque<Integer> queue = new ArrayDeque<>(states);
        for (int e = edgeOffsets[ROOT]; e < edgeOffsets[ROOT + 1]; e++) {
            failLinks[edgeTargets[e]] = ROOT;
            queue.add(edgeTargets[e]);
        }
        while (!queue.isEmpty()) {
            int state = queue.poll();
            for (int e = edgeOffsets[state]; e < edgeOffsets[state + 1]; e++) {
                int child = edgeTargets[e];
                failLinks[child] = step(failLinks[state], edgeChars[e]);
                int fail = failLinks[child];
                outputLinks[child] = words[fail] != null ? fail : outputLinks[fail];
                queue.add(child);
            }
        }
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static final class Trie {
        private final List<Map<Character, Integer>> children = new ArrayList<>();
        private final List<Integer> depths = new ArrayList<>();
        private final List<String> words = new ArrayList<>();
        private final List<FlagSeverity> severities = new ArrayList<>();
        private final List<String> languages = new ArrayList<>();
        private final List<Boolean> allowed = new ArrayList<>();

        private Trie() {
            newState(0);
        }

        private int newState(int depth) {
            children.add(new HashMap<>());
            depths.add(depth);
            words.add(null);
            severities.add(null);
            languages.add(null);
            allowed.add(false);
            return children.size() - 1;
        }

        private void insert(String key, WordlistEntry entry) {
            int state = ROOT;
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                Integer next = children.get(state).get(c);
                if (next == null) {
                    next = newState(i + 1);
                    children.get(state).put(c, next);
                }
                state = next;
            }
            if (words.get(state) == null) {
                words.set(state, key);
            }
            if (entry.tier().isAllow()) {
                allowed.set(state, true);
                return;
            }
            FlagSeverity severity = entry.tier().severity().orElseThrow();
            FlagSeverity existing = severities.get(state);
            if (existing == null || severity.compareTo(existing) > 0) {
                severities.set(state, severity);
                languages.set(state, entry.language());
            }
        }
    }

    private static final class SpanList {
        private int[] starts = new int[8];
        private int[] ends = new int[8];
        private int size;

        private void add(int start, int end) {
            if (size == starts.length) {
                starts = Arrays.copyOf(starts, size * 2);
                ends = Arrays.copyOf(ends, size * 2);
            }
            starts[size] = start;
            ends[size] = end;
            size++;
        }

        private void sortByStart() {
            long[] packed = new long[size];
            for (int i = 0; i < size; i++) {
                packed[i] = ((long) starts[i] << 32) | (ends[i] & 0xFFFFFFFFL);
            }
            Arrays.sort(packed);
            for (int i = 0; i < size; i++) {
                starts[i] = (int) (packed[i] >>> 32);
                ends[i] = (int) packed[i];
            }
        }
    }
}
