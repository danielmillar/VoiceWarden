package dev.danielmillar.voicewarden.voice;

import dev.danielmillar.voicewarden.config.Durations;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.config.PluginConfig.MuteDisplay;
import dev.danielmillar.voicewarden.mute.MuteService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What a muted player sees: a status line (action bar or boss bar) for the whole mute, and a throttled chat reminder
 * when they talk and nobody hears them. Players without the speak permission get a throttled action bar instead.
 *
 * <p>The status is one repeating task per muted online player, on that player's scheduler. It ends itself on the first
 * tick after the mute ends or the display setting changes, and on quit, so nobody muted means no tasks.
 */
public final class VoiceSpeechNotice implements AutoCloseable {
    private static final long NO_PERMISSION_INTERVAL_MILLIS = 1_000;
    private static final long TICKS_PER_UPDATE = 20;

    private final ConcurrentHashMap<UUID, Long> lastNoPermission = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastReminder = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Status> statuses = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final Supplier<Messages> messages;
    private final MuteService mutes;
    private final LongSupplier clock;
    private final Logger logger;
    private volatile boolean active = true;

    /** One player's status line. {@code bar} and {@code shown} are only touched on that player's scheduler. */
    private static final class Status {
        final Player player;
        volatile @Nullable ScheduledTask task;
        @Nullable BossBar bar;
        @Nullable MuteDisplay shown;

        Status(Player player) {
            this.player = player;
        }
    }

    public VoiceSpeechNotice(Plugin plugin, Supplier<PluginConfig> config, Supplier<Messages> messages,
                             MuteService mutes, LongSupplier clock, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.mutes = mutes;
        this.clock = clock;
        this.logger = logger;
    }

    /**
     * Called from the voice-chat thread for every blocked packet, so the common case is two map lookups.
     *
     * @return true when VoiceWarden handled this denial, including a throttled repeat
     */
    public boolean onDeniedSpeech(Player player) {
        if (!active) {
            return false;
        }
        PluginConfig.Mute settings = config.get().mute();
        if (!settings.notifyPlayer()) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (mutes.isMuted(id)) {
            if (settings.display() == MuteDisplay.OFF) {
                return false;
            }
            show(player);
            long interval = settings.reminderInterval().toMillis();
            if (interval > 0 && due(lastReminder, id, interval)) {
                schedule(player, lastReminder, () -> remind(player));
            }
            return true;
        }
        if (messages.get().isEmpty("player.no-speak-permission-actionbar")) {
            return false;
        }
        if (!due(lastNoPermission, id, NO_PERMISSION_INTERVAL_MILLIS)) {
            return true;
        }
        return schedule(player, lastNoPermission, () -> {
            // The permission may have come back while the action bar waited for the player's tick.
            if (mutes.isMuted(id) || player.hasPermission("voicechat.speak")) {
                lastNoPermission.remove(id);
                return;
            }
            player.sendActionBar(messages.get().render("player.no-speak-permission-actionbar"));
        });
    }

    /** Starts the status line for a muted online player if the display needs one. Safe from any thread. */
    public void show(Player player) {
        UUID id = player.getUniqueId();
        if (!active || statuses.containsKey(id) || !showsStatus(config.get().mute()) || !mutes.isMuted(id)) {
            return;
        }
        Status status = new Status(player);
        if (statuses.putIfAbsent(id, status) != null) {
            return;
        }
        try {
            status.task = player.getScheduler().runAtFixedRate(plugin, task -> tick(status, task),
                    () -> statuses.remove(id, status), 1, TICKS_PER_UPDATE);
            if (status.task == null) {
                statuses.remove(id, status);
            }
        } catch (RuntimeException e) {
            statuses.remove(id, status);
            logger.log(Level.WARNING, "Could not start the voice-mute status for " + player.getName(), e);
        }
    }

    private void tick(Status status, ScheduledTask task) {
        Player player = status.player;
        PluginConfig.Mute settings = config.get().mute();
        var mute = mutes.get(player.getUniqueId());
        if (!active || mute.isEmpty() || !showsStatus(settings) || statuses.get(player.getUniqueId()) != status) {
            statuses.remove(player.getUniqueId(), status);
            task.cancel();
            clear(status);
            return;
        }
        MuteDisplay display = settings.display();
        if (status.shown != display) {
            clear(status);
        }
        boolean permanent = mute.get().expiresAt() == MuteService.PERMANENT;
        String key = (permanent ? "player.muted-permanent-" : "player.muted-")
                + (display == MuteDisplay.BOSS_BAR ? "bossbar" : "actionbar");
        Messages current = messages.get();
        if (current.isEmpty(key)) {
            clear(status);
            return;
        }
        long now = System.currentTimeMillis();
        Component text = permanent ? current.render(key)
                : current.render(key, Messages.text("remaining", Durations.formatShort(mute.get().remaining(now))));
        status.shown = display;
        if (display == MuteDisplay.ACTION_BAR) {
            player.sendActionBar(text);
            return;
        }
        float progress = permanent ? 1f : progress(mute.get(), now);
        if (status.bar == null) {
            status.bar = BossBar.bossBar(text, progress, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            player.showBossBar(status.bar);
        } else {
            status.bar.name(text);
            status.bar.progress(progress);
        }
    }

    private void remind(Player player) {
        var mute = mutes.get(player.getUniqueId());
        if (mute.isEmpty()) {
            return;
        }
        Messages current = messages.get();
        if (mute.get().expiresAt() == MuteService.PERMANENT) {
            if (!current.isEmpty("player.muted-permanent-reminder")) {
                player.sendMessage(current.render("player.muted-permanent-reminder"));
            }
        } else if (!current.isEmpty("player.muted-reminder")) {
            player.sendMessage(current.render("player.muted-reminder", Messages.text("remaining",
                    Durations.formatShort(mute.get().remaining(System.currentTimeMillis())))));
        }
    }

    public void onDisconnect(UUID id) {
        lastNoPermission.remove(id);
        lastReminder.remove(id);
        Status status = statuses.remove(id);
        if (status != null && status.task != null) {
            status.task.cancel();
        }
    }

    /** Main thread, on disable. */
    @Override
    public void close() {
        active = false;
        lastNoPermission.clear();
        lastReminder.clear();
        for (Status status : statuses.values()) {
            statuses.remove(status.player.getUniqueId(), status);
            if (status.task != null) {
                status.task.cancel();
            }
            clear(status);
        }
    }

    private static boolean showsStatus(PluginConfig.Mute settings) {
        return settings.notifyPlayer() && settings.display().ticks();
    }

    private static float progress(MuteService.Mute mute, long now) {
        long total = mute.expiresAt() - mute.createdAt();
        return total <= 0 ? 0f : Math.clamp((float) (mute.expiresAt() - now) / total, 0f, 1f);
    }

    private static void clear(Status status) {
        if (status.bar != null) {
            status.player.hideBossBar(status.bar);
            status.bar = null;
        }
        if (status.shown == MuteDisplay.ACTION_BAR) {
            status.player.sendActionBar(Component.empty());
        }
        status.shown = null;
    }

    /** Claims the next send for {@code id} when at least {@code interval} has passed since the last one. */
    private boolean due(ConcurrentHashMap<UUID, Long> last, UUID id, long interval) {
        long now = clock.getAsLong();
        var claimed = new AtomicBoolean();
        last.compute(id, (ignored, previous) -> {
            if (previous == null || now - previous >= interval) {
                claimed.set(true);
                return now;
            }
            return previous;
        });
        return claimed.get();
    }

    private boolean schedule(Player player, ConcurrentHashMap<UUID, Long> last, Runnable send) {
        try {
            player.getScheduler().run(plugin, task -> {
                if (active && config.get().mute().notifyPlayer()) {
                    send.run();
                }
            }, null);
            return true;
        } catch (RuntimeException e) {
            last.remove(player.getUniqueId());
            logger.log(Level.WARNING, "Could not schedule a voice-mute notice", e);
            return false;
        }
    }
}
