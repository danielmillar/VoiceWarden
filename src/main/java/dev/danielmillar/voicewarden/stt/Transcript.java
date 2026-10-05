package dev.danielmillar.voicewarden.stt;

import dev.danielmillar.voicewarden.audio.AudioSegment;

/**
 * Recogniser output for one utterance.
 *
 * @param latencyMillis time from the end of speech until the transcript was available
 */
public record Transcript(AudioSegment segment, String text, long latencyMillis) {
}
