package dev.danielmillar.nevusvoice.voice;

import dev.danielmillar.nevusvoice.config.Durations;
import dev.danielmillar.nevusvoice.config.Messages;
import dev.danielmillar.nevusvoice.config.PluginConfig;
import dev.danielmillar.nevusvoice.mute.MuteService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Schedules one custom action bar per second of blocked speech on the player's own scheduler. */
public final class VoiceSpeechNotice implements AutoCloseable {
    private static final long INTERVAL_MILLIS = 1_000;
    private final ConcurrentHashMap<UUID, Long> lastNotice = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final Supplier<Messages> messages;
    private final MuteService mutes;
    private final Logger logger;
    private volatile boolean active = true;

    public VoiceSpeechNotice(Plugin plugin, Supplier<PluginConfig> config, Supplier<Messages> messages,
                             MuteService mutes, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.mutes = mutes;
        this.logger = logger;
    }

    /** @return true when the custom notice handles this denial, including a throttled repeat. */
    public boolean onDeniedSpeech(Player player) {
        if (!active || !config.get().mute().notifyPlayer()) {
            return false;
        }
        UUID id = player.getUniqueId();
        String key = mutes.get(id).map(mute -> mute.expiresAt() == MuteService.PERMANENT
                ? "player.muted-permanent-actionbar" : "player.muted-actionbar")
                .orElse("player.no-speak-permission-actionbar");
        if (messages.get().isEmpty(key)) {
            return false;
        }
        long now = System.currentTimeMillis();
        AtomicBoolean send = new AtomicBoolean();
        lastNotice.compute(id, (ignored, last) -> {
            if (last == null || now - last >= INTERVAL_MILLIS) {
                send.set(true);
                return now;
            }
            return last;
        });
        if (!send.get()) {
            return true;
        }
        try {
            player.getScheduler().run(plugin, task -> {
                if (!active || !config.get().mute().notifyPlayer()) {
                    return;
                }
                var mute = mutes.get(id);
                // Permission or mute may have changed while the action bar was waiting for the next tick.
                if (mute.isEmpty() && player.hasPermission("voicechat.speak")) {
                    lastNotice.remove(id);
                    return;
                }
                Messages current = messages.get();
                if (mute.isPresent()) {
                    if (mute.get().expiresAt() == MuteService.PERMANENT) {
                        player.sendActionBar(current.render("player.muted-permanent-actionbar"));
                    } else {
                        player.sendActionBar(current.render("player.muted-actionbar",
                                Messages.text("remaining", Durations.formatShort(mute.get().remaining(System.currentTimeMillis())))));
                    }
                } else {
                    player.sendActionBar(current.render("player.no-speak-permission-actionbar"));
                }
            }, null);
            return true;
        } catch (RuntimeException e) {
            lastNotice.remove(id, now);
            logger.log(Level.WARNING, "Could not schedule the blocked-speech action bar", e);
            return false;
        }
    }

    public void onDisconnect(UUID id) {
        lastNotice.remove(id);
    }

    @Override
    public void close() {
        active = false;
        lastNotice.clear();
    }
}
