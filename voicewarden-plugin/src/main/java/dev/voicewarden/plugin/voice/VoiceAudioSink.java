package dev.voicewarden.plugin.voice;

import dev.voicewarden.core.audio.RollingOpusBuffer;
import dev.voicewarden.core.audio.ShardedAudioPipeline;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public interface VoiceAudioSink {

    VoiceAudioSink NONE = new VoiceAudioSink() {
        @Override
        public void submitOpus(UUID playerId, byte[] opus, int offset, int length) {
        }

        @Override
        public void speakingStopped(UUID playerId) {
        }

        @Override
        public void playerLeft(UUID playerId) {
        }
    };

    void submitOpus(UUID playerId, byte[] opus, int offset, int length);

    void speakingStopped(UUID playerId);

    void playerLeft(UUID playerId);

    default void voiceGroup(UUID playerId, @Nullable UUID groupId) {
    }

    static VoiceAudioSink of(
            ShardedAudioPipeline pipeline,
            RollingOpusBuffer recentAudio,
            SpeakerContextTracker speakers,
            Clock clock) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(recentAudio, "recentAudio");
        Objects.requireNonNull(speakers, "speakers");
        Objects.requireNonNull(clock, "clock");
        return new VoiceAudioSink() {
            @Override
            public void submitOpus(UUID playerId, byte[] opus, int offset, int length) {
                pipeline.submitOpus(playerId, opus, offset, length);
                recentAudio.append(playerId, opus, offset, length, clock.millis());
            }

            @Override
            public void speakingStopped(UUID playerId) {
                pipeline.markSpeakingStopped(playerId);
            }

            @Override
            public void playerLeft(UUID playerId) {
                pipeline.removePlayer(playerId);
                recentAudio.clear(playerId);
                speakers.forget(playerId);
            }

            @Override
            public void voiceGroup(UUID playerId, @Nullable UUID groupId) {
                speakers.observeGroup(playerId, groupId);
            }
        };
    }
}
