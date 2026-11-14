package dev.danielmillar.voicewarden.mute;

import java.util.List;

/**
 * What a voice mute blocks, by destination. Simple Voice Chat sends a player's audio to proximity (or whisper range)
 * when they are in no group, to their group only in a normal or isolated group, and to both in an open group.
 *
 * @param allowedGroups group names (case-insensitive) a muted player can always talk in
 */
public record MuteScope(boolean proximity, boolean whisper, Groups groups, List<String> allowedGroups) {

    /** Which voice chat groups a mute blocks. */
    public enum Groups { ALL, OPEN_ONLY, NONE }

    public static final MuteScope EVERYTHING = new MuteScope(true, true, Groups.ALL, List.of());

    public MuteScope {
        allowedGroups = List.copyOf(allowedGroups);
    }

    /** True when a mute silences the player everywhere, which is what the LuckPerms speak denial does too. */
    public boolean blocksEverything() {
        return proximity && whisper && groups == Groups.ALL && allowedGroups.isEmpty();
    }

    /** Proximity audio, or whisper audio when {@code whispering}. */
    public boolean blocksNearby(boolean whispering) {
        return whispering ? whisper : proximity;
    }

    /** Hot path: no allocation; {@code allowedGroups} is a short operator-written list. */
    public boolean blocksGroup(String name, boolean open) {
        for (String allowed : allowedGroups) {
            if (allowed.equalsIgnoreCase(name)) {
                return false;
            }
        }
        return switch (groups) {
            case ALL -> true;
            case OPEN_ONLY -> open;
            case NONE -> false;
        };
    }
}
