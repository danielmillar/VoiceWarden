package dev.voicewarden.core.audio;

import io.github.jaredmdobson.OpusDecoder;
import io.github.jaredmdobson.OpusException;

public final class ConcentusOpusAudioDecoder implements OpusAudioDecoder {

    private final OpusDecoder decoder;
    private int lastFrameSamples = AudioFormat.OPUS_FRAME_20MS_48K;

    public ConcentusOpusAudioDecoder() {
        try {
            this.decoder = new OpusDecoder(AudioFormat.SAMPLE_RATE_48K, 1);
        } catch (OpusException e) {
            throw new IllegalStateException("Concentus refused a 48 kHz mono decoder", e);
        }
    }

    @Override
    public int decode(byte[] packet, int offset, int length, short[] out48k) {
        if (packet == null || length <= 0) {
            return conceal(out48k);
        }
        try {
            int samples = decoder.decode(packet, offset, length, out48k, 0, maxFrame(out48k), false);
            if (samples <= 0) {
                return DECODE_FAILED;
            }
            lastFrameSamples = samples;
            return samples;
        } catch (OpusException | RuntimeException e) {
            return DECODE_FAILED;
        }
    }

    @Override
    public int conceal(short[] out48k) {
        int frame = Math.min(lastFrameSamples, maxFrame(out48k));
        try {
            int samples = decoder.decode(null, 0, 0, out48k, 0, frame, false);
            return Math.max(0, samples);
        } catch (OpusException | RuntimeException e) {
            decoder.resetState();
            return 0;
        }
    }

    @Override
    public void reset() {
        decoder.resetState();
        lastFrameSamples = AudioFormat.OPUS_FRAME_20MS_48K;
    }

    private static int maxFrame(short[] out48k) {
        return Math.min(out48k.length, AudioFormat.MAX_FRAME_SAMPLES_48K);
    }
}
