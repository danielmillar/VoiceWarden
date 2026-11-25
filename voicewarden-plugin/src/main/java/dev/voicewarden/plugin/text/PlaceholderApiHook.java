package dev.voicewarden.plugin.text;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

final class PlaceholderApiHook implements PlaceholderHook {

    @Override
    public String apply(Player viewer, String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        return PlaceholderAPI.setPlaceholders(viewer, text);
    }
}
