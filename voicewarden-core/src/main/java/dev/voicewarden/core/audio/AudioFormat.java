package dev.voicewarden.core.audio;

public final class AudioFormat {

    public static final int SAMPLE_RATE_48K = 48_000;
    public static final int SAMPLE_RATE_16K = 16_000;
    public static final int SAMPLES_PER_MS_16K = SAMPLE_RATE_16K / 1000;
    public static final int SAMPLES_PER_MS_48K = SAMPLE_RATE_48K / 1000;
    public static final int DECIMATION_FACTOR = SAMPLE_RATE_48K / SAMPLE_RATE_16K;
    public static final int OPUS_FRAME_20MS_48K = 960;
    public static final int MAX_FRAME_SAMPLES_48K = 2880;
    public static final int MAX_FRAME_SAMPLES_16K = MAX_FRAME_SAMPLES_48K / DECIMATION_FACTOR;

    private AudioFormat() {
    }

    public static long durationMs16k(int samples) {
        return samples * 1000L / SAMPLE_RATE_16K;
    }
}
