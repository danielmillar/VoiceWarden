package dev.danielmillar.voicewarden.moderation;

/** A player's automatic-mute history for the mute ladder. */
public record Offenses(int count, long lastEpochMillis) {
    public static final Offenses NONE = new Offenses(0, 0);
}
