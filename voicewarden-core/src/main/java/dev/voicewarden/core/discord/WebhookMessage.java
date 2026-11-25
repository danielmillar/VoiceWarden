package dev.voicewarden.core.discord;

import com.google.gson.JsonObject;
import java.util.Objects;

public record WebhookMessage(
        JsonObject payload,
        JsonObject attachmentPayload,
        byte[] attachment,
        String attachmentName) {

    public WebhookMessage {
        Objects.requireNonNull(payload, "payload");
        boolean hasAttachment = attachment != null;
        if (hasAttachment != (attachmentName != null) || hasAttachment != (attachmentPayload != null)) {
            throw new IllegalArgumentException(
                    "attachmentPayload, attachment and attachmentName must all be set or all be null");
        }
    }

    public WebhookMessage(JsonObject payload) {
        this(payload, null, null, null);
    }

    public boolean hasAttachment() {
        return attachment != null;
    }
}
