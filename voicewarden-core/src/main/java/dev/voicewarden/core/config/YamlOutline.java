package dev.voicewarden.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YamlOutline {

    private static final Pattern KEY_LINE = Pattern.compile(
            "^( *)(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^\\s#'\"\\[\\]{},&*!|>%@`-][^#]*?|-[^\\s#][^#]*?)([ \\t]*:)(?=[ \\t]|$)(.*)$");

    final List<Entry> entries;
    final List<String> trailing;

    private YamlOutline(List<Entry> entries, List<String> trailing) {
        this.entries = entries;
        this.trailing = trailing;
    }

    static YamlOutline parse(List<String> lines) {
        Parser parser = new Parser(lines);
        List<Entry> entries = parser.parseBlock(0);
        List<String> trailing = new ArrayList<>(lines.subList(parser.position, lines.size()));
        return new YamlOutline(entries, trailing);
    }

    List<String> emit() {
        List<String> out = new ArrayList<>();
        for (Entry entry : entries) {
            entry.emitTo(out);
        }
        out.addAll(trailing);
        return out;
    }

    static final class Entry {
        final List<String> leading;
        final String key;
        final int indent;
        String keyLine;
        final List<String> body;
        final List<Entry> children;
        final List<String> raw;

        private Entry(List<String> leading, String keyLine, String key, int indent) {
            this.leading = leading;
            this.keyLine = keyLine;
            this.key = key;
            this.indent = indent;
            this.body = new ArrayList<>();
            this.children = new ArrayList<>();
            this.raw = null;
        }

        private Entry(String key, int indent, List<String> raw) {
            this.leading = List.of();
            this.keyLine = null;
            this.key = key;
            this.indent = indent;
            this.body = List.of();
            this.children = new ArrayList<>();
            this.raw = raw;
        }

        static Entry inserted(String key, int indent, List<String> lines) {
            return new Entry(key, indent, List.copyOf(lines));
        }

        boolean isInserted() {
            return raw != null;
        }

        boolean isMapping() {
            return !children.isEmpty();
        }

        boolean hasNoValue() {
            return raw == null && children.isEmpty() && body.isEmpty() && inlineValue().isEmpty();
        }

        String inlineValue() {
            Matcher matcher = KEY_LINE.matcher(keyLine);
            if (!matcher.matches()) {
                return "";
            }
            return stripInlineComment(matcher.group(4)).strip();
        }

        void replaceInlineValue(String newValue) {
            Matcher matcher = KEY_LINE.matcher(keyLine);
            if (!matcher.matches()) {
                return;
            }
            String rest = matcher.group(4);
            String withoutComment = stripInlineComment(rest);
            String comment = rest.substring(withoutComment.length()).strip();
            String prefix = matcher.group(1) + matcher.group(2) + matcher.group(3);
            keyLine = prefix + " " + newValue + (comment.isEmpty() ? "" : " " + comment);
        }

        void emitTo(List<String> out) {
            if (raw != null) {
                out.addAll(raw);
                return;
            }
            out.addAll(leading);
            out.add(keyLine);
            out.addAll(body);
            for (Entry child : children) {
                child.emitTo(out);
            }
        }

        List<String> emit() {
            List<String> out = new ArrayList<>();
            emitTo(out);
            return out;
        }
    }

    static String stripInlineComment(String value) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inDouble) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
            } else if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
                return stripTrailingWhitespace(value.substring(0, i));
            }
        }
        return value;
    }

    private static String stripTrailingWhitespace(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    static String unquoteKey(String key) {
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            return key.substring(1, key.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (key.length() >= 2 && key.startsWith("'") && key.endsWith("'")) {
            return key.substring(1, key.length() - 1).replace("''", "'");
        }
        return key.strip();
    }

    static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    static boolean isBlankOrComment(String line) {
        String stripped = line.strip();
        return stripped.isEmpty() || stripped.startsWith("#");
    }

    private static boolean isListItem(String line) {
        String stripped = line.stripLeading();
        return stripped.equals("-") || stripped.startsWith("- ") || stripped.startsWith("-\t");
    }

    private static final class Parser {
        private final List<String> lines;
        private int position;

        Parser(List<String> lines) {
            this.lines = lines;
        }

        List<Entry> parseBlock(int minIndent) {
            List<Entry> entries = new ArrayList<>();
            List<String> pending = new ArrayList<>();
            int pendingStart = position;
            int blockIndent = -1;
            while (position < lines.size()) {
                String line = lines.get(position);
                if (isBlankOrComment(line)) {
                    if (pending.isEmpty()) {
                        pendingStart = position;
                    }
                    pending.add(line);
                    position++;
                    continue;
                }
                int indent = indentOf(line);
                if (indent < minIndent || (blockIndent >= 0 && indent < blockIndent)) {
                    break;
                }
                Matcher matcher = isListItem(line) ? null : KEY_LINE.matcher(line);
                boolean isKey = matcher != null && matcher.matches();
                if (blockIndent < 0 && isKey) {
                    blockIndent = indent;
                }
                if (!isKey || indent != blockIndent) {
                    if (pending.isEmpty()) {
                        pendingStart = position;
                    }
                    pending.add(line);
                    position++;
                    continue;
                }
                Entry entry = new Entry(pending, line, unquoteKey(matcher.group(2)), indent);
                pending = new ArrayList<>();
                position++;
                readValue(entry, stripInlineComment(matcher.group(4)).strip());
                entries.add(entry);
            }
            if (!pending.isEmpty()) {
                position = pendingStart;
            }
            return entries;
        }

        private void readValue(Entry entry, String inlineValue) {
            int indent = entry.indent;
            if (!inlineValue.isEmpty()) {
                consumeBody(entry, line -> indentOf(line) > indent, inlineValue.startsWith("|") || inlineValue.startsWith(">"));
                return;
            }
            int next = nextContentLine();
            if (next < 0) {
                return;
            }
            String nextLine = lines.get(next);
            int nextIndent = indentOf(nextLine);
            if (isListItem(nextLine) && nextIndent >= indent) {
                consumeBody(entry, line -> indentOf(line) > indent || (indentOf(line) == indent && isListItem(line)), false);
            } else if (nextIndent > indent && KEY_LINE.matcher(nextLine).matches()) {
                entry.children.addAll(parseBlock(indent + 1));
            } else if (nextIndent > indent) {
                consumeBody(entry, line -> indentOf(line) > indent, false);
            }
        }

        private int nextContentLine() {
            for (int i = position; i < lines.size(); i++) {
                if (!isBlankOrComment(lines.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        private void consumeBody(Entry entry, Predicate<String> belongs, boolean blockScalar) {
            int lastContent = position - 1;
            int cursor = position;
            while (cursor < lines.size()) {
                String line = lines.get(cursor);
                boolean blank = line.isBlank();
                if (blank || (!blockScalar && isBlankOrComment(line))) {
                    cursor++;
                    continue;
                }
                if (!belongs.test(line)) {
                    break;
                }
                lastContent = cursor;
                cursor++;
            }
            for (int i = position; i <= lastContent; i++) {
                entry.body.add(lines.get(i));
            }
            position = lastContent + 1;
        }
    }
}
