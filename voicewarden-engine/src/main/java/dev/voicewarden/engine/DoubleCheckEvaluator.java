package dev.voicewarden.engine;

import dev.voicewarden.api.ConfirmationState;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class DoubleCheckEvaluator {

    public static final int SHORT_PHRASE_TOKENS = 3;
    public static final double DEFAULT_LONG_COVERAGE = 0.75;
    static final int MAX_TOKENS = 256;

    private static final Pattern APOSTROPHES = Pattern.compile("[‘’ʼ`´]");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}']+");
    private static final Pattern EDGE_APOSTROPHES = Pattern.compile("(^'+|'+$)");
    private static final int MIN_STEM_LENGTH = 3;
    private static final int MIN_CLIPPED_G_LENGTH = 5;
    private static final Set<String> INFLECTIONS = Set.of("s", "es", "ed", "ing", "in");
    private static final Set<String> DOUBLING_INFLECTIONS = Set.of("ed", "ing", "in");

    private static final Map<String, String> CONTRACTIONS = Map.ofEntries(
            Map.entry("won't", "will not"), Map.entry("wont", "will not"),
            Map.entry("can't", "can not"), Map.entry("cant", "can not"), Map.entry("cannot", "can not"),
            Map.entry("shan't", "shall not"), Map.entry("ain't", "is not"),
            Map.entry("dont", "do not"), Map.entry("doesnt", "does not"), Map.entry("didnt", "did not"),
            Map.entry("isnt", "is not"), Map.entry("arent", "are not"), Map.entry("wasnt", "was not"),
            Map.entry("werent", "were not"), Map.entry("havent", "have not"), Map.entry("hasnt", "has not"),
            Map.entry("shouldnt", "should not"), Map.entry("wouldnt", "would not"), Map.entry("couldnt", "could not"),
            Map.entry("im", "i am"), Map.entry("youre", "you are"), Map.entry("theyre", "they are"),
            Map.entry("ive", "i have"), Map.entry("youve", "you have"), Map.entry("youll", "you will"),
            Map.entry("gonna", "going to"), Map.entry("wanna", "want to"), Map.entry("gotta", "got to"));

    private static final Map<String, String> SUFFIX_CONTRACTIONS = Map.of(
            "n't", " not", "'re", " are", "'m", " am", "'ll", " will", "'ve", " have", "'d", " would");

    private final double longCoverage;

    public DoubleCheckEvaluator() {
        this(DEFAULT_LONG_COVERAGE);
    }

    public DoubleCheckEvaluator(double longCoverage) {
        if (longCoverage <= 0 || longCoverage > 1) {
            throw new IllegalArgumentException("longCoverage must be in (0, 1], got " + longCoverage);
        }
        this.longCoverage = longCoverage;
    }

    public ConfirmationState evaluate(String primaryTranscript, String verifierTranscript) {
        List<String> primary = tokens(primaryTranscript);
        List<String> verifier = tokens(verifierTranscript);
        if (primary.isEmpty() || verifier.isEmpty()) {
            return ConfirmationState.UNCONFIRMED;
        }
        int matched = alignedMatches(primary, verifier);
        int required = primary.size() <= SHORT_PHRASE_TOKENS
                ? primary.size()
                : (int) Math.ceil(primary.size() * longCoverage);
        return matched >= required ? ConfirmationState.CONFIRMED : ConfirmationState.UNCONFIRMED;
    }

    public static String normalize(String text) {
        return String.join(" ", tokens(text));
    }

    static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = APOSTROPHES.matcher(s).replaceAll("'");
        List<String> out = new ArrayList<>();
        for (String raw : NON_WORD.split(s)) {
            String word = EDGE_APOSTROPHES.matcher(raw).replaceAll("");
            if (word.isEmpty()) {
                continue;
            }
            for (String expanded : expand(word).split(" ")) {
                if (!expanded.isEmpty() && out.size() < MAX_TOKENS) {
                    out.add(expanded);
                }
            }
        }
        return out;
    }

    private static String expand(String word) {
        String known = CONTRACTIONS.get(word);
        if (known != null) {
            return known;
        }
        for (Map.Entry<String, String> suffix : SUFFIX_CONTRACTIONS.entrySet()) {
            if (word.endsWith(suffix.getKey()) && word.length() > suffix.getKey().length()) {
                return word.substring(0, word.length() - suffix.getKey().length()) + suffix.getValue();
            }
        }
        if (word.endsWith("'s") && word.length() > 2) {
            return word.substring(0, word.length() - 2);
        }
        String plain = word.replace("'", "");
        return CONTRACTIONS.getOrDefault(plain, plain);
    }

    static boolean sameWord(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.length() < MIN_STEM_LENGTH || b.length() < MIN_STEM_LENGTH) {
            return false;
        }
        return inflectionOf(a, b) || inflectionOf(b, a) || droppedG(a, b) || droppedG(b, a);
    }

    private static boolean inflectionOf(String base, String form) {
        if (!form.startsWith(base)) {
            return irregularInflection(base, form);
        }
        String suffix = form.substring(base.length());
        if (suffix.equals("d") && base.endsWith("e")) {
            return true;
        }
        if (INFLECTIONS.contains(suffix)) {
            return !(DOUBLING_INFLECTIONS.contains(suffix) && isShortClosedSyllable(base));
        }
        char last = base.charAt(base.length() - 1);
        return suffix.length() > 1 && suffix.charAt(0) == last && DOUBLING_INFLECTIONS.contains(suffix.substring(1));
    }

    private static boolean isShortClosedSyllable(String word) {
        return word.length() == MIN_STEM_LENGTH
                && !isVowel(word.charAt(0))
                && isVowel(word.charAt(1))
                && !isVowel(word.charAt(2))
                && "wxy".indexOf(word.charAt(2)) < 0;
    }

    private static boolean isVowel(char c) {
        return "aeiou".indexOf(c) >= 0;
    }

    private static boolean irregularInflection(String base, String form) {
        String stem = base.substring(0, base.length() - 1);
        if (base.endsWith("e")) {
            return form.equals(stem + "ing") || form.equals(stem + "in");
        }
        if (base.endsWith("y")) {
            return form.equals(stem + "ies") || form.equals(stem + "ied");
        }
        return false;
    }

    private static boolean droppedG(String clipped, String full) {
        return clipped.length() >= MIN_CLIPPED_G_LENGTH && clipped.endsWith("in") && full.equals(clipped + "g");
    }

    private static int alignedMatches(List<String> primary, List<String> verifier) {
        int n = primary.size();
        int m = verifier.size();
        int[][] best = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                int score = Math.max(best[i + 1][j], best[i][j + 1]);
                String p = primary.get(i);
                String v = verifier.get(j);
                if (sameWord(p, v)) {
                    score = Math.max(score, 1 + best[i + 1][j + 1]);
                }
                if (j + 1 < m && sameWord(p, v + verifier.get(j + 1))) {
                    score = Math.max(score, 1 + best[i + 1][j + 2]);
                }
                if (i + 1 < n && sameWord(p + primary.get(i + 1), v)) {
                    score = Math.max(score, 2 + best[i + 2][j + 1]);
                }
                best[i][j] = score;
            }
        }
        return best[0][0];
    }
}
