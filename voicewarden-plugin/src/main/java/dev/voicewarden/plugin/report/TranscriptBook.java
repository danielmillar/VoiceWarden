package dev.voicewarden.plugin.report;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class TranscriptBook {

    public static final int MAX_LINES_PER_PAGE = 12;
    public static final int MAX_CHARS_PER_PAGE = 240;
    public static final int MAX_CHARS_PER_LINE = 180;
    public static final int MAX_PAGES = 100;

    private TranscriptBook() {
    }

    public static Book build(MessageRenderer messages, String targetName, List<TranscriptLine> lines, int minutes) {
        Map<String, String> header = Map.of("player", targetName, "minutes", Integer.toString(minutes));
        Component title = messages.render(MessageKey.REPORT_BOOK_TITLE, header);
        Component author = messages.render(MessageKey.REPORT_BOOK_AUTHOR, header);
        Component headerText = messages.render(MessageKey.REPORT_BOOK_HEADER, header);
        PageBuilder pages = new PageBuilder();
        pages.add(headerText, 3);
        if (lines.isEmpty()) {
            pages.add(messages.render(MessageKey.REPORT_BOOK_EMPTY, header), 1);
        }
        for (TranscriptLine line : lines) {
            MessageKey key = line.fromNearbyPlayer() ? MessageKey.REPORT_BOOK_NEARBY_LINE
                    : line.flagged() ? MessageKey.REPORT_BOOK_FLAGGED_LINE : MessageKey.REPORT_BOOK_LINE;
            for (String chunk : chunks(line.text())) {
                Map<String, String> values = line.fromNearbyPlayer()
                        ? Map.of("time", line.time(), "player", line.speaker(), "text", chunk)
                        : Map.of("time", line.time(), "text", chunk);
                Component rendered = messages.render(key, values);
                pages.add(rendered.append(Component.newline()), 1);
            }
        }
        return Book.book(title, author, pages.finish());
    }

    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        String remaining = text.strip();
        while (remaining.length() > MAX_CHARS_PER_LINE) {
            int cut = remaining.lastIndexOf(' ', MAX_CHARS_PER_LINE);
            if (cut <= 0) {
                cut = MAX_CHARS_PER_LINE;
            }
            out.add(remaining.substring(0, cut).strip());
            remaining = remaining.substring(cut).strip();
        }
        out.add(remaining);
        return out;
    }

    private static final class PageBuilder {
        private final List<Component> pages = new ArrayList<>();
        private Component current = Component.empty();
        private int lines;
        private int chars;

        void add(Component part, int lineCount) {
            int length = PlainTextComponentSerializer.plainText().serialize(part).length();
            if (lines > 0 && (lines + lineCount > MAX_LINES_PER_PAGE || chars + length > MAX_CHARS_PER_PAGE)) {
                flush();
            }
            current = current.append(part);
            lines += lineCount;
            chars += length;
        }

        List<Component> finish() {
            if (lines > 0) {
                flush();
            }
            if (pages.size() > MAX_PAGES) {
                return List.copyOf(pages.subList(pages.size() - MAX_PAGES, pages.size()));
            }
            return List.copyOf(pages);
        }

        private void flush() {
            pages.add(current);
            current = Component.empty();
            lines = 0;
            chars = 0;
        }
    }
}
