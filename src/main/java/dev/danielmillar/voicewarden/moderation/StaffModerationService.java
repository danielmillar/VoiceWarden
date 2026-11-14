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
    /** {@code PARDONED} lifted a mute; {@code CLEARED} pardoned a player who was not muted. */
    public enum Result { MUTED, BYPASSED, UNMUTED, NOT_MUTED, PARDONED, CLEARED }

    private final MuteService mutes;
    private final Function<UUID, CompletableFuture<Boolean>> bypass;
    private final Function<UUID, CompletableFuture<Void>> forgive;
    private final Consumer<StaffAction> notifications;

    /** @param forgive clears a player's offence count and flag window ({@code ModerationService#forgive}) */
    public StaffModerationService(MuteService mutes, Function<UUID, CompletableFuture<Boolean>> bypass,
                                  Function<UUID, CompletableFuture<Void>> forgive, Consumer<StaffAction> notifications) {
        this.mutes = mutes;
        this.bypass = bypass;
        this.forgive = forgive;
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
            return mutes.unmute(id, actor).thenApply(removed -> {
                if (!removed) {
                    return Result.NOT_MUTED;
                }
                notifications.accept(new StaffAction(StaffAction.Type.UNMUTE, id, name, actor, null, null, Instant.now()));
                return Result.UNMUTED;
            });
        }
    }

    /**
     * Undoes a wrong call: lifts any mute, clears the offence count and flag window, and records the pardon. Strikes are
     * cleared only once the mute is gone, so a failed mirror cleanup leaves the player exactly as they were.
     */
    public CompletableFuture<Result> pardon(UUID id, String name, String actor, String reason) {
        synchronized (actions) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("VoiceWarden is stopping"));
            }
            return mutes.pardon(id, name, actor, reason).thenCompose(removed -> forgive.apply(id).thenApply(ignored -> {
                notifications.accept(new StaffAction(StaffAction.Type.PARDON, id, name, actor, null, reason, Instant.now()));
                return removed ? Result.PARDONED : Result.CLEARED;
            }));
        }
    }

    @Override
    public void close() {
        synchronized (actions) {
            closed = true;
        }
    }
}
