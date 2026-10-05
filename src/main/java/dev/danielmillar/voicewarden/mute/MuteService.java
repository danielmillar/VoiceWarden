package dev.danielmillar.voicewarden.mute;

import com.google.gson.reflect.TypeToken;
import dev.danielmillar.voicewarden.config.Durations;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.moderation.StaffAction;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.util.JsonFiles;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Voice mutes. Enforcement is a lock-free map lookup on Simple Voice Chat's packet thread ({@link #isMuted}); muted
 * packets are cancelled before anyone hears them. Optionally mirrored to LuckPerms as temporary {@code voicechat.speak}
 * (and {@code voicechat.listen}) deny nodes for persistence and network-wide effect. Local state is persisted to
 * {@code data/mutes.json} asynchronously with debouncing.
 */
public final class MuteService implements AutoCloseable {

    public static final long PERMANENT = Long.MAX_VALUE;

    /** @param expiresAt epoch millis, or {@link #PERMANENT} */
    public record Mute(UUID playerId, String playerName, long createdAt, long expiresAt, String reason, String actor,
                       boolean automatic, List<LuckPermsHook.VoiceMute> mirrors) {
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
    private final LuckPermsHook luckPerms;
    private final OnlinePlayers players;
    private final Executor io;
    private final Logger logger;
    private final Path file;
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private boolean writable = true;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Set<UUID> expiring = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Object saveLock = new Object();
    private volatile Consumer<StaffAction> expiryListener = action -> { };
    private @Nullable ScheduledExecutorService timer;
    private @Nullable ScheduledFuture<?> sweeper;

    public MuteService(Supplier<PluginConfig> config, Supplier<Messages> messages, LuckPermsHook luckPerms,
                       OnlinePlayers players, Executor io, Logger logger, Path dataFolder) {
        this.config = config;
        this.messages = messages;
        this.luckPerms = luckPerms;
        this.players = players;
        this.io = io;
        this.logger = logger;
        this.file = dataFolder.resolve("data").resolve("mutes.json");
    }

    /** Loads persisted mutes synchronously (startup only) and starts the expiry sweeper. */
    public void start(ScheduledExecutorService timer) {
        this.timer = timer;
        if (Files.exists(file)) {
            try {
                List<Mute> stored = JsonFiles.read(file, new TypeToken<List<Mute>>() { }.getType());
                if (stored == null || stored.stream().anyMatch(m -> m == null || m.playerId() == null
                        || m.playerName() == null || m.reason() == null || m.actor() == null
                        || (m.mirrors() != null && m.mirrors().stream().anyMatch(v -> v == null
                        || v.speakPermission() == null || v.listenPermission() == null || v.serverContext() == null)))) {
                    throw new IOException("Invalid mute state");
                }
                stored.forEach(m -> mutes.put(m.playerId(), withMirrors(m, mirrors(m))));
            } catch (IOException | RuntimeException e) {
                try {
                    Path preserved = JsonFiles.quarantine(file);
                    logger.log(Level.WARNING, "Could not read " + file + "; moved it to " + preserved
                            + " and started with empty state.", e);
                } catch (IOException preservationFailure) {
                    writable = false;
                    e.addSuppressed(preservationFailure);
                    logger.log(Level.SEVERE, "Could not preserve " + file + "; writes are suspended to avoid overwriting it.", e);
                }
            }
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
            var known = new ArrayList<LuckPermsHook.VoiceMute>(previous == null ? List.of() : mirrors(previous));
            LuckPermsHook.VoiceMute next = mirror(settings);
            if (next != null && !known.contains(next)) {
                known.add(next);
            }
            result.set(luckPerms.replaceVoiceMute(playerId, known, next, duration));
            return new Mute(playerId, playerName, now, expires, reason, actor, automatic, List.copyOf(known));
        });
        scheduleSave();
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

    public CompletableFuture<Boolean> unmute(UUID playerId) {
        return unmute(playerId, null);
    }

    private CompletableFuture<Boolean> unmute(UUID playerId, @Nullable Mute expected) {
        var found = new AtomicReference<Mute>();
        var result = new AtomicReference<CompletableFuture<Void>>();
        mutes.compute(playerId, (id, current) -> {
            if (expected != null && current != expected) {
                result.set(CompletableFuture.completedFuture(null));
                return current;
            }
            found.set(current);
            List<LuckPermsHook.VoiceMute> known = current == null ? currentMirrors() : mirrors(current);
            result.set(luckPerms.replaceVoiceMute(playerId, known, null, null));
            return current;
        });
        Mute original = found.get();
        return track(result.get().thenApply(ignored -> {
            boolean removed = original != null && mutes.remove(playerId, original);
            if (removed) {
                scheduleSave();
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
                LuckPermsHook.VoiceMute next = current.expired(System.currentTimeMillis()) ? null : mirror(config.get().mute());
                if (next != null && !known.contains(next)) {
                    known.add(next);
                }
                result.set(luckPerms.replaceVoiceMute(id, known, next, current.remaining(System.currentTimeMillis())));
                return withMirrors(current, List.copyOf(known));
            });
            if (result.get() != null) {
                track(result.get().exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not update the LuckPerms mirror for " + player
                            + ". The local voice mute is still enforced.", error);
                    return null;
                }));
            }
        }
        if (!mutes.isEmpty()) {
            scheduleSave();
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

    private List<LuckPermsHook.VoiceMute> mirrors(Mute mute) {
        if (mute.mirrors() != null) {
            return mute.mirrors();
        }
        PluginConfig.Mute settings = config.get().mute();
        return List.of(new LuckPermsHook.VoiceMute(settings.speakPermission(), settings.listenPermission(), settings.serverContext()));
    }

    private List<LuckPermsHook.VoiceMute> currentMirrors() {
        PluginConfig.Mute settings = config.get().mute();
        return settings.luckPerms() ? List.of(new LuckPermsHook.VoiceMute(settings.speakPermission(),
                settings.listenPermission(), settings.serverContext())) : List.of();
    }

    private static LuckPermsHook.@Nullable VoiceMute mirror(PluginConfig.Mute settings) {
        return settings.luckPerms() ? new LuckPermsHook.VoiceMute(settings.speakPermission(),
                settings.disableListening() ? settings.listenPermission() : "", settings.serverContext()) : null;
    }

    private static Mute withMirrors(Mute mute, List<LuckPermsHook.VoiceMute> mirrors) {
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
            io.execute(() -> unmute(mute.playerId(), mute).whenComplete((removed, error) -> {
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

    /** Coalesces bursts of changes into one write about a second later. */
    private void scheduleSave() {
        dirty.set(true);
        ScheduledExecutorService t = timer;
        if (t == null || !saveScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            t.schedule(() -> io.execute(this::saveNow), 1, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            saveScheduled.set(false);
        }
    }

    /** Serialised: concurrent saves (debounced timer vs shutdown) never interleave. */
    private void saveNow() {
        synchronized (saveLock) {
            saveScheduled.set(false);
            if (!writable || !dirty.getAndSet(false)) {
                return;
            }
            try {
                JsonFiles.writeAtomically(file, new ArrayList<>(mutes.values()));
            } catch (IOException e) {
                dirty.set(true);
                logger.log(Level.WARNING, "Could not save " + file, e);
            }
        }
    }

    /**
     * Final save on shutdown, only if something changed since the last debounced write. Writes a file on the calling
     * thread, so call it off the server thread, after {@link #flushMirrors()} has completed.
     */
    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.cancel(false);
        }
        saveNow();
    }
}
