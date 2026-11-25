package dev.voicewarden.core.discord;

import java.util.List;
import java.util.Objects;

public record WebhookTarget(
        boolean enabled,
        String url,
        String username,
        String avatarUrl,
        int embedColor,
        String title,
        String footer,
        List<String> rolePingIds,
        IncludeTranscriptMode includeTranscript,
        boolean includeServerInfo,
        boolean includeAudio) {

    public static final int DEFAULT_COLOR = 0xE74C3C;

    public WebhookTarget {
        url = url == null ? "" : url.trim();
        username = username == null ? "" : username.trim();
        avatarUrl = avatarUrl == null ? "" : avatarUrl.trim();
        embedColor = embedColor & 0xFFFFFF;
        title = title == null ? "" : title;
        footer = footer == null ? "" : footer;
        rolePingIds = rolePingIds == null ? List.of() : rolePingIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList();
        includeTranscript = includeTranscript == null ? IncludeTranscriptMode.BOTH : includeTranscript;
    }

    public static WebhookTarget disabled() {
        return new WebhookTarget(false, "", "", "", DEFAULT_COLOR, "", "", List.of(), IncludeTranscriptMode.NONE, false, false);
    }

    public boolean isConfigured() {
        return enabled && !url.isEmpty();
    }

    public WebhookTarget withUrl(String newUrl) {
        return new WebhookTarget(enabled, newUrl, username, avatarUrl, embedColor, title, footer, rolePingIds,
                includeTranscript, includeServerInfo, includeAudio);
    }

    @Override
    public String toString() {
        return "WebhookTarget[enabled=" + enabled
                + ", url=" + (url.isEmpty() ? "" : "<redacted>")
                + ", username=" + username
                + ", embedColor=#" + String.format("%06X", embedColor)
                + ", title=" + title
                + ", rolePingIds=" + rolePingIds
                + ", includeTranscript=" + includeTranscript
                + ", includeServerInfo=" + includeServerInfo
                + ", includeAudio=" + includeAudio + "]";
    }
}
