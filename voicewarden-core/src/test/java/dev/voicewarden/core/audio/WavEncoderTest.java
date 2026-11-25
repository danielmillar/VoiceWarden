package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WavEncoderTest {

    @Test
    void writesCanonicalPcm16MonoHeader() {
        short[] samples = {0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE};
        byte[] wav = WavEncoder.encodePcm16Mono(samples, 16_000);
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(44 + samples.length * 2, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(36 + samples.length * 2, buf.getInt(4));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(wav, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(16, buf.getInt(16));
        assertEquals(1, buf.getShort(20));
        assertEquals(1, buf.getShort(22));
        assertEquals(16_000, buf.getInt(24));
        assertEquals(32_000, buf.getInt(28));
        assertEquals(2, buf.getShort(32));
        assertEquals(16, buf.getShort(34));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(samples.length * 2, buf.getInt(40));
        assertEquals(Short.MAX_VALUE, buf.getShort(44 + 6));
        assertEquals(Short.MIN_VALUE, buf.getShort(44 + 8));
    }

    @Test
    void roundTripsSamples() {
        short[] samples = TestSignals.sine(440, 16_000, 16_000, 20_000, 0);
        byte[] wav = WavEncoder.encodePcm16Mono(samples, 16_000);
        assertArrayEquals(samples, WavEncoder.decodePcm16Mono(wav));
        assertEquals(16_000, WavEncoder.sampleRate(wav));
    }

    @Test
    void finalUtteranceExportsWav() {
        short[] pcm = TestSignals.sine(300, 16_000, 8_000, 5_000, 0);
        FinalUtterance utterance = new FinalUtterance(java.util.UUID.randomUUID(), 1, pcm, 500);
        assertArrayEquals(pcm, WavEncoder.decodePcm16Mono(utterance.toWav()));
    }

    @Test
    void rejectsNonWaveInput() {
        assertThrows(IllegalArgumentException.class, () -> WavEncoder.decodePcm16Mono(new byte[60]));
        byte[] wav = WavEncoder.encodePcm16Mono(new short[4], 16_000);
        wav[22] = 2;
        assertThrows(IllegalArgumentException.class, () -> WavEncoder.decodePcm16Mono(wav));
    }
}
