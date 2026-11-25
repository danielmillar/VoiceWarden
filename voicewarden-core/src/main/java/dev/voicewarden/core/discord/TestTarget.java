package dev.voicewarden.core.discord;

import java.util.List;
import java.util.Locale;

public enum TestTarget {
    ALL(List.of(WebhookKind.FLAG, WebhookKind.MUTE, WebhookKind.REPORT)),
    FLAG(List.of(WebhookKind.FLAG)),
    MUTE(List.of(WebhookKind.MUTE));

    private final List<WebhookKind> kinds;

    TestTarget(List<WebhookKind> kinds) {
        this.kinds = kinds;
    }

    public List<WebhookKind> kinds() {
        return kinds;
    }

    public static TestTarget parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (TestTarget target : values()) {
            if (target.name().equals(normalized)) {
                return target;
            }
        }
        throw new IllegalArgumentException("Unknown webhook test target '" + value + "'. Use all, flag or mute.");
    }
}
