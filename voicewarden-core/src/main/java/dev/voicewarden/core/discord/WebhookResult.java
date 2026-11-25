package dev.voicewarden.core.discord;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public record WebhookResult(
        Status status,
        String target,
        int statusCode,
        int attempts,
        String message,
        List<WebhookResult> parts) {

    public enum Status {
        SENT,
        SKIPPED,
        FAILED
    }

    public WebhookResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(target, "target");
        message = message == null ? "" : message;
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public static WebhookResult sent(WebhookKind kind, int statusCode, int attempts) {
        return new WebhookResult(Status.SENT, kind.label(), statusCode, attempts,
                "HTTP " + statusCode + attemptSuffix(attempts), List.of());
    }

    public static WebhookResult skipped(WebhookKind kind, String reason) {
        return new WebhookResult(Status.SKIPPED, kind.label(), 0, 0, reason, List.of());
    }

    public static WebhookResult failed(WebhookKind kind, int statusCode, int attempts, String reason) {
        return new WebhookResult(Status.FAILED, kind.label(), statusCode, attempts, reason, List.of());
    }

    public static WebhookResult combined(String target, List<WebhookResult> parts) {
        boolean anyFailed = parts.stream().anyMatch(part -> part.status == Status.FAILED);
        boolean anySent = parts.stream().anyMatch(part -> part.status == Status.SENT);
        Status status = anyFailed ? Status.FAILED : anySent ? Status.SENT : Status.SKIPPED;
        int attempts = parts.stream().mapToInt(WebhookResult::attempts).sum();
        String summary = parts.stream().map(WebhookResult::summary).collect(Collectors.joining("; "));
        return new WebhookResult(status, target, 0, attempts, summary, parts);
    }

    public boolean success() {
        return status == Status.SENT;
    }

    public String summary() {
        if (!parts.isEmpty()) {
            return message;
        }
        return target + ": " + status.name().toLowerCase(Locale.ROOT) + " (" + message + ")";
    }

    private static String attemptSuffix(int attempts) {
        return attempts == 1 ? " after 1 attempt" : " after " + attempts + " attempts";
    }
}
