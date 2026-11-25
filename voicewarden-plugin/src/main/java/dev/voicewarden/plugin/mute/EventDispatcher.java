package dev.voicewarden.plugin.mute;

import org.bukkit.event.Event;

@FunctionalInterface
public interface EventDispatcher {

    void callAsync(Event event);
}
