package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AudioConfigTest {

    @Test
    void defaultsMatchThePlan() {
        AudioConfig config = AudioConfig.defaults();
        assertEquals(500, config.silenceThresholdMs());
        assertEquals(200, config.minAudioDurationMs());
        assertEquals(15, config.maxUtteranceSeconds());
        assertTrue(config.liveDetection());
        assertEquals(200, config.liveChunkMs());
        assertEquals(200, config.preRollMs());
        assertEquals(220.0, config.vadRmsThreshold());
        assertEquals(2, config.workerThreads());
        assertEquals(512, config.workerQueueCapacity());
        assertEquals(2048, config.framePoolCapacity());
        assertEquals(1276, config.maxOpusFrameBytes());
        assertEquals(3200, config.liveChunkSamples16k());
        assertEquals(3200, config.preRollSamples16k());
        assertEquals(240_000, config.maxUtteranceSamples16k());
    }

    @Test
    void clampsOutOfRangeValues() {
        AudioConfig low = AudioConfig.builder().maxUtteranceSeconds(1).workerThreads(0).vadRmsThreshold(Double.NaN).build();
        assertEquals(5, low.maxUtteranceSeconds());
        assertEquals(1, low.workerThreads());
        assertEquals(220.0, low.vadRmsThreshold());
        AudioConfig high = AudioConfig.builder().maxUtteranceSeconds(600).workerThreads(64).build();
        assertEquals(60, high.maxUtteranceSeconds());
        assertEquals(16, high.workerThreads());
    }

    @Test
    void toBuilderRoundTrips() {
        AudioConfig config = AudioConfig.builder().liveDetection(false).liveChunkMs(400).build();
        assertEquals(config, config.toBuilder().build());
    }
}
