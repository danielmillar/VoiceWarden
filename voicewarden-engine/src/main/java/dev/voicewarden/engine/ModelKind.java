package dev.voicewarden.engine;

public enum ModelKind {
    ONLINE_TRANSDUCER(true),
    OFFLINE_TRANSDUCER(false),
    OFFLINE_MOONSHINE(false),
    OFFLINE_WHISPER(false),
    SILERO_VAD(false);

    private final boolean streaming;

    ModelKind(boolean streaming) {
        this.streaming = streaming;
    }

    public boolean streaming() {
        return streaming;
    }
}
