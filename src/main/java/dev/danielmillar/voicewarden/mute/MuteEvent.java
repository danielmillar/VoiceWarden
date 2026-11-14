package dev.danielmillar.voicewarden.mute;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One line of a player's mute history ({@code mute_events}).
 *
 * @param at        epoch millis
 * @param expiresAt epoch millis or {@link MuteService#PERMANENT} for {@link Type#MUTE}; {@code null} otherwise
 */
public record MuteEvent(UUID playerId, String playerName, Type type, long at, String actor, String reason,
                        @Nullable Long expiresAt, boolean automatic) {

    public enum Type {
        MUTE, UNMUTE, EXPIRE, PARDON;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One page of history, newest first. {@code pages} is 0 when the player has no history. */
    public record Page(List<MuteEvent> events, int page, int pages) {
        public boolean more() {
            return page < pages;
        }

        /** The page "newer" opens: the previous one, or the last page when this one is past the end. */
        public int newer() {
            return Math.min(page - 1, Math.max(1, pages));
        }
    }

    static MuteEvent muted(MuteService.Mute mute) {
        return new MuteEvent(mute.playerId(), mute.playerName(), Type.MUTE, mute.createdAt(), mute.actor(), mute.reason(),
                mute.expiresAt(), mute.automatic());
    }

    /** The length of a {@link Type#MUTE}; {@code null} when permanent or not a mute. */
    public @Nullable Duration duration() {
        return expiresAt == null || expiresAt == MuteService.PERMANENT ? null : Duration.ofMillis(expiresAt - at);
    }
}
