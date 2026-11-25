package dev.voicewarden.core.discord;

import java.util.Objects;

public record DiscordWebhookConfig(
        WebhookTarget flagWebhook,
        WebhookTarget muteWebhook,
        WebhookTarget reportWebhook,
        int timeoutSeconds,
        int maxRetries,
        long retryBaseDelayMs,
        String serverName,
        String serverId) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_RETRY_BASE_DELAY_MS = 500L;

    public DiscordWebhookConfig {
        flagWebhook = Objects.requireNonNullElseGet(flagWebhook, WebhookTarget::disabled);
        muteWebhook = Objects.requireNonNullElseGet(muteWebhook, WebhookTarget::disabled);
        reportWebhook = Objects.requireNonNullElseGet(reportWebhook, WebhookTarget::disabled);
        timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        maxRetries = Math.max(0, maxRetries);
        retryBaseDelayMs = retryBaseDelayMs <= 0 ? DEFAULT_RETRY_BASE_DELAY_MS : retryBaseDelayMs;
        serverName = serverName == null || serverName.isBlank() ? "Minecraft server" : serverName.trim();
        serverId = serverId == null || serverId.isBlank() ? "default" : serverId.trim();
    }

    public WebhookTarget target(WebhookKind kind) {
        return switch (kind) {
            case FLAG -> flagWebhook;
            case MUTE -> muteWebhook;
            case REPORT -> reportWebhook;
        };
    }
}
