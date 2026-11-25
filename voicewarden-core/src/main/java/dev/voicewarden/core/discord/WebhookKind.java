package dev.voicewarden.core.discord;

import java.util.Locale;

public enum WebhookKind {
    FLAG,
    MUTE,
    REPORT;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
