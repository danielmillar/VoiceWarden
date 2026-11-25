package dev.voicewarden.plugin.pipeline;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ModerationQuitListener implements Listener {

    private final VoiceModerationPipeline pipeline;

    public ModerationQuitListener(VoiceModerationPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pipeline.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
