package dev.voicewarden.core.audio;

import java.util.UUID;

public final class PooledOpusFrame {

    private final byte[] opusData;
    private int opusLength;
    private short[] pcm48kData;
    private int pcmLength;
    private UUID playerId;
    private long timestampMs;
    private boolean voiceStopped;
    private FrameKind kind;

    PooledOpusFrame(int maxOpusBytes) {
        this.opusData = new byte[maxOpusBytes];
    }

    void fillOpus(UUID player, byte[] source, int offset, int length, long timestamp) {
        System.arraycopy(source, offset, opusData, 0, length);
        opusLength = length;
        pcmLength = 0;
        playerId = player;
        timestampMs = timestamp;
        voiceStopped = false;
        kind = FrameKind.OPUS;
    }

    void fillPcm48k(UUID player, short[] source, int offset, int length, long timestamp) {
        if (pcm48kData == null || pcm48kData.length < length) {
            pcm48kData = new short[Math.max(length, AudioFormat.MAX_FRAME_SAMPLES_48K)];
        }
        System.arraycopy(source, offset, pcm48kData, 0, length);
        pcmLength = length;
        opusLength = 0;
        playerId = player;
        timestampMs = timestamp;
        voiceStopped = false;
        kind = FrameKind.PCM_48K;
    }

    void fillSpeakingStopped(UUID player, long timestamp) {
        opusLength = 0;
        pcmLength = 0;
        playerId = player;
        timestampMs = timestamp;
        voiceStopped = true;
        kind = FrameKind.SPEAKING_STOPPED;
    }

    void reset() {
        opusLength = 0;
        pcmLength = 0;
        playerId = null;
        timestampMs = 0L;
        voiceStopped = false;
        kind = null;
    }

    public byte[] opusData() {
        return opusData;
    }

    public int opusLength() {
        return opusLength;
    }

    public short[] pcm48kData() {
        return pcm48kData;
    }

    public int pcmLength() {
        return pcmLength;
    }

    public UUID playerId() {
        return playerId;
    }

    public long timestampMs() {
        return timestampMs;
    }

    public boolean voiceStopped() {
        return voiceStopped;
    }

    public FrameKind kind() {
        return kind;
    }

    public int maxOpusBytes() {
        return opusData.length;
    }
}
