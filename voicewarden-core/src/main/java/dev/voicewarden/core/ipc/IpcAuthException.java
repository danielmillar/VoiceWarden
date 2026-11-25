package dev.voicewarden.core.ipc;

import java.io.IOException;

public class IpcAuthException extends IOException {

    public IpcAuthException(String message) {
        super(message);
    }
}
