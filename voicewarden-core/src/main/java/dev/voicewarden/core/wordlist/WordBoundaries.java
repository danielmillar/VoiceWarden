package dev.voicewarden.core.wordlist;

final class WordBoundaries {

    private WordBoundaries() {
    }

    static boolean isWholeWord(CharSequence text, int start, int end) {
        return startsWord(text, start) && endsWord(text, end);
    }

    static boolean startsWord(CharSequence text, int start) {
        if (start == 0) {
            return true;
        }
        int before = Character.codePointBefore(text, start);
        int first = Character.codePointAt(text, start);
        return !Character.isLetterOrDigit(before) || isUnspacedScript(before) || isUnspacedScript(first);
    }

    static boolean endsWord(CharSequence text, int end) {
        if (end >= text.length()) {
            return true;
        }
        int after = Character.codePointAt(text, end);
        int last = Character.codePointBefore(text, end);
        return !Character.isLetterOrDigit(after) || isUnspacedScript(after) || isUnspacedScript(last);
    }

    static boolean isUnspacedScript(int codePoint) {
        return switch (Character.UnicodeScript.of(codePoint)) {
            case HAN, HIRAGANA, KATAKANA, THAI, LAO, KHMER, MYANMAR -> true;
            case COMMON -> isKanaProlongedSoundMark(codePoint);
            default -> false;
        };
    }

    private static boolean isKanaProlongedSoundMark(int codePoint) {
        return codePoint == 0x30FC || codePoint == 0xFF70;
    }
}
