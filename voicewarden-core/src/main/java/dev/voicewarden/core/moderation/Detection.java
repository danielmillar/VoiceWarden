package dev.voicewarden.core.moderation;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Detection(
        UUID playerId,
        String playerName,
        String utteranceId,
        DetectionPhase phase,
        String transcript,
        List<MatchedWord> matches,
        ConfirmationState confirmation) {

    public Detection {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(utteranceId, "utteranceId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(transcript, "transcript");
        matches = List.copyOf(matches);
        Objects.requireNonNull(confirmation, "confirmation");
    }

    public Optional<FlagSeverity> highestSeverity() {
        return matches.stream().map(MatchedWord::severity).reduce(FlagSeverity::highest);
    }

    public List<String> matchedWords() {
        return matches.stream().map(MatchedWord::word).distinct().toList();
    }
}
