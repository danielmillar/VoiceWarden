package dev.voicewarden.core.moderation;

import dev.voicewarden.api.FlagSeverity;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record TranscriptEntry(Instant timestamp, String text, boolean flagged, FlagSeverity severity) {

    public TranscriptEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(text, "text");
        if (flagged != (severity != null)) {
            throw new IllegalArgumentException("A flagged entry needs a severity and an unflagged entry must not have one");
        }
    }

    public static TranscriptEntry clean(Instant timestamp, String text) {
        return new TranscriptEntry(timestamp, text, false, null);
    }

    public static TranscriptEntry flagged(Instant timestamp, String text, FlagSeverity severity) {
        return new TranscriptEntry(timestamp, text, true, Objects.requireNonNull(severity, "severity"));
    }

    public Optional<FlagSeverity> severityIfFlagged() {
        return Optional.ofNullable(severity);
    }
}
