package dev.danielmillar.nevusvoice.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.*;

import static org.junit.jupiter.api.Assertions.*;

class WavEncoderTest {
    @ParameterizedTest @ValueSource(ints = {0, 1, 16000})
    void roundTripsWithJavaSound(int count) throws Exception {
        short[] pcm = new short[count];
        for (int index = 0; index < count; index++) pcm[index] = (short) (index * 7919);
        byte[] encoded = WavEncoder.encode(pcm, 16000);
        assertEquals(WavEncoder.byteSize(count), encoded.length);
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(encoded))) {
            AudioFormat format = stream.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
            assertEquals(16000, format.getSampleRate());
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(1, format.getChannels());
            assertFalse(format.isBigEndian());
            assertEquals(count, stream.getFrameLength());
            ByteBuffer buffer = ByteBuffer.wrap(stream.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            for (short sample : pcm) assertEquals(sample, buffer.getShort());
        }
    }

    @Test void writesOneBulkBufferWithoutClosingStream() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void write(int value) { fail("Per-byte write"); }
            @Override public void close() { fail("Caller owns stream"); }
        };
        short[] pcm = {-32768, -1, 0, 1, 32767};
        WavEncoder.write(output, pcm, 48000);
        assertArrayEquals(WavEncoder.encode(pcm, 48000), output.toByteArray());
    }

    @Test void rejectsInvalidSizesAndRates() {
        assertThrows(IllegalArgumentException.class, () -> WavEncoder.byteSize(-1));
        assertThrows(ArithmeticException.class, () -> WavEncoder.byteSize(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> WavEncoder.encode(new short[0], 0));
        assertThrows(IllegalArgumentException.class, () -> WavEncoder.encode(new short[0], Integer.MAX_VALUE));
    }
}
