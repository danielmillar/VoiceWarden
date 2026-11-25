package dev.voicewarden.core.audio;

public interface AudioEventSink {

    void onLiveChunk(LiveAudioChunk chunk);

    void onFinalUtterance(FinalUtterance utterance);

    default void onAcousticAbuse(AcousticAbuseEvent event) {
    }
}
