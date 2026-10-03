package dev.danielmillar.voicesentinel.moderation.rules;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared tokenisation for configuration and transcripts. Offsets are always original UTF-16 indices. */
final class RuleText {
    private RuleText() {
    }

    record Token(String text, int start, int end) {
        boolean singleLetter() {
            return text.codePointCount(0, text.length()) == 1 && Character.isLetter(text.codePointAt(0));
        }
    }

    static List<Token> tokens(String input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<Token> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < input.length()) {
            int cp = input.codePointAt(cursor);
            if (!wordCharacter(cp) || mark(cp)) {
                cursor += Character.charCount(cp);
                continue;
            }
            int start = cursor;
            boolean simpleAscii = true;
            while (cursor < input.length()) {
                cp = input.codePointAt(cursor);
                if (wordCharacter(cp)) {
                    simpleAscii &= cp < 128;
                    cursor += Character.charCount(cp);
                } else if (apostrophe(cp) && internalApostrophe(input, cursor, start)) {
                    simpleAscii = false;
                    cursor += Character.charCount(cp);
                } else {
                    break;
                }
            }
            if (simpleAscii) {
                String text = input.substring(start, cursor).toLowerCase(Locale.ROOT);
                result.add(new Token(text, start, cursor));
            } else {
                String text = normalize(input.substring(start, cursor)).replace("'", "").replace("’", "");
                // A compatibility character can expand into several words. Each keeps its source
                // token's span; never use a normalised string's offsets against the original input.
                int index = 0;
                while (index < text.length()) {
                    int normalizedCp = text.codePointAt(index);
                    if (!Character.isLetterOrDigit(normalizedCp)) {
                        index += Character.charCount(normalizedCp);
                        continue;
                    }
                    int begin = index;
                    do {
                        index += Character.charCount(normalizedCp);
                        if (index == text.length()) {
                            break;
                        }
                        normalizedCp = text.codePointAt(index);
                    } while (Character.isLetterOrDigit(normalizedCp));
                    result.add(new Token(text.substring(begin, index), start, cursor));
                }
            }
        }
        return result;
    }

    static String normalize(String input) {
        String decomposed = Normalizer.normalize(
                Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder output = new StringBuilder(decomposed.length());
        decomposed.codePoints().filter(cp -> !mark(cp)).forEach(output::appendCodePoint);
        return output.toString();
    }

    static boolean apostrophe(int cp) {
        return cp == '\'' || cp == '’' || cp == '＇';
    }

    static boolean mark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean wordCharacter(int cp) {
        if (cp < 128) {
            return cp >= 'a' && cp <= 'z' || cp >= 'A' && cp <= 'Z' || cp >= '0' && cp <= '9';
        }
        if (Character.isLetterOrDigit(cp) || mark(cp)) {
            return true;
        }
        if (apostrophe(cp)) {
            return false;
        }
        // Includes compatibility digits/letters such as circled digits and squared Latin letters.
        return Normalizer.normalize(Character.toString(cp), Normalizer.Form.NFKC)
                .codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static boolean internalApostrophe(String input, int index, int start) {
        int after = index + Character.charCount(input.codePointAt(index));
        // Diacritics disappear before the apostrophe rule is applied. A decomposed accent just
        // after the apostrophe must not make transcript tokenisation differ from pattern parsing.
        while (after < input.length() && mark(input.codePointAt(after))) {
            after += Character.charCount(input.codePointAt(after));
        }
        return index > start && after < input.length()
                && wordCharacter(input.codePointBefore(index))
                && wordCharacter(input.codePointAt(after));
    }
}
