package dev.voicewarden.core.storage;

import dev.voicewarden.api.FlagSeverity;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StrikeRecord(
        long id,
        UUID playerId,
        String playerName,
        FlagSeverity severity,
        String matchedWords,
        String transcript,
        Instant createdAt,
        String serverId,
        boolean pardoned) {

    public StrikeRecord {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(matchedWords, "matchedWords");
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(serverId, "serverId");
    }

    public static StrikeRecord unsaved(
            UUID playerId,
            String playerName,
            FlagSeverity severity,
            String matchedWords,
            String transcript,
            Instant createdAt,
            String serverId) {
        return new StrikeRecord(0L, playerId, playerName, severity, matchedWords, transcript, createdAt, serverId, false);
    }

    public StrikeRecord withId(long newId) {
        return new StrikeRecord(newId, playerId, playerName, severity, matchedWords, transcript, createdAt, serverId, pardoned);
    }

    public StrikeRecord withPardoned(boolean newPardoned) {
        return new StrikeRecord(id, playerId, playerName, severity, matchedWords, transcript, createdAt, serverId, newPardoned);
    }
}
