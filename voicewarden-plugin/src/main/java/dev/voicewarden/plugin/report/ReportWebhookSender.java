package dev.voicewarden.plugin.report;

import dev.voicewarden.core.discord.ReportWebhookPayload;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ReportWebhookSender {

    ReportWebhookSender NONE = payload -> CompletableFuture.completedFuture(null);

    CompletableFuture<?> send(ReportWebhookPayload payload);
}
