package dev.danielmillar.nevusvoice.moderation;

import dev.danielmillar.nevusvoice.moderation.rules.RuleMatch;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A transcribed utterance that matched one or more moderation rules. Immutable and safe to share across threads
 * (the {@code audio} array must be treated as read-only by every consumer).
 *
 * @param id            unique incident id
 * @param detectedAt    when the transcript was evaluated
 * @param spokenAt      when the utterance started
 * @param audioDuration length of the utterance audio
 * @param playerId      speaker UUID
 * @param playerName    speaker name
 * @param transcript    full transcript text exactly as produced by the speech-to-text engine
 * @param matches       rule matches, ordered by {@link RuleMatch#start()}; offsets index into {@code transcript}
 * @param flagsAdded    mute-flags added by this incident (sum of weights of MUTE-action matches)
 * @param flagsInWindow mute-flags inside the flag window after adding this incident
 * @param flagThreshold flags required for an auto-mute
 * @param action        the action decided for this incident
 * @param offense       for MUTE: which auto-mute offense this is for the player (1 = first, drives the mute ladder); else 0
 * @param dryRun        true when moderation is in dry-run mode: the action was decided but NOT applied
 * @param context       up to N preceding utterances of this speaker, oldest first (excludes this one)
 * @param location      where the speaker was, if known
 * @param audio         16-bit signed PCM, mono, at {@code sampleRate}; may be empty
 * @param sampleRate    sample rate of {@code audio} in Hz (16000)
 */
public record Incident(
        UUID id,
        Instant detectedAt,
        Instant spokenAt,
        Duration audioDuration,
        UUID playerId,
        String playerName,
        String transcript,
        List<RuleMatch> matches,
        int flagsAdded,
        int flagsInWindow,
        int flagThreshold,
        ModerationAction action,
        int offense,
        boolean dryRun,
        List<TranscriptLine> context,
        @Nullable SpeakerLocation location,
        short[] audio,
        int sampleRate
) {

    public Incident {
        matches = List.copyOf(matches);
        context = List.copyOf(context);
    }

    /** First 8 hex characters of the id, for display. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
