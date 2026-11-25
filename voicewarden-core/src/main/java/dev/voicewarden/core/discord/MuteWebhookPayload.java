package dev.voicewarden.core.discord;

import dev.voicewarden.api.VoiceMuteRecord;
import dev.voicewarden.core.moderation.ConversationLine;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MuteWebhookPayload(
        boolean isUnmute,
        VoiceMuteRecord mute,
        String unmutedBy,
        String transcript,
        short[] pcm16kAudio,
        Instant timestamp,
        List<ConversationLine> contextLines) {

    public MuteWebhookPayload {
        Objects.requireNonNull(mute, "mute");
        Objects.requireNonNull(timestamp, "timestamp");
        contextLines = contextLines == null ? List.of() : List.copyOf(contextLines);
    }

    public static MuteWebhookPayload muted(VoiceMuteRecord mute, String transcript, short[] pcm16kAudio, Instant timestamp) {
        return muted(mute, transcript, pcm16kAudio, timestamp, List.of());
    }

    public static MuteWebhookPayload muted(
            VoiceMuteRecord mute, String transcript, short[] pcm16kAudio, Instant timestamp, List<ConversationLine> contextLines) {
        return new MuteWebhookPayload(false, mute, null, transcript, pcm16kAudio, timestamp, contextLines);
    }

    public static MuteWebhookPayload unmuted(VoiceMuteRecord mute, String unmutedBy, Instant timestamp) {
        return new MuteWebhookPayload(true, mute, unmutedBy, null, null, timestamp, List.of());
    }
}
