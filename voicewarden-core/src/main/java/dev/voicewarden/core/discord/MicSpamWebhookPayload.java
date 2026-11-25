package dev.voicewarden.core.discord;

import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.core.audio.AcousticAbuseEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MicSpamWebhookPayload(
        UUID playerId,
        String playerName,
        AcousticAbuseEvent.Kind kind,
        double peakRms,
        double clippingRatio,
        int durationMs,
        VoiceAction actionTaken,
        short[] pcm16kAudio,
        Instant timestamp) {

    public MicSpamWebhookPayload {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actionTaken, "actionTaken");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public static MicSpamWebhookPayload of(AcousticAbuseEvent event, String playerName, VoiceAction action, Instant timestamp) {
        return new MicSpamWebhookPayload(event.playerId(), playerName, event.kind(), event.peakRms(), event.clippingRatio(),
                event.durationMs(), action, event.pcm16k(), timestamp);
    }
}
