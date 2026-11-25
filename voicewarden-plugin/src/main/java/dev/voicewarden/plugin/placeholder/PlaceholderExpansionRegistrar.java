package dev.voicewarden.plugin.placeholder;

import dev.voicewarden.api.VoiceWardenAPI;
import dev.voicewarden.plugin.consent.ConsentView;
import org.bukkit.Bukkit;

public final class PlaceholderExpansionRegistrar {

    public static final String PLUGIN_NAME = "PlaceholderAPI";

    private PlaceholderExpansionRegistrar() {
    }

    public static Runnable registerIfPresent(VoiceWardenAPI api, ConsentView consent, String version) {
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            return () -> {
            };
        }
        return ExpansionHandle.register(api, consent, version);
    }

    private static final class ExpansionHandle {

        private ExpansionHandle() {
        }

        static Runnable register(VoiceWardenAPI api, ConsentView consent, String version) {
            VoiceWardenExpansion expansion = new VoiceWardenExpansion(api, consent, version);
            if (!expansion.register()) {
                throw new IllegalStateException("PlaceholderAPI refused the voicewarden expansion. Another expansion may already use that identifier.");
            }
            return expansion::unregister;
        }
    }
}
