package dev.voicewarden.core.wordlist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RegexPrefilter {

    static final RegexPrefilter ALWAYS = new RegexPrefilter(false, false, false, List.of(), List.of());

    private final boolean requireDigit;
    private final boolean requireDot;
    private final boolean requireAt;
    private final List<String> requiredSubstrings;
    private final List<List<String>> anyOfGroups;

    private RegexPrefilter(
            boolean requireDigit,
            boolean requireDot,
            boolean requireAt,
            List<String> requiredSubstrings,
            List<List<String>> anyOfGroups) {
        this.requireDigit = requireDigit;
        this.requireDot = requireDot;
        this.requireAt = requireAt;
        this.requiredSubstrings = List.copyOf(requiredSubstrings);
        this.anyOfGroups = List.copyOf(anyOfGroups);
    }

    static RegexPrefilter analyze(String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.contains("\\Q") || hasTopLevelAlternation(pattern)) {
            return ALWAYS;
        }
        boolean digit = false;
        boolean dot = false;
        boolean at = false;
        List<String> literals = new ArrayList<>();
        List<List<String>> anyGroups = new ArrayList<>();

        int n = pattern.length();
        int i = 0;
        StringBuilder literal = new StringBuilder();

        while (i < n) {
            char c = pattern.charAt(i);
            if (c == '[') {
                flushLiteral(literal, literals);
                int end = skipCharClass(pattern, i);
                i = skipQuantifier(pattern, end);
                continue;
            }
            if (c == '(') {
                flushLiteral(literal, literals);
                int close = findMatchingParen(pattern, i);
                if (close < 0) {
                    return ALWAYS;
                }
                int after = close + 1;
                boolean optional = isOptionalQuantifier(pattern, after);
                boolean lookaround = i + 2 < n && pattern.charAt(i + 1) == '?'
                        && (pattern.charAt(i + 2) == '=' || pattern.charAt(i + 2) == '!'
                                || (i + 3 < n && pattern.charAt(i + 2) == '<'
                                        && (pattern.charAt(i + 3) == '=' || pattern.charAt(i + 3) == '!')));
                if (!optional && !lookaround) {
                    String inner = stripGroupHeader(pattern.substring(i + 1, close));
                    if (inner != null) {
                        if (!hasTopLevelAlternation(inner)) {
                            RegexPrefilter nested = analyze(inner);
                            digit |= nested.requireDigit;
                            dot |= nested.requireDot;
                            at |= nested.requireAt;
                            literals.addAll(nested.requiredSubstrings);
                            anyGroups.addAll(nested.anyOfGroups);
                        } else {
                            List<String> branches = splitTopLevelAlternation(inner);
                            List<String> anchors = extractBranchAnchors(branches);
                            if (anchors != null && !anchors.isEmpty()) {
                                anyGroups.add(anchors);
                            }
                        }
                    }
                }
                i = skipQuantifier(pattern, after);
                continue;
            }
            if (c == '\\') {
                if (i + 1 >= n) {
                    break;
                }
                char next = pattern.charAt(i + 1);
                int after = i + 2;
                if (next == 'x' || next == 'u' || next == '0' || next == 'k' || next == 'c' || next == 'N' || next == 'g') {
                    return ALWAYS;
                }
                if (next == 'p' || next == 'P') {
                    flushLiteral(literal, literals);
                    if (after < n && pattern.charAt(after) == '{') {
                        int brace = pattern.indexOf('}', after);
                        after = brace >= 0 ? brace + 1 : after;
                    }
                    i = skipQuantifier(pattern, after);
                    continue;
                }
                boolean optional = isOptionalQuantifier(pattern, after);
                if (next == 'd') {
                    flushLiteral(literal, literals);
                    if (!optional) {
                        digit = true;
                    }
                } else if (next == '.') {
                    flushLiteral(literal, literals);
                    if (!optional) {
                        dot = true;
                    }
                } else if (next == '@' || next == '/') {
                    flushLiteral(literal, literals);
                    if (!optional && next == '@') {
                        at = true;
                    }
                } else if (isBoundaryOrMeta(next)) {
                    flushLiteral(literal, literals);
                } else {
                    flushLiteral(literal, literals);
                }
                i = skipQuantifier(pattern, after);
                continue;
            }
            if (isMetaChar(c)) {
                flushLiteral(literal, literals);
                i = skipQuantifier(pattern, i + 1);
                continue;
            }
            int after = i + 1;
            if (isOptionalQuantifier(pattern, after)) {
                flushLiteral(literal, literals);
                i = skipQuantifier(pattern, after);
                continue;
            }
            int nextIndex = skipQuantifier(pattern, after);
            boolean quantified = nextIndex > after;
            if (c == '@') {
                flushLiteral(literal, literals);
                at = true;
            } else if (isAsciiLetter(c)) {
                literal.append(Character.toLowerCase(c));
                if (quantified) {
                    flushLiteral(literal, literals);
                }
            } else {
                flushLiteral(literal, literals);
            }
            i = nextIndex;
        }
        flushLiteral(literal, literals);
        if (!digit && !dot && !at && literals.isEmpty() && anyGroups.isEmpty()) {
            return ALWAYS;
        }
        return new RegexPrefilter(digit, dot, at, literals.stream().distinct().toList(), anyGroups);
    }

    boolean mightMatch(String lowerText, boolean hasDigit, boolean hasDot, boolean hasAt) {
        if (requireDigit && !hasDigit) {
            return false;
        }
        if (requireDot && !hasDot) {
            return false;
        }
        if (requireAt && !hasAt) {
            return false;
        }
        for (String required : requiredSubstrings) {
            if (!lowerText.contains(required)) {
                return false;
            }
        }
        for (List<String> group : anyOfGroups) {
            boolean found = false;
            for (String option : group) {
                if (lowerText.contains(option)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    boolean isTrivial() {
        return !requireDigit && !requireDot && !requireAt && requiredSubstrings.isEmpty() && anyOfGroups.isEmpty();
    }

    private static List<String> extractBranchAnchors(List<String> branches) {
        List<String> anchors = new ArrayList<>(branches.size());
        for (String branch : branches) {
            RegexPrefilter sub = analyze(branch);
            String best = "";
            for (String lit : sub.requiredSubstrings) {
                if (lit.length() > best.length()) {
                    best = lit;
                }
            }
            if (best.length() < 2) {
                return null;
            }
            anchors.add(best.toLowerCase(Locale.ROOT));
        }
        return anchors.stream().distinct().toList();
    }

    private static void flushLiteral(StringBuilder literal, List<String> out) {
        if (literal.length() >= 2) {
            out.add(literal.toString());
        }
        literal.setLength(0);
    }

    private static String stripGroupHeader(String inside) {
        if (!inside.startsWith("?")) {
            return inside;
        }
        if (inside.startsWith("?:")) {
            return inside.substring(2);
        }
        return null;
    }

    private static boolean hasTopLevelAlternation(String pattern) {
        int depth = 0;
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                i = skipCharClass(pattern, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == '|' && depth == 0) {
                return true;
            }
            i++;
        }
        return false;
    }

    private static List<String> splitTopLevelAlternation(String pattern) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                i = skipCharClass(pattern, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == '|' && depth == 0) {
                parts.add(pattern.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        parts.add(pattern.substring(start));
        return parts;
    }

    private static int skipCharClass(String pattern, int openBracket) {
        int i = openBracket + 1;
        if (i < pattern.length() && pattern.charAt(i) == '^') {
            i++;
        }
        if (i < pattern.length() && pattern.charAt(i) == ']') {
            i++;
        }
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == ']') {
                return i + 1;
            }
            i++;
        }
        return pattern.length();
    }

    private static int findMatchingParen(String pattern, int openParen) {
        int depth = 0;
        int i = openParen;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                i = skipCharClass(pattern, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static boolean isOptionalQuantifier(String pattern, int index) {
        if (index >= pattern.length()) {
            return false;
        }
        char q = pattern.charAt(index);
        if (q == '?' || q == '*') {
            return true;
        }
        if (q == '{') {
            int comma = pattern.indexOf(',', index + 1);
            int close = pattern.indexOf('}', index + 1);
            int end = comma >= 0 && comma < close ? comma : close;
            if (end > index + 1) {
                try {
                    return Integer.parseInt(pattern.substring(index + 1, end).trim()) == 0;
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int skipQuantifier(String pattern, int index) {
        if (index >= pattern.length()) {
            return index;
        }
        char q = pattern.charAt(index);
        int end = index;
        if (q == '?' || q == '*' || q == '+') {
            end = index + 1;
        } else if (q == '{') {
            int close = pattern.indexOf('}', index + 1);
            if (close > index) {
                end = close + 1;
            }
        }
        if (end > index && end < pattern.length() && (pattern.charAt(end) == '?' || pattern.charAt(end) == '+')) {
            end++;
        }
        return end;
    }

    private static boolean isMetaChar(char c) {
        return c == '.' || c == '^' || c == '$' || c == '*' || c == '+' || c == '?' || c == '{' || c == '}' || c == '|';
    }

    private static boolean isBoundaryOrMeta(char c) {
        return c == 'b' || c == 'B' || c == 'A' || c == 'Z' || c == 'z' || c == 's' || c == 'S'
                || c == 'w' || c == 'W' || c == 'D';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
