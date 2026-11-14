package dev.danielmillar.voicewarden.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Optional PlaceholderAPI expansion. Only this package may name PlaceholderAPI types, and only after the plugin was
 * found, so VoiceWarden starts without it. Callers hold the {@link Runnable} from {@link #register}, never this class.
 */
public final class VoiceWardenExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final VoiceStatus status;

    private VoiceWardenExpansion(Plugin plugin, VoiceStatus status) {
        this.plugin = plugin;
        this.status = status;
    }

    /** Registers the expansion and returns what unregisters it. Call only when PlaceholderAPI is installed. */
    public static Runnable register(Plugin plugin, VoiceStatus status) {
        var expansion = new VoiceWardenExpansion(plugin, status);
        if (!expansion.register()) {
            plugin.getLogger().warning("PlaceholderAPI refused the voicewarden placeholders");
        }
        return expansion::unregister;
    }

    @Override
    public String getIdentifier() {
        return "voicewarden";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public List<String> getPlaceholders() {
        return VoiceStatus.NAMES.stream().map(name -> "%voicewarden_" + name + "%").toList();
    }

    /** VoiceWarden registers and unregisters this itself, so {@code /papi reload} must keep it. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        return status.resolve(player == null ? null : player.getUniqueId(), params);
    }
}
