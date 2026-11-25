package dev.voicewarden.engine;

import java.time.Duration;
import java.util.UUID;

public interface SpeechRecognizerEngine extends AutoCloseable {

    int SAMPLE_RATE = 16_000;

    boolean supportsStreaming();

    boolean hasDoubleCheckVerifier();

    RecognitionOutput feedLiveChunk(UUID playerId, long utteranceId, short[] pcm16k, boolean endOfUtterance);

    RecognitionOutput transcribeFinal(UUID playerId, long utteranceId, short[] pcm16k, boolean requestDoubleCheck);

    VerificationOutput verifyUtterance(UUID playerId, long utteranceId, String primaryTranscript, short[] pcm16k);

    void dropUtterance(UUID playerId, long utteranceId);

    default void clearAllStreams() {
    }

    default int evictIdleStreams(Duration maxIdle) {
        return 0;
    }

    default int activeStreams() {
        return 0;
    }

    @Override
    void close();

    static float[] toFloat(short[] pcm16) {
        float[] out = new float[pcm16.length];
        for (int i = 0; i < pcm16.length; i++) {
            out[i] = pcm16[i] / 32768.0f;
        }
        return out;
    }
}
