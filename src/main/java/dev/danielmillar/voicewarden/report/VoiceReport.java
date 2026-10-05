package dev.danielmillar.voicewarden.report;

import dev.danielmillar.voicewarden.moderation.TranscriptLine;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A player-submitted report of another player's recent voice chat (/reportvoice).
 *
 * @param reporterId null when filed by the console
 * @param lines      the target's transcript lines inside the window, oldest first
 * @param audio      16-bit PCM mono of the target's buffered speech inside the window (may be empty)
 */
public record VoiceReport(
        UUID id,
        Instant createdAt,
        @Nullable UUID reporterId,
        String reporterName,
        UUID targetId,
        String targetName,
        Duration window,
        List<TranscriptLine> lines,
        short[] audio,
        int sampleRate
) {
    public VoiceReport {
        lines = List.copyOf(lines);
    }

    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
