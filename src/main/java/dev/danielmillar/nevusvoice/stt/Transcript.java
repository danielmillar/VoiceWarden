package dev.danielmillar.nevusvoice.stt;

import dev.danielmillar.nevusvoice.audio.AudioSegment;

/**
 * Recogniser output for one utterance.
 *
 * @param latencyMillis time from the end of speech until the transcript was available
 */
public record Transcript(AudioSegment segment, String text, long latencyMillis) {
}
