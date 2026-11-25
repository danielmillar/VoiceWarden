package dev.voicewarden.plugin.text;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public interface PlaceholderHook {

    String PLUGIN_NAME = "PlaceholderAPI";

    PlaceholderHook NONE = (viewer, text) -> text;

    String apply(Player viewer, String text);

    static PlaceholderHook detect(boolean enabled) {
        if (enabled && Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            return new PlaceholderApiHook();
        }
        return NONE;
    }
}
