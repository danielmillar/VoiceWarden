package dev.voicewarden.core.audio;

public interface OpusAudioDecoder extends AutoCloseable {

    int DECODE_FAILED = -1;

    int decode(byte[] packet, int offset, int length, short[] out48k);

    int conceal(short[] out48k);

    void reset();

    @Override
    default void close() {
    }
}
