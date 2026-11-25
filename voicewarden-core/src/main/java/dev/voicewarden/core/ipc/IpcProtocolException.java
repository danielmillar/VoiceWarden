package dev.voicewarden.core.ipc;

import java.io.IOException;

public class IpcProtocolException extends IOException {

    public IpcProtocolException(String message) {
        super(message);
    }

    public IpcProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
