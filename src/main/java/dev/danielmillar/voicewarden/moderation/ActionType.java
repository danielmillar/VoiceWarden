package dev.danielmillar.voicewarden.moderation;

/** Punishment applied in response to a detected violation. */
public enum ActionType {
    /** Flagged and reported to staff, but no punishment (below the first ladder step, or dry-run). */
    NONE,
    /** The player is warned. */
    WARN,
    /** The player's voice is muted for {@link ModerationAction#duration()}. */
    MUTE
}
