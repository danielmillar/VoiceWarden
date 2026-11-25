package dev.voicewarden.core.audio;

import io.github.jaredmdobson.OpusApplication;
import io.github.jaredmdobson.OpusEncoder;
import io.github.jaredmdobson.OpusException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class TestSignals {

    private TestSignals() {
    }

    static short[] sine(double frequency, int sampleRate, int samples, double amplitude, int startSample) {
        short[] out = new short[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = (short) Math.round(amplitude * Math.sin(2.0 * Math.PI * frequency * (startSample + i) / sampleRate));
        }
        return out;
    }

    static short[] concat(short[]... parts) {
        int total = 0;
        for (short[] part : parts) {
            total += part.length;
        }
        short[] out = new short[total];
        int pos = 0;
        for (short[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }

    static double rms(short[] pcm, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) pcm[i] * pcm[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }

    static double goertzelPower(short[] pcm, int from, int to, double frequency, int sampleRate) {
        double coeff = 2.0 * Math.cos(2.0 * Math.PI * frequency / sampleRate);
        double s1 = 0;
        double s2 = 0;
        for (int i = from; i < to; i++) {
            double s0 = pcm[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        int n = to - from;
        double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return 2.0 * power / ((double) n * n);
    }

    static OpusEncoder voipEncoder() {
        try {
            OpusEncoder encoder = new OpusEncoder(AudioFormat.SAMPLE_RATE_48K, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(32_000);
            encoder.setComplexity(5);
            return encoder;
        } catch (OpusException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<byte[]> encodeOpus(short[] pcm48k) {
        OpusEncoder encoder = voipEncoder();
        List<byte[]> packets = new ArrayList<>();
        byte[] scratch = new byte[1276];
        int frame = AudioFormat.OPUS_FRAME_20MS_48K;
        for (int pos = 0; pos + frame <= pcm48k.length; pos += frame) {
            try {
                int len = encoder.encode(pcm48k, pos, frame, scratch, 0, scratch.length);
                packets.add(Arrays.copyOf(scratch, len));
            } catch (OpusException e) {
                throw new IllegalStateException(e);
            }
        }
        return packets;
    }

    static long allocatedBytes(Thread thread) {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        return bean.getThreadAllocatedBytes(thread.threadId());
    }

    static final class RecordingSink implements AudioEventSink {
        final List<LiveAudioChunk> chunks = new CopyOnWriteArrayList<>();
        final List<FinalUtterance> finals = new CopyOnWriteArrayList<>();
        final List<AcousticAbuseEvent> abuse = new CopyOnWriteArrayList<>();

        @Override
        public void onLiveChunk(LiveAudioChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onAcousticAbuse(AcousticAbuseEvent event) {
            abuse.add(event);
        }

        @Override
        public void onFinalUtterance(FinalUtterance utterance) {
            finals.add(utterance);
        }
    }

    static class PassThroughDecoder implements OpusAudioDecoder {
        int resets;

        @Override
        public int decode(byte[] packet, int offset, int length, short[] out48k) {
            if (length == 1 && packet[offset] == (byte) 0xFF) {
                return DECODE_FAILED;
            }
            int samples = length / 2;
            for (int i = 0; i < samples; i++) {
                out48k[i] = (short) ((packet[offset + 2 * i] & 0xFF) | packet[offset + 2 * i + 1] << 8);
            }
            return samples;
        }

        @Override
        public int conceal(short[] out48k) {
            Arrays.fill(out48k, 0, AudioFormat.OPUS_FRAME_20MS_48K, (short) 0);
            return AudioFormat.OPUS_FRAME_20MS_48K;
        }

        @Override
        public void reset() {
            resets++;
        }
    }
}
