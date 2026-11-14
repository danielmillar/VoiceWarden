package dev.danielmillar.voicewarden.mute;

import dev.danielmillar.voicewarden.config.Durations;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.moderation.StaffAction;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.storage.Database;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Voice mutes. Enforcement is a lock-free map lookup on Simple Voice Chat's packet thread ({@link #isMuted}); muted
 * packets are cancelled before anyone hears them. Mutes are saved in the database, and can also be mirrored to LuckPerms
 * as temporary {@code voicechat.speak} (and {@code voicechat.listen}) deny nodes so they apply on other servers.
 */
public final class MuteService implements AutoCloseable {

    public static final long PERMANENT = Long.MAX_VALUE;

    /** @param expiresAt epoch millis, or {@link #PERMANENT} */
    public record Mute(UUID playerId, String playerName, long createdAt, long expiresAt, String reason, String actor,
                       boolean automatic, List<VoiceMute> mirrors) {
        public Mute(UUID playerId, String playerName, long createdAt, long expiresAt, String reason, String actor,
                    boolean automatic) {
            this(playerId, playerName, createdAt, expiresAt, reason, actor, automatic, null);
        }

        public boolean expired(long now) {
            return now >= expiresAt;
        }

        public @Nullable Duration remaining(long now) {
            return expiresAt == PERMANENT ? null : Duration.ofMillis(Math.max(0, expiresAt - now));
        }
    }

    private final ConcurrentHashMap<UUID, Mute> mutes = new ConcurrentHashMap<>();
    private final Supplier<PluginConfig> config;
    private final Supplier<Messages> messages;
    private final VoiceMuteMirror mirror;
    private final OnlinePlayers players;
    private final Executor io;
    private final Logger logger;
    private final Database database;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Set<UUID> expiring = ConcurrentHashMap.newKeySet();
    private volatile Consumer<StaffAction> expiryListener = action -> { };
    private @Nullable ScheduledExecutorService timer;
    private @Nullable ScheduledFuture<?> sweeper;

    public MuteService(Supplier<PluginConfig> config, Supplier<Messages> messages, VoiceMuteMirror mirror,
                       OnlinePlayers players, Executor io, Logger logger, Database database) {
        this.config = config;
        this.messages = messages;
        this.mirror = mirror;
        this.players = players;
        this.io = io;
        this.logger = logger;
        this.database = database;
    }

    /** Loads stored mutes synchronously (startup only) and starts the expiry sweeper. */
    public void start(ScheduledExecutorService timer) {
        this.timer = timer;
        try {
            database.read(MuteTable::loadAll).forEach(m -> mutes.put(m.playerId(), m));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not load voice mutes from the database; starting with none", e);
        }
        reconcileMirrors();
        sweeper = timer.scheduleWithFixedDelay(this::sweepExpired, 1, 1, TimeUnit.SECONDS);
    }

    public void onExpiry(Consumer<StaffAction> listener) {
        this.expiryListener = listener;
    }

    /** Hot path (SVC packet thread): one map lookup. */
    public boolean isMuted(UUID player) {
        Mute mute = mutes.get(player);
        if (mute == null) {
            return false;
        }
        if (mute.expired(System.currentTimeMillis())) {
            expireLater(mute);
            return false;
        }
        return true;
    }

    public Optional<Mute> get(UUID player) {
        return isMuted(player) ? Optional.ofNullable(mutes.get(player)) : Optional.empty();
    }

    public List<Mute> active() {
        long now = System.currentTimeMillis();
        List<Mute> list = new ArrayList<>();
        for (Mute m : mutes.values()) {
            if (!m.expired(now)) {
                list.add(m);
            }
        }
        list.sort(Comparator.comparingLong(Mute::expiresAt));
        return list;
    }

    /**
     * Mutes a player (replacing any existing mute). Effective immediately for this server; the LuckPerms mirror is
     * applied asynchronously. {@code duration == null} = permanent.
     */
    public CompletableFuture<Void> mute(UUID playerId, String playerName, @Nullable Duration duration, String reason,
                                       String actor, boolean automatic) {
        long now = System.currentTimeMillis();
        long expires = duration == null ? PERMANENT : now + duration.toMillis();
        PluginConfig.Mute settings = config.get().mute();
        var result = new AtomicReference<CompletableFuture<Void>>();
        mutes.compute(playerId, (id, previous) -> {
            var known = new ArrayList<VoiceMute>(previous == null ? List.of() : mirrors(previous));
            VoiceMute next = mirror(settings);
            if (next != null && !known.contains(next)) {
                known.add(next);
            }
            result.set(mirror.replaceVoiceMute(playerId, known, next, duration));
            Mute mute = new Mute(playerId, playerName, now, expires, reason, actor, automatic, List.copyOf(known));
            database.write("the voice mute of " + playerName, c -> {
                MuteTable.save(c, mute);
                MuteTable.record(c, MuteEvent.muted(mute));
            });
            return mute;
        });
        Player online = players.get(playerId);
        if (online != null && settings.notifyPlayer()) {
            online.sendMessage(messages.get().render(duration == null ? "player.muted-permanent" : "player.muted",
                    Messages.text("duration", Durations.format(duration)), Messages.text("reason", reason)));
        }
        return track(result.get().exceptionally(error -> {
            logger.log(Level.WARNING, "Could not update the LuckPerms mirror for " + playerName
                    + ". The local voice mute is still enforced.", error);
            return null;
        }));
    }

    /** Lifts the player's mute. Completes with {@code false} if they were not muted. */
    public CompletableFuture<Boolean> unmute(UUID playerId, String actor) {
        return unmute(playerId, null, MuteEvent.Type.UNMUTE, actor, "");
    }

    /**
     * Lifts any mute and records a pardon in the player's history, even when they were not muted. Completes with
     * whether a mute was lifted.
     */
    public CompletableFuture<Boolean> pardon(UUID playerId, String playerName, String actor, String reason) {
        return unmute(playerId, null, MuteEvent.Type.PARDON, actor, reason).thenApply(removed -> {
            if (!removed) {
                MuteEvent event = new MuteEvent(playerId, playerName, MuteEvent.Type.PARDON, System.currentTimeMillis(),
                        actor, reason, null, false);
                mutes.compute(playerId, (id, current) -> {
                    database.write("the pardon of " + playerName, c -> MuteTable.record(c, event));
                    return current;
                });
            }
            return removed;
        });
    }

    /** A page (1-based) of the player's mute history, newest first, read on the database thread. */
    public CompletableFuture<MuteEvent.Page> history(UUID playerId, int page, int perPage) {
        return database.query(c -> MuteTable.history(c, playerId, page, perPage));
    }

    private CompletableFuture<Boolean> unmute(UUID playerId, @Nullable Mute expected, MuteEvent.Type type,
                                              String actor, String reason) {
        var found = new AtomicReference<Mute>();
        var result = new AtomicReference<CompletableFuture<Void>>();
        mutes.compute(playerId, (id, current) -> {
            if (expected != null && current != expected) {
                result.set(CompletableFuture.completedFuture(null));
                return current;
            }
            found.set(current);
            List<VoiceMute> known = current == null ? currentMirrors() : mirrors(current);
            result.set(mirror.replaceVoiceMute(playerId, known, null, null));
            return current;
        });
        Mute original = found.get();
        return track(result.get().thenApply(ignored -> {
            boolean removed = original != null && remove(original, type, actor, reason);
            if (removed) {
                if (config.get().mute().notifyPlayer()) {
                    Player online = players.get(playerId);
                    if (online != null) {
                        online.sendMessage(messages.get().render("player.unmuted"));
                    }
                }
            }
            return removed;
        }).whenComplete((removed, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Could not remove the LuckPerms mirror for " + playerId
                        + ". The local mute was kept so it can be cleaned up later.", error);
            }
        }));
    }

    public void reconcileMirrors() {
        for (UUID player : mutes.keySet()) {
            var result = new AtomicReference<CompletableFuture<Void>>();
            mutes.computeIfPresent(player, (id, current) -> {
                var known = new ArrayList<>(mirrors(current));
                VoiceMute next = current.expired(System.currentTimeMillis()) ? null : mirror(config.get().mute());
                if (next != null && !known.contains(next)) {
                    known.add(next);
                }
                result.set(mirror.replaceVoiceMute(id, known, next, current.remaining(System.currentTimeMillis())));
                Mute updated = withMirrors(current, List.copyOf(known));
                if (!updated.equals(current)) {
                    save(updated);
                }
                return updated;
            });
            if (result.get() != null) {
                track(result.get().exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not update the LuckPerms mirror for " + player
                            + ". The local voice mute is still enforced.", error);
                    return null;
                }));
            }
        }
    }

    public CompletableFuture<Void> flushMirrors() {
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pending.add(future);
        future.whenComplete((ignored, error) -> pending.remove(future));
        return future;
    }

    private List<VoiceMute> mirrors(Mute mute) {
        if (mute.mirrors() != null) {
            return mute.mirrors();
        }
        PluginConfig.Mute settings = config.get().mute();
        return List.of(new VoiceMute(settings.speakPermission(), settings.listenPermission(), settings.serverContext()));
    }

    private List<VoiceMute> currentMirrors() {
        PluginConfig.Mute settings = config.get().mute();
        return settings.luckPerms() ? List.of(new VoiceMute(settings.speakPermission(),
                settings.listenPermission(), settings.serverContext())) : List.of();
    }

    private static @Nullable VoiceMute mirror(PluginConfig.Mute settings) {
        return settings.luckPerms() ? new VoiceMute(settings.speakPermission(),
                settings.disableListening() ? settings.listenPermission() : "", settings.serverContext()) : null;
    }

    private static Mute withMirrors(Mute mute, List<VoiceMute> mirrors) {
        return new Mute(mute.playerId(), mute.playerName(), mute.createdAt(), mute.expiresAt(), mute.reason(),
                mute.actor(), mute.automatic(), mirrors);
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Mute mute : mutes.values()) {
            if (mute.expired(now)) {
                expireLater(mute);
            }
        }
    }

    private void expireLater(Mute mute) {
        if (!expiring.add(mute.playerId())) {
            return;
        }
        try {
            io.execute(() -> unmute(mute.playerId(), mute, MuteEvent.Type.EXPIRE, "System", "").whenComplete((removed, error) -> {
                expiring.remove(mute.playerId());
                if (Boolean.TRUE.equals(removed)) {
                    expiryListener.accept(new StaffAction(StaffAction.Type.UNMUTE, mute.playerId(), mute.playerName(),
                            "System", null, "Mute expired", Instant.now()));
                }
            }));
        } catch (RuntimeException e) {
            expiring.remove(mute.playerId());
        }
    }

    /** Call inside the map update that installed {@code mute}, so database writes keep the same order. */
    private void save(Mute mute) {
        database.write("the voice mute of " + mute.playerName(), c -> MuteTable.save(c, mute));
    }

    /**
     * Removes {@code mute} only if it is still the current one, deleting it from the database and recording why in the
     * same step.
     */
    private boolean remove(Mute mute, MuteEvent.Type type, String actor, String reason) {
        var removed = new AtomicBoolean();
        mutes.computeIfPresent(mute.playerId(), (id, current) -> {
            if (current != mute) {
                return current;
            }
            removed.set(true);
            MuteEvent event = new MuteEvent(id, mute.playerName(), type, System.currentTimeMillis(), actor, reason, null,
                    false);
            database.write("the removal of " + mute.playerName() + "'s voice mute", c -> {
                MuteTable.delete(c, id);
                MuteTable.record(c, event);
            });
            return null;
        });
        return removed.get();
    }

    /** Stops the expiry sweeper. Pending database writes are finished by {@link Database#close()}. */
    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.cancel(false);
        }
    }
}
