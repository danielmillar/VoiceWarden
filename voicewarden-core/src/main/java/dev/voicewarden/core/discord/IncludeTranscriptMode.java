package dev.voicewarden.core.discord;

import java.util.Locale;

public enum IncludeTranscriptMode {
    FLAGGED,
    MUTED,
    BOTH,
    NONE;

    public boolean includesFlagged() {
        return this == FLAGGED || this == BOTH;
    }

    public boolean includesMuted() {
        return this == MUTED || this == BOTH;
    }

    public static IncludeTranscriptMode parse(String value) {
        if (value == null || value.isBlank()) {
            return BOTH;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (IncludeTranscriptMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Unknown include_transcript '" + value + "'. Use flagged, muted, both or none.");
    }

    public static IncludeTranscriptMode fromFlag(boolean includeTranscript, IncludeTranscriptMode whenTrue) {
        return includeTranscript ? whenTrue : NONE;
    }
}
