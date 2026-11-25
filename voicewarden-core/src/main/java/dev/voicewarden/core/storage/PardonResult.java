package dev.voicewarden.core.storage;

public record PardonResult(int strikesPardoned, int mutesDeactivated) {

    public boolean changedAnything() {
        return strikesPardoned > 0 || mutesDeactivated > 0;
    }
}
