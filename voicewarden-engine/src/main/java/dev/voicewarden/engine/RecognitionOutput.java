package dev.voicewarden.engine;

import dev.voicewarden.api.ConfirmationState;
import java.util.Objects;

public record RecognitionOutput(String text, String language, ConfirmationState confirmation, String verifierText) {

    public RecognitionOutput {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(confirmation, "confirmation");
        Objects.requireNonNull(verifierText, "verifierText");
    }

    public static RecognitionOutput unchecked(String text, String language) {
        return new RecognitionOutput(text, language, ConfirmationState.UNCHECKED, "");
    }

    public boolean isBlank() {
        return text.isBlank();
    }
}
