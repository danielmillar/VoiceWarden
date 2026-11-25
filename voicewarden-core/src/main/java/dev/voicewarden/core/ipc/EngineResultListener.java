package dev.voicewarden.core.ipc;

public interface EngineResultListener {

    EngineResultListener NONE = new EngineResultListener() { };

    default void onTranscript(IpcMessage.TranscriptResult result) {
    }

    default void onPong(long nonce, double roundTripMs) {
    }

    default void onStatus(IpcMessage.EngineStatusResponse status) {
    }

    default void onDisconnected(Throwable cause) {
    }
}
