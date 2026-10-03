package dev.danielmillar.voicesentinel.stt;

import dev.danielmillar.voicesentinel.audio.AudioSegment;

/**
 * Recogniser output for one utterance.
 *
 * @param latencyMillis time from the end of speech until the transcript was available
 */
public record Transcript(AudioSegment segment, String text, long latencyMillis) {
}
