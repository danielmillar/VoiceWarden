package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConcentusOpusAudioDecoderTest {

    private static final int FRAME = AudioFormat.OPUS_FRAME_20MS_48K;

    @Test
    void decodesEncodedSineWithHighFidelity() {
        short[] input = TestSignals.sine(1000, AudioFormat.SAMPLE_RATE_48K, FRAME * 50, 10_000, 0);
        List<byte[]> packets = TestSignals.encodeOpus(input);
        assertEquals(50, packets.size());

        ConcentusOpusAudioDecoder decoder = new ConcentusOpusAudioDecoder();
        short[] frame = new short[AudioFormat.MAX_FRAME_SAMPLES_48K];
        short[] output = new short[input.length];
        int written = 0;
        for (byte[] packet : packets) {
            int samples = decoder.decode(packet, 0, packet.length, frame);
            assertEquals(FRAME, samples);
            System.arraycopy(frame, 0, output, written, samples);
            written += samples;
        }

        int from = FRAME * 10;
        double inputRms = TestSignals.rms(input, from, output.length);
        double outputRms = TestSignals.rms(output, from, output.length);
        assertEquals(inputRms, outputRms, inputRms * 0.15, "decoded RMS");
        double tone = TestSignals.goertzelPower(output, from, output.length, 1000, AudioFormat.SAMPLE_RATE_48K);
        assertTrue(tone > 0.9 * outputRms * outputRms, "1 kHz should dominate the decoded signal");
        assertTrue(bestCorrelation(input, output, from) > 0.95, "decoded waveform should track the input");
    }

    @Test
    void corruptPacketReportsFailureAndDecoderRecovers() {
        short[] input = TestSignals.sine(440, AudioFormat.SAMPLE_RATE_48K, FRAME * 10, 8_000, 0);
        List<byte[]> packets = TestSignals.encodeOpus(input);
        ConcentusOpusAudioDecoder decoder = new ConcentusOpusAudioDecoder();
        short[] frame = new short[AudioFormat.MAX_FRAME_SAMPLES_48K];

        assertEquals(FRAME, decoder.decode(packets.get(0), 0, packets.get(0).length, frame));
        byte[] zeroFrameCode3 = {(byte) 0x03, (byte) 0x00};
        assertEquals(OpusAudioDecoder.DECODE_FAILED, decoder.decode(zeroFrameCode3, 0, zeroFrameCode3.length, frame));
        assertEquals(FRAME, decoder.conceal(frame));
        assertEquals(FRAME, decoder.decode(packets.get(1), 0, packets.get(1).length, frame));
    }

    @Test
    void missingPacketIsConcealedAtLastFrameSize() {
        ConcentusOpusAudioDecoder decoder = new ConcentusOpusAudioDecoder();
        short[] frame = new short[AudioFormat.MAX_FRAME_SAMPLES_48K];
        assertEquals(FRAME, decoder.decode(null, 0, 0, frame));
        assertEquals(FRAME, decoder.conceal(frame));
    }

    private static double bestCorrelation(short[] reference, short[] decoded, int from) {
        double best = 0;
        for (int lag = 0; lag < 960; lag++) {
            double dot = 0;
            double a = 0;
            double b = 0;
            for (int i = from; i + lag < decoded.length; i++) {
                dot += (double) reference[i] * decoded[i + lag];
                a += (double) reference[i] * reference[i];
                b += (double) decoded[i + lag] * decoded[i + lag];
            }
            best = Math.max(best, dot / Math.sqrt(a * b));
        }
        return best;
    }
}
