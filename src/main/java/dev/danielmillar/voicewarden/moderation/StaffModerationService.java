package dev.danielmillar.voicewarden.moderation;

import dev.danielmillar.voicewarden.mute.MuteService;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public final class StaffModerationService implements AutoCloseable {
    private boolean closed;
    private final Object actions = new Object();
    public enum Result { MUTED, BYPASSED, UNMUTED, NOT_MUTED }

    private final MuteService mutes;
    private final Function<UUID, CompletableFuture<Boolean>> bypass;
    private final Consumer<StaffAction> notifications;

    public StaffModerationService(MuteService mutes, Function<UUID, CompletableFuture<Boolean>> bypass,
                                  Consumer<StaffAction> notifications) {
        this.mutes = mutes;
        this.bypass = bypass;
        this.notifications = notifications;
    }

    public CompletableFuture<Result> mute(UUID id, String name, @Nullable Duration duration, String reason, String actor) {
        return bypass.apply(id).thenCompose(exempt -> {
            synchronized (actions) {
                if (closed) {
                    return CompletableFuture.failedFuture(new IllegalStateException("VoiceWarden is stopping"));
                }
                if (exempt) {
                    return CompletableFuture.completedFuture(Result.BYPASSED);
                }
                return mutes.mute(id, name, duration, reason, actor, false).thenApply(ignored -> {
                    notifications.accept(new StaffAction(StaffAction.Type.MUTE, id, name, actor, duration, reason, Instant.now()));
                    return Result.MUTED;
                });
            }
        });
    }

    public CompletableFuture<Result> unmute(UUID id, String name, String actor) {
        synchronized (actions) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("VoiceWarden is stopping"));
            }
            return mutes.unmute(id).thenApply(removed -> {
                if (!removed) {
                    return Result.NOT_MUTED;
                }
                notifications.accept(new StaffAction(StaffAction.Type.UNMUTE, id, name, actor, null, null, Instant.now()));
                return Result.UNMUTED;
            });
        }
    }

    @Override
    public void close() {
        synchronized (actions) {
            closed = true;
        }
    }
}
