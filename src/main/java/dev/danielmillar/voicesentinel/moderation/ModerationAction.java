package dev.danielmillar.voicesentinel.moderation;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * An action decided by the punishment ladder.
 *
 * @param type     action kind
 * @param duration mute length; non-null only for {@link ActionType#MUTE}
 */
public record ModerationAction(ActionType type, @Nullable Duration duration) {

    public static final ModerationAction NONE = new ModerationAction(ActionType.NONE, null);
    public static final ModerationAction WARN = new ModerationAction(ActionType.WARN, null);

    public ModerationAction {
        Objects.requireNonNull(type, "type");
        if (type == ActionType.MUTE) {
            Objects.requireNonNull(duration, "mute duration");
        } else {
            duration = null;
        }
    }

    public static ModerationAction mute(Duration duration) {
        return new ModerationAction(ActionType.MUTE, duration);
    }
}
