package dev.danielmillar.voicewarden.notify.discord;

/**
 * Discord configuration (the {@code discord} section of config.yml).
 *
 * <p>Routing: every {@code Incident} goes to {@link #flags()}. Auto-mute incidents and {@code StaffAction}s go to
 * {@link #mutes()}. If an auto-mute incident would be sent to the same URL by both, it is sent once.
 * {@code VoiceReport}s go to {@link #reports()}.
 *
 * @param serverName     identifies this backend server in embeds (footer / server info field), "" = omit
 * @param timeoutSeconds HTTP request timeout
 * @param retryAttempts  retries for 429 / 5xx / IO errors (0 = no retry)
 * @param maxQueueSize   pending messages kept before new ones are dropped
 */
public record DiscordSettings(
        String serverName,
        int timeoutSeconds,
        int retryAttempts,
        int maxQueueSize,
        WebhookSettings flags,
        WebhookSettings mutes,
        WebhookSettings reports
) {
    public static DiscordSettings disabled() {
        WebhookSettings off = WebhookSettings.disabled();
        return new DiscordSettings("", 10, 3, 100, off, off, off);
    }
}
