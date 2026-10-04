package dev.danielmillar.nevusvoice.mute;

import com.google.gson.reflect.TypeToken;
import dev.danielmillar.nevusvoice.config.Durations;
import dev.danielmillar.nevusvoice.config.Messages;
import dev.danielmillar.nevusvoice.config.PluginConfig;
import dev.danielmillar.nevusvoice.luckperms.LuckPermsHook;
import dev.danielmillar.nevusvoice.moderation.StaffAction;
import dev.danielmillar.nevusvoice.player.OnlinePlayers;
import dev.danielmillar.nevusvoice.util.JsonFiles;
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
                       boolean automatic) {
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

    /** Loads persisted mutes synchronously (startup only; a small file) and starts the expiry sweeper. */
    public void start(ScheduledExecutorService timer) {
        this.timer = timer;
        if (Files.exists(file)) {
            try {
                List<Mute> stored = JsonFiles.read(file, new TypeToken<List<Mute>>() { }.getType());
                long now = System.currentTimeMillis();
                if (stored != null) {
                    stored.stream().filter(m -> m != null && m.playerId() != null && !m.expired(now))
                            .forEach(m -> mutes.put(m.playerId(), m));
                }
            } catch (IOException | RuntimeException e) {
                logger.log(Level.WARNING, "Could not read " + file + "; starting with no local mutes", e);
            }
        }
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
            if (mutes.remove(player, mute)) {
                io.execute(() -> expired(mute));
            }
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
        mutes.put(playerId, new Mute(playerId, playerName, now, expires, reason, actor, automatic));
        scheduleSave();
        PluginConfig.Mute settings = config.get().mute();
        Player online = players.get(playerId);
        if (online != null && settings.notifyPlayer()) {
            online.sendMessage(messages.get().render(duration == null ? "player.muted-permanent" : "player.muted",
                    Messages.text("duration", Durations.format(duration)), Messages.text("reason", reason)));
        }
        if (!settings.luckPerms()) {
            return CompletableFuture.completedFuture(null);
        }
        return luckPerms.applyVoiceMute(playerId, duration, settings.speakPermission(),
                        settings.disableListening() ? settings.listenPermission() : null, settings.serverContext())
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not apply LuckPerms voice mute for " + playerName
                            + " (the local mute is still active)", error);
                    return null;
                });
    }

    /** @return true if the player had a local mute (LuckPerms deny nodes are removed either way) */
    public CompletableFuture<Boolean> unmute(UUID playerId) {
        Mute removed = mutes.remove(playerId);
        scheduleSave();
        PluginConfig.Mute settings = config.get().mute();
        if (removed != null && settings.notifyPlayer()) {
            Player online = players.get(playerId);
            if (online != null) {
                online.sendMessage(messages.get().render("player.unmuted"));
            }
        }
        if (!settings.luckPerms()) {
            return CompletableFuture.completedFuture(removed != null);
        }
        return luckPerms.clearVoiceMute(playerId, settings.speakPermission(), settings.listenPermission(), settings.serverContext())
                .handle((ok, error) -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Could not remove LuckPerms voice mute nodes", error);
                    }
                    return removed != null;
                });
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Mute mute : mutes.values()) {
            if (mute.expired(now) && mutes.remove(mute.playerId(), mute)) {
                io.execute(() -> expired(mute));
            }
        }
    }

    private void expired(Mute mute) {
        scheduleSave();
        if (config.get().mute().notifyPlayer()) {
            Player online = players.get(mute.playerId());
            if (online != null) {
                online.sendMessage(messages.get().render("player.unmuted"));
            }
        }
        expiryListener.accept(new StaffAction(StaffAction.Type.UNMUTE, mute.playerId(), mute.playerName(), "System",
                null, "Mute expired", Instant.now()));
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
            if (!dirty.getAndSet(false)) {
                return;
            }
            try {
                JsonFiles.writeAtomically(file, active());
            } catch (IOException e) {
                dirty.set(true);
                logger.log(Level.WARNING, "Could not save " + file, e);
            }
        }
    }

    /** Final save on shutdown, only if something changed since the last debounced write (a few KB at most). */
    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.cancel(false);
        }
        saveNow();
    }
}
