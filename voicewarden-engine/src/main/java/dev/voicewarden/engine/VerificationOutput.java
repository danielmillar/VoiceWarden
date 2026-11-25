package dev.voicewarden.engine;

import dev.voicewarden.api.ConfirmationState;
import java.util.Objects;

public record VerificationOutput(ConfirmationState state, String verifierText, String language) {

    public VerificationOutput {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(verifierText, "verifierText");
        Objects.requireNonNull(language, "language");
    }

    public static VerificationOutput unchecked(String language) {
        return new VerificationOutput(ConfirmationState.UNCHECKED, "", language);
    }
}
