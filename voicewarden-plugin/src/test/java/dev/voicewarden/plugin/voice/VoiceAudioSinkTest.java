package dev.voicewarden.plugin.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.core.audio.AudioConfig;
import dev.voicewarden.core.audio.AudioEventSink;
import dev.voicewarden.core.audio.FinalUtterance;
import dev.voicewarden.core.audio.LiveAudioChunk;
import dev.voicewarden.core.audio.RollingOpusBuffer;
import dev.voicewarden.core.audio.ShardedAudioPipeline;
import dev.voicewarden.core.config.TranscriptConfig;
import dev.voicewarden.core.moderation.TranscriptBuffer;
import dev.voicewarden.plugin.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoiceAudioSinkTest {

    private static final Duration WINDOW = Duration.ofSeconds(45);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T12:00:00Z"));
    private final RollingOpusBuffer recentAudio = new RollingOpusBuffer(WINDOW);
    private final UUID speaker = UUID.randomUUID();

    @Test
    void monitoredPacketsAreBufferedWithTheClockTimeAndClearedWhenThePlayerLeaves() {
        try (ShardedAudioPipeline pipeline = new ShardedAudioPipeline(AudioConfig.defaults(), new IgnoringSink())) {
            SpeakerContextTracker speakers = new SpeakerContextTracker(
                    TranscriptBuffer.withDefaults(), new TranscriptConfig(43_200, 500, 4, 60, 48), clock);
            VoiceAudioSink sink = VoiceAudioSink.of(pipeline, recentAudio, speakers, clock);

            sink.submitOpus(speaker, new byte[] {0, 1, 2, 3, 4}, 1, 3);

            List<RollingOpusBuffer.OpusFrame> frames = recentAudio.snapshot(speaker, WINDOW, clock.millis());
            assertEquals(1, frames.size());
            assertEquals(clock.millis(), frames.get(0).timestampMs());
            assertArrayEquals(new byte[] {1, 2, 3}, frames.get(0).data());

            sink.speakingStopped(speaker);
            assertEquals(1, recentAudio.snapshot(speaker, WINDOW, clock.millis()).size());

            sink.playerLeft(speaker);
            assertTrue(recentAudio.snapshot(speaker, WINDOW, clock.millis()).isEmpty());
            assertEquals(0, recentAudio.playerCount());
        }
    }

    private static final class IgnoringSink implements AudioEventSink {

        @Override
        public void onLiveChunk(LiveAudioChunk chunk) {
        }

        @Override
        public void onFinalUtterance(FinalUtterance utterance) {
        }
    }
}
