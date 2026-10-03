package dev.danielmillar.voicesentinel.moderation;

import java.time.Instant;

/** One previously transcribed utterance, used as context in alerts. */
public record TranscriptLine(Instant spokenAt, String text) {
}
