package dev.danielmillar.voicesentinel.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Encodes signed, mono PCM as an uncompressed 16-bit RIFF/WAVE file. */
public final class WavEncoder {
    private WavEncoder() {}

    public static int byteSize(int samples) {
        if (samples < 0) throw new IllegalArgumentException("Negative sample count");
        return Math.addExact(44, Math.multiplyExact(samples, Short.BYTES));
    }

    public static byte[] encode(short[] pcm, int sampleRate) {
        Objects.requireNonNull(pcm, "pcm");
        if (sampleRate <= 0 || sampleRate > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid sample rate");
        }
        ByteBuffer buffer = ByteBuffer.allocate(byteSize(pcm.length)).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46464952).putInt(buffer.capacity() - 8).putInt(0x45564157); // RIFF, WAVE
        buffer.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1); // fmt, PCM, mono
        buffer.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        buffer.putInt(0x61746164).putInt(pcm.length * 2); // data
        buffer.asShortBuffer().put(pcm);
        return buffer.array();
    }

    /** Makes one bulk write; the owner of the stream controls flushing and closing. */
    public static void write(OutputStream out, short[] pcm, int sampleRate) throws IOException {
        Objects.requireNonNull(out, "out").write(encode(pcm, sampleRate));
    }
}
