package dev.voicewarden.core.audio;

public record AudioConfig(
        int silenceThresholdMs,
        int minAudioDurationMs,
        int maxUtteranceSeconds,
        boolean liveDetection,
        int liveChunkMs,
        int preRollMs,
        double vadRmsThreshold,
        int workerThreads,
        int workerQueueCapacity,
        int framePoolCapacity,
        int maxOpusFrameBytes,
        MicSpamConfig micSpam) {

    public static final int DEFAULT_SILENCE_THRESHOLD_MS = 500;
    public static final int DEFAULT_MIN_AUDIO_DURATION_MS = 200;
    public static final int DEFAULT_MAX_UTTERANCE_SECONDS = 15;
    public static final boolean DEFAULT_LIVE_DETECTION = true;
    public static final int DEFAULT_LIVE_CHUNK_MS = 200;
    public static final int DEFAULT_PRE_ROLL_MS = 200;
    public static final double DEFAULT_VAD_RMS_THRESHOLD = 220.0;
    public static final int DEFAULT_WORKER_THREADS = 2;
    public static final int DEFAULT_WORKER_QUEUE_CAPACITY = 512;
    public static final int DEFAULT_FRAME_POOL_CAPACITY = 2048;
    public static final int DEFAULT_MAX_OPUS_FRAME_BYTES = 1276;

    public static final int MIN_SILENCE_THRESHOLD_MS = 20;
    public static final int MAX_SILENCE_THRESHOLD_MS = 10_000;
    public static final int MAX_MIN_AUDIO_DURATION_MS = 10_000;
    public static final int MIN_UTTERANCE_SECONDS = 5;
    public static final int MAX_UTTERANCE_SECONDS = 60;
    public static final int MIN_LIVE_CHUNK_MS = 20;
    public static final int MAX_LIVE_CHUNK_MS = 5_000;
    public static final int MAX_PRE_ROLL_MS = 2_000;
    public static final double MIN_VAD_RMS_THRESHOLD = 1.0;
    public static final double MAX_VAD_RMS_THRESHOLD = 32_767.0;
    public static final int MIN_WORKER_THREADS = 1;
    public static final int MAX_WORKER_THREADS = 16;
    public static final int MIN_QUEUE_CAPACITY = 16;
    public static final int MAX_QUEUE_CAPACITY = 1 << 20;
    public static final int MIN_POOL_CAPACITY = 16;
    public static final int MAX_POOL_CAPACITY = 1 << 20;
    public static final int MIN_OPUS_FRAME_BYTES = 64;
    public static final int MAX_OPUS_FRAME_BYTES = 8_192;

    public AudioConfig {
        silenceThresholdMs = clamp(silenceThresholdMs, MIN_SILENCE_THRESHOLD_MS, MAX_SILENCE_THRESHOLD_MS);
        minAudioDurationMs = clamp(minAudioDurationMs, 0, MAX_MIN_AUDIO_DURATION_MS);
        maxUtteranceSeconds = clamp(maxUtteranceSeconds, MIN_UTTERANCE_SECONDS, MAX_UTTERANCE_SECONDS);
        liveChunkMs = clamp(liveChunkMs, MIN_LIVE_CHUNK_MS, MAX_LIVE_CHUNK_MS);
        preRollMs = clamp(preRollMs, 0, MAX_PRE_ROLL_MS);
        vadRmsThreshold = Double.isFinite(vadRmsThreshold)
                ? Math.max(MIN_VAD_RMS_THRESHOLD, Math.min(MAX_VAD_RMS_THRESHOLD, vadRmsThreshold))
                : DEFAULT_VAD_RMS_THRESHOLD;
        workerThreads = clamp(workerThreads, MIN_WORKER_THREADS, MAX_WORKER_THREADS);
        workerQueueCapacity = clamp(workerQueueCapacity, MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY);
        framePoolCapacity = clamp(framePoolCapacity, MIN_POOL_CAPACITY, MAX_POOL_CAPACITY);
        maxOpusFrameBytes = clamp(maxOpusFrameBytes, MIN_OPUS_FRAME_BYTES, MAX_OPUS_FRAME_BYTES);
        micSpam = micSpam == null ? MicSpamConfig.defaults() : micSpam;
    }

    public static AudioConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .silenceThresholdMs(silenceThresholdMs)
                .minAudioDurationMs(minAudioDurationMs)
                .maxUtteranceSeconds(maxUtteranceSeconds)
                .liveDetection(liveDetection)
                .liveChunkMs(liveChunkMs)
                .preRollMs(preRollMs)
                .vadRmsThreshold(vadRmsThreshold)
                .workerThreads(workerThreads)
                .workerQueueCapacity(workerQueueCapacity)
                .framePoolCapacity(framePoolCapacity)
                .maxOpusFrameBytes(maxOpusFrameBytes)
                .micSpam(micSpam);
    }

    public int silenceThresholdSamples16k() {
        return silenceThresholdMs * AudioFormat.SAMPLES_PER_MS_16K;
    }

    public int minAudioSamples16k() {
        return minAudioDurationMs * AudioFormat.SAMPLES_PER_MS_16K;
    }

    public int maxUtteranceSamples16k() {
        return maxUtteranceSeconds * AudioFormat.SAMPLE_RATE_16K;
    }

    public int liveChunkSamples16k() {
        return liveChunkMs * AudioFormat.SAMPLES_PER_MS_16K;
    }

    public int preRollSamples16k() {
        return preRollMs * AudioFormat.SAMPLES_PER_MS_16K;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Builder {
        private int silenceThresholdMs = DEFAULT_SILENCE_THRESHOLD_MS;
        private int minAudioDurationMs = DEFAULT_MIN_AUDIO_DURATION_MS;
        private int maxUtteranceSeconds = DEFAULT_MAX_UTTERANCE_SECONDS;
        private boolean liveDetection = DEFAULT_LIVE_DETECTION;
        private int liveChunkMs = DEFAULT_LIVE_CHUNK_MS;
        private int preRollMs = DEFAULT_PRE_ROLL_MS;
        private double vadRmsThreshold = DEFAULT_VAD_RMS_THRESHOLD;
        private int workerThreads = DEFAULT_WORKER_THREADS;
        private int workerQueueCapacity = DEFAULT_WORKER_QUEUE_CAPACITY;
        private int framePoolCapacity = DEFAULT_FRAME_POOL_CAPACITY;
        private int maxOpusFrameBytes = DEFAULT_MAX_OPUS_FRAME_BYTES;
        private MicSpamConfig micSpam = MicSpamConfig.defaults();

        private Builder() {
        }

        public Builder silenceThresholdMs(int value) {
            silenceThresholdMs = value;
            return this;
        }

        public Builder minAudioDurationMs(int value) {
            minAudioDurationMs = value;
            return this;
        }

        public Builder maxUtteranceSeconds(int value) {
            maxUtteranceSeconds = value;
            return this;
        }

        public Builder liveDetection(boolean value) {
            liveDetection = value;
            return this;
        }

        public Builder liveChunkMs(int value) {
            liveChunkMs = value;
            return this;
        }

        public Builder preRollMs(int value) {
            preRollMs = value;
            return this;
        }

        public Builder vadRmsThreshold(double value) {
            vadRmsThreshold = value;
            return this;
        }

        public Builder workerThreads(int value) {
            workerThreads = value;
            return this;
        }

        public Builder workerQueueCapacity(int value) {
            workerQueueCapacity = value;
            return this;
        }

        public Builder framePoolCapacity(int value) {
            framePoolCapacity = value;
            return this;
        }

        public Builder maxOpusFrameBytes(int value) {
            maxOpusFrameBytes = value;
            return this;
        }

        public Builder micSpam(MicSpamConfig value) {
            micSpam = value;
            return this;
        }

        public AudioConfig build() {
            return new AudioConfig(
                    silenceThresholdMs,
                    minAudioDurationMs,
                    maxUtteranceSeconds,
                    liveDetection,
                    liveChunkMs,
                    preRollMs,
                    vadRmsThreshold,
                    workerThreads,
                    workerQueueCapacity,
                    framePoolCapacity,
                    maxOpusFrameBytes,
                    micSpam);
        }
    }
}
