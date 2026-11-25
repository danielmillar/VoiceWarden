package dev.voicewarden.engine;

import java.io.IOException;

public final class ModelUnavailableException extends IOException {
    private static final long serialVersionUID = 1L;

    public ModelUnavailableException(String message) {
        super(message);
    }
}
