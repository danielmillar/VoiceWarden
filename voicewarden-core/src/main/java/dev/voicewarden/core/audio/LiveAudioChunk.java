package dev.voicewarden.core.audio;

import java.util.UUID;

public record LiveAudioChunk(UUID playerId, long utteranceId, int chunkIndex, short[] pcm16k, boolean endOfUtterance) {

    public long durationMs() {
        return AudioFormat.durationMs16k(pcm16k.length);
    }
}
