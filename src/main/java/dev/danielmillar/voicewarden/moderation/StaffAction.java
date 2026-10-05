package dev.danielmillar.voicewarden.moderation;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A mute or unmute that did not come from automatic detection: a staff command, the console, or a mute expiring.
 *
 * @param actor    staff name, "Console", or "System" for expiries
 * @param duration non-null only for {@link Type#MUTE}
 * @param reason   optional free-text reason
 */
public record StaffAction(
        Type type,
        UUID targetId,
        String targetName,
        String actor,
        @Nullable Duration duration,
        @Nullable String reason,
        Instant timestamp
) {
    public enum Type {
        MUTE,
        UNMUTE
    }
}
