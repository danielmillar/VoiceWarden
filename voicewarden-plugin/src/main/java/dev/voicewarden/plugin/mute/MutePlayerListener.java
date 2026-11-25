package dev.voicewarden.plugin.mute;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class MutePlayerListener implements Listener {

    private final NativeMuteService mutes;

    public MutePlayerListener(NativeMuteService mutes) {
        this.mutes = Objects.requireNonNull(mutes, "mutes");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        mutes.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        mutes.onQuit(event.getPlayer().getUniqueId());
    }
}
