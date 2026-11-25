package dev.voicewarden.core.moderation;

import dev.voicewarden.api.FlagSeverity;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConversationLine(UUID playerId, String playerName, Instant timestamp, String text, FlagSeverity severity) {

    public ConversationLine {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(text, "text");
    }

    public static ConversationLine of(UUID playerId, String playerName, TranscriptEntry entry) {
        return new ConversationLine(playerId, playerName, entry.timestamp(), entry.text(), entry.severity());
    }

    public boolean flagged() {
        return severity != null;
    }
}
