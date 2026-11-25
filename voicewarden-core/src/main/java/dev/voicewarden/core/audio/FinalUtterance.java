package dev.voicewarden.core.audio;

import java.util.UUID;

public record FinalUtterance(UUID playerId, long utteranceId, short[] pcm16k, long durationMs) {

    public byte[] toWav() {
        return WavEncoder.encodePcm16Mono(pcm16k, AudioFormat.SAMPLE_RATE_16K);
    }
}
