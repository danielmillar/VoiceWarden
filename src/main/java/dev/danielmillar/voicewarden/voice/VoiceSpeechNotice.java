package dev.danielmillar.voicewarden.voice;

import dev.danielmillar.voicewarden.config.Durations;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.config.PluginConfig.MuteDisplay;
import dev.danielmillar.voicewarden.consent.ConsentService;
import dev.danielmillar.voicewarden.mute.MuteService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
 * What a muted player sees: an action bar for the whole mute, and a throttled chat reminder
 * when they talk and nobody hears them. Players without the speak permission, or blocked because they have not
 * accepted voice moderation, get a throttled action bar instead.
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
    private final ConsentService consent;
    private final LongSupplier clock;
    private final Logger logger;
    private volatile boolean active = true;

    /** One player's status line. {@code shown} is only touched on that player's scheduler. */
    private static final class Status {
        final Player player;
        volatile @Nullable ScheduledTask task;
        boolean shown;

        Status(Player player) {
            this.player = player;
        }
    }

    public VoiceSpeechNotice(Plugin plugin, Supplier<PluginConfig> config, Supplier<Messages> messages,
                             MuteService mutes, ConsentService consent, LongSupplier clock, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.mutes = mutes;
        this.consent = consent;
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
        UUID id = player.getUniqueId();
        boolean muted = mutes.isMuted(id);
        boolean needsConsent = !muted && consent.blocksSpeech(id);
        if (!settings.notifyPlayer() && !needsConsent) {
            return false;
        }
        if (muted) {
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
        String key = needsConsent ? "player.consent-needed-actionbar" : "player.no-speak-permission-actionbar";
        if (messages.get().isEmpty(key)) {
            return false;
        }
        if (!due(lastNoPermission, id, NO_PERMISSION_INTERVAL_MILLIS)) {
            return true;
        }
        return schedule(player, lastNoPermission, () -> {
            // The permission or consent may have come back while the action bar waited for the player's tick.
            if (!needsConsent && !config.get().mute().notifyPlayer()) {
                return;
            }
            if (mutes.isMuted(id) || (needsConsent ? !consent.blocksSpeech(id) : player.hasPermission("voicechat.speak"))) {
                lastNoPermission.remove(id);
                return;
            }
            player.sendActionBar(messages.get().render(key));
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
        boolean permanent = mute.get().expiresAt() == MuteService.PERMANENT;
        String key = permanent ? "player.muted-permanent-actionbar" : "player.muted-actionbar";
        Messages current = messages.get();
        if (current.isEmpty(key)) {
            clear(status);
            return;
        }
        status.shown = true;
        player.sendActionBar(permanent ? current.render(key) : current.render(key, Messages.text("remaining",
                Durations.formatShort(mute.get().remaining(System.currentTimeMillis())))));
    }

    private void remind(Player player) {
        var mute = mutes.get(player.getUniqueId());
        if (mute.isEmpty() || !config.get().mute().notifyPlayer()) {
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

    /** Paper drops the player's scheduled tasks when they respawn, so the status starts again. */
    public void onRespawn(Player player) {
        stopStatus(player.getUniqueId());
        show(player);
    }

    public void onDisconnect(UUID id) {
        lastNoPermission.remove(id);
        lastReminder.remove(id);
        stopStatus(id);
    }

    private void stopStatus(UUID id) {
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
        return settings.notifyPlayer() && settings.display() == MuteDisplay.ACTION_BAR;
    }

    private static void clear(Status status) {
        if (status.shown) {
            status.player.sendActionBar(Component.empty());
            status.shown = false;
        }
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
                if (active) {
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
