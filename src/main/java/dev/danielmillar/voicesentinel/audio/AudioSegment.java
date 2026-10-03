package dev.danielmillar.voicesentinel.audio;

import dev.danielmillar.voicesentinel.moderation.SpeakerLocation;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One utterance of one player, ready for transcription. Immutable; {@code pcm} must be treated as read-only.
 *
 * @param pcm           16-bit mono PCM at {@code sampleRate}
 * @param endedAtNanos  {@link System#nanoTime()} when the utterance ended (for latency metrics and staleness)
 */
public record AudioSegment(
        UUID playerId,
        String playerName,
        short[] pcm,
        int sampleRate,
        Instant spokenAt,
        long endedAtNanos,
        @Nullable SpeakerLocation location,
        boolean whispering
) {
    public Duration duration() {
        return Duration.ofMillis(pcm.length * 1000L / sampleRate);
    }
}
