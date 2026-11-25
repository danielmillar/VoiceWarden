package dev.voicewarden.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public record VoiceMuteRecord(
        UUID playerId,
        String playerName,
        String reason,
        String moderator,
        Instant createdAt,
        @Nullable Instant expiresAt,
        boolean active,
        String serverId) {

    public VoiceMuteRecord {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(serverId, "serverId");
    }

    public boolean isPermanent() {
        return expiresAt == null;
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isEffectivelyActive(Instant now) {
        return active && !isExpired(now);
    }

    public Optional<Duration> remainingDuration(Instant now) {
        Objects.requireNonNull(now, "now");
        if (expiresAt == null || !isEffectivelyActive(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, expiresAt));
    }

    public VoiceMuteRecord withActive(boolean newActive) {
        return new VoiceMuteRecord(playerId, playerName, reason, moderator, createdAt, expiresAt, newActive, serverId);
    }
}
