package dev.danielmillar.voicewarden.moderation;

/** Where the speaker was when the utterance started. */
public record SpeakerLocation(String world, double x, double y, double z) {

    public String formatBlock() {
        return world + " " + (long) Math.floor(x) + ", " + (long) Math.floor(y) + ", " + (long) Math.floor(z);
    }
}
