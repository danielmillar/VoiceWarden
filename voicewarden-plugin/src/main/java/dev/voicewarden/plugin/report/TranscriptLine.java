package dev.voicewarden.plugin.report;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.moderation.TranscriptEntry;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public record TranscriptLine(String time, String text, @Nullable FlagSeverity severity, @Nullable String speaker) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern SNAPSHOT_LINE = Pattern.compile("^\\[([^\\]]+)] (?:\\[(MILD|MODERATE|SEVERE)] )?(.*)$");
    private static final Pattern SPEAKER_LINE =
            Pattern.compile("^\\[([^\\]:]+)] \\[(\\d{2}:\\d{2}:\\d{2})] (?:\\[(MILD|MODERATE|SEVERE)] )?(.*)$");

    public TranscriptLine {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(text, "text");
    }

    public TranscriptLine(String time, String text, @Nullable FlagSeverity severity) {
        this(time, text, severity, null);
    }

    public boolean flagged() {
        return severity != null;
    }

    public boolean fromNearbyPlayer() {
        return speaker != null;
    }

    public static TranscriptLine of(TranscriptEntry entry, ZoneId zone) {
        return new TranscriptLine(format(entry.timestamp(), zone), entry.text(), entry.severity());
    }

    public static TranscriptLine of(ConversationLine line, ZoneId zone) {
        return new TranscriptLine(format(line.timestamp(), zone), line.text(), line.severity(), line.playerName());
    }

    public static List<TranscriptLine> of(List<TranscriptEntry> entries, ZoneId zone) {
        return entries.stream().map(entry -> of(entry, zone)).toList();
    }

    public static List<TranscriptLine> interleave(List<TranscriptEntry> own, List<ConversationLine> nearby, ZoneId zone) {
        record Timed(Instant at, int source, TranscriptLine line) {
        }
        List<Timed> timed = new ArrayList<>(own.size() + nearby.size());
        own.forEach(entry -> timed.add(new Timed(entry.timestamp(), 0, of(entry, zone))));
        nearby.forEach(line -> timed.add(new Timed(line.timestamp(), 1, of(line, zone))));
        timed.sort(Comparator.comparing(Timed::at).thenComparingInt(Timed::source));
        return timed.stream().map(Timed::line).toList();
    }

    public String toSnapshotLine() {
        String prefix = speaker == null ? "" : "[" + speaker + "] ";
        String flag = severity == null ? "" : "[" + severity.name() + "] ";
        return prefix + "[" + time + "] " + flag + text.replace('\n', ' ');
    }

    public static String toSnapshot(List<TranscriptLine> lines) {
        StringBuilder out = new StringBuilder();
        for (TranscriptLine line : lines) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line.toSnapshotLine());
        }
        return out.toString();
    }

    public static List<TranscriptLine> parseSnapshot(String snapshot) {
        List<TranscriptLine> lines = new ArrayList<>();
        for (String raw : snapshot.split("\n")) {
            if (raw.isBlank()) {
                continue;
            }
            Matcher speakerLine = SPEAKER_LINE.matcher(raw);
            Matcher ownLine = SNAPSHOT_LINE.matcher(raw);
            if (speakerLine.matches()) {
                lines.add(new TranscriptLine(speakerLine.group(2), speakerLine.group(4), severity(speakerLine.group(3)), speakerLine.group(1)));
            } else if (ownLine.matches()) {
                lines.add(new TranscriptLine(ownLine.group(1), ownLine.group(3), severity(ownLine.group(2))));
            } else {
                lines.add(new TranscriptLine("", raw, null));
            }
        }
        return lines;
    }

    private static String format(Instant timestamp, ZoneId zone) {
        return TIME.format(timestamp.atZone(zone));
    }

    private static @Nullable FlagSeverity severity(@Nullable String name) {
        return name == null ? null : FlagSeverity.valueOf(name);
    }
}
