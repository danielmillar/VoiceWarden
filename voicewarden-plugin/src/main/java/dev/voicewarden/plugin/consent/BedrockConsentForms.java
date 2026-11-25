package dev.voicewarden.plugin.consent;

import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public interface BedrockConsentForms extends ConsentPrompt {

    String FLOODGATE_PLUGIN = "floodgate";

    BedrockConsentForms NONE = new BedrockConsentForms() {
        @Override
        public boolean isBedrock(UUID playerId) {
            return false;
        }

        @Override
        public void open(Player player, ConsentManager manager) {
        }
    };

    boolean isBedrock(UUID playerId);

    static BedrockConsentForms detect(MessageRenderer messages, TaskScheduler scheduler) {
        if (Bukkit.getPluginManager().isPluginEnabled(FLOODGATE_PLUGIN)) {
            return FloodgateConsentBridge.create(messages, scheduler);
        }
        return NONE;
    }
}
