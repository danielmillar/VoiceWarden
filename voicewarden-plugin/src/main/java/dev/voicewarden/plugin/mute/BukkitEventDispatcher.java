package dev.voicewarden.plugin.mute;

import dev.voicewarden.plugin.scheduler.TaskScheduler;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;

public final class BukkitEventDispatcher implements EventDispatcher {

    private final TaskScheduler scheduler;
    private final PluginManager pluginManager;

    public BukkitEventDispatcher(TaskScheduler scheduler, PluginManager pluginManager) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
    }

    @Override
    public void callAsync(Event event) {
        if (!event.isAsynchronous()) {
            throw new IllegalArgumentException(event.getEventName() + " is a synchronous event and cannot be fired from a background thread");
        }
        scheduler.runAsync(() -> pluginManager.callEvent(event));
    }
}
