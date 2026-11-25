package dev.voicewarden.core.discord;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.core.moderation.ConversationLine;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record FlagWebhookPayload(
        UUID playerId,
        String playerName,
        FlagSeverity severity,
        ConfirmationState confirmationState,
        List<String> matchedWords,
        String transcript,
        VoiceAction actionTaken,
        short[] pcm16kAudio,
        Instant timestamp,
        List<ConversationLine> contextLines) {

    public FlagWebhookPayload(
            UUID playerId,
            String playerName,
            FlagSeverity severity,
            ConfirmationState confirmationState,
            List<String> matchedWords,
            String transcript,
            VoiceAction actionTaken,
            short[] pcm16kAudio,
            Instant timestamp) {
        this(playerId, playerName, severity, confirmationState, matchedWords, transcript, actionTaken, pcm16kAudio, timestamp, List.of());
    }

    public FlagWebhookPayload {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confirmationState, "confirmationState");
        matchedWords = matchedWords == null ? List.of() : List.copyOf(matchedWords);
        transcript = transcript == null ? "" : transcript;
        Objects.requireNonNull(timestamp, "timestamp");
        contextLines = contextLines == null ? List.of() : List.copyOf(contextLines);
    }

    public boolean unconfirmed() {
        return confirmationState == ConfirmationState.UNCONFIRMED;
    }
}
