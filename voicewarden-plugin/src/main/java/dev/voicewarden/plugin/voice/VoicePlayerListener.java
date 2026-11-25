package dev.voicewarden.plugin.voice;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class VoicePlayerListener implements Listener {

    private final VoiceBackendManager voice;

    public VoicePlayerListener(VoiceBackendManager voice) {
        this.voice = Objects.requireNonNull(voice, "voice");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        voice.refreshBypass(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        voice.forget(event.getPlayer().getUniqueId());
    }
}
