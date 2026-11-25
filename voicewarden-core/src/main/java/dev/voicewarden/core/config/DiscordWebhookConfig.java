package dev.voicewarden.core.config;

import java.util.List;
import java.util.Objects;

public record DiscordWebhookConfig(
        boolean enabled,
        String webhookUrl,
        String username,
        String avatarUrl,
        int color,
        String title,
        String footer,
        List<String> rolePings,
        TranscriptInclusion includeTranscript,
        boolean includeServerInfo,
        boolean includeAudio,
        int timeoutSeconds,
        int maxRetries) {

    public enum TranscriptInclusion {
        FLAGGED,
        MUTED,
        BOTH,
        NONE
    }

    public DiscordWebhookConfig {
        Objects.requireNonNull(webhookUrl, "webhookUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(avatarUrl, "avatarUrl");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(footer, "footer");
        rolePings = List.copyOf(rolePings);
        Objects.requireNonNull(includeTranscript, "includeTranscript");
    }

    public boolean isUsable() {
        return enabled && !webhookUrl.isBlank();
    }

    @Override
    public String toString() {
        return "DiscordWebhookConfig[enabled=" + enabled + ", webhookUrl=" + Redaction.secret(webhookUrl)
                + ", username=" + username + ", avatarUrl=" + avatarUrl + ", color=#" + String.format("%06X", color)
                + ", title=" + title + ", footer=" + footer + ", rolePings=" + rolePings
                + ", includeTranscript=" + includeTranscript + ", includeServerInfo=" + includeServerInfo
                + ", includeAudio=" + includeAudio + ", timeoutSeconds=" + timeoutSeconds + ", maxRetries=" + maxRetries + "]";
    }
}
