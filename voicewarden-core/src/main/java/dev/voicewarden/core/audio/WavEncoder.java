package dev.voicewarden.core.audio;

public final class WavEncoder {

    public static final int HEADER_BYTES = 44;

    private static final int PCM_FORMAT = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int FMT_CHUNK_BYTES = 16;

    private WavEncoder() {
    }

    public static byte[] encodePcm16Mono(short[] samples, int sampleRate) {
        return encodePcm16Mono(samples, 0, samples.length, sampleRate);
    }

    public static byte[] encodePcm16Mono(short[] samples, int offset, int length, int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got " + sampleRate);
        }
        if (offset < 0 || length < 0 || offset + length > samples.length) {
            throw new IllegalArgumentException("Range " + offset + "+" + length + " is outside " + samples.length + " samples");
        }
        int dataBytes = length * BYTES_PER_SAMPLE;
        byte[] out = new byte[HEADER_BYTES + dataBytes];
        writeAscii(out, 0, "RIFF");
        writeIntLe(out, 4, 36 + dataBytes);
        writeAscii(out, 8, "WAVE");
        writeAscii(out, 12, "fmt ");
        writeIntLe(out, 16, FMT_CHUNK_BYTES);
        writeShortLe(out, 20, PCM_FORMAT);
        writeShortLe(out, 22, 1);
        writeIntLe(out, 24, sampleRate);
        writeIntLe(out, 28, sampleRate * BYTES_PER_SAMPLE);
        writeShortLe(out, 32, BYTES_PER_SAMPLE);
        writeShortLe(out, 34, BITS_PER_SAMPLE);
        writeAscii(out, 36, "data");
        writeIntLe(out, 40, dataBytes);
        int pos = HEADER_BYTES;
        for (int i = offset; i < offset + length; i++) {
            short s = samples[i];
            out[pos++] = (byte) s;
            out[pos++] = (byte) (s >> 8);
        }
        return out;
    }

    public static short[] decodePcm16Mono(byte[] wavBytes) {
        if (wavBytes.length < HEADER_BYTES || !ascii(wavBytes, 0, "RIFF") || !ascii(wavBytes, 8, "WAVE")) {
            throw new IllegalArgumentException("Not a RIFF/WAVE file");
        }
        int pos = 12;
        boolean formatSeen = false;
        while (pos + 8 <= wavBytes.length) {
            int chunkSize = readIntLe(wavBytes, pos + 4);
            int body = pos + 8;
            if (chunkSize < 0 || body + chunkSize > wavBytes.length) {
                throw new IllegalArgumentException("WAVE chunk at byte " + pos + " overruns the file");
            }
            if (ascii(wavBytes, pos, "fmt ")) {
                requireMonoPcm16(wavBytes, body, chunkSize);
                formatSeen = true;
            } else if (ascii(wavBytes, pos, "data")) {
                if (!formatSeen) {
                    throw new IllegalArgumentException("WAVE data chunk appears before the fmt chunk");
                }
                short[] samples = new short[chunkSize / BYTES_PER_SAMPLE];
                for (int i = 0; i < samples.length; i++) {
                    int b = body + i * BYTES_PER_SAMPLE;
                    samples[i] = (short) ((wavBytes[b] & 0xFF) | (wavBytes[b + 1] << 8));
                }
                return samples;
            }
            pos = body + chunkSize + (chunkSize & 1);
        }
        throw new IllegalArgumentException("WAVE file has no data chunk");
    }

    public static int sampleRate(byte[] wavBytes) {
        return readIntLe(wavBytes, 24);
    }

    private static void requireMonoPcm16(byte[] wav, int body, int size) {
        if (size < FMT_CHUNK_BYTES) {
            throw new IllegalArgumentException("WAVE fmt chunk is too short: " + size + " bytes");
        }
        int format = readShortLe(wav, body);
        int channels = readShortLe(wav, body + 2);
        int bits = readShortLe(wav, body + 14);
        if (format != PCM_FORMAT || channels != 1 || bits != BITS_PER_SAMPLE) {
            throw new IllegalArgumentException("Expected PCM16 mono, got format=" + format
                    + " channels=" + channels + " bits=" + bits);
        }
    }

    private static boolean ascii(byte[] data, int offset, String tag) {
        for (int i = 0; i < tag.length(); i++) {
            if (data[offset + i] != tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static void writeAscii(byte[] out, int offset, String tag) {
        for (int i = 0; i < tag.length(); i++) {
            out[offset + i] = (byte) tag.charAt(i);
        }
    }

    private static void writeIntLe(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >> 8);
        out[offset + 2] = (byte) (value >> 16);
        out[offset + 3] = (byte) (value >> 24);
    }

    private static void writeShortLe(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >> 8);
    }

    private static int readIntLe(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }

    private static int readShortLe(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }
}
