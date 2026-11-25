package dev.voicewarden.core.wordlist;

final class TextNormalizer {

    static final char SPACE = ' ';

    private TextNormalizer() {
    }

    static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    static char fold(char c, boolean caseSensitive) {
        if (caseSensitive || Character.isSurrogate(c)) {
            return c;
        }
        return Character.toLowerCase(c);
    }

    static String normalizeTerm(String raw, boolean caseSensitive) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (isSpace(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(SPACE);
                pendingSpace = false;
            }
            out.append(fold(c, caseSensitive));
        }
        return out.toString();
    }

    static Normalized normalizeText(String text, boolean caseSensitive) {
        int length = text.length();
        char[] chars = new char[length];
        int[] starts = new int[length];
        int[] ends = new int[length];
        int size = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (isSpace(c)) {
                if (size > 0 && chars[size - 1] == SPACE && ends[size - 1] == i) {
                    ends[size - 1] = i + 1;
                    continue;
                }
                c = SPACE;
            } else {
                c = fold(c, caseSensitive);
            }
            chars[size] = c;
            starts[size] = i;
            ends[size] = i + 1;
            size++;
        }
        return new Normalized(chars, starts, ends, size);
    }

    static final class Normalized {
        final char[] chars;
        final int[] starts;
        final int[] ends;
        final int length;

        Normalized(char[] chars, int[] starts, int[] ends, int length) {
            this.chars = chars;
            this.starts = starts;
            this.ends = ends;
            this.length = length;
        }
    }
}
