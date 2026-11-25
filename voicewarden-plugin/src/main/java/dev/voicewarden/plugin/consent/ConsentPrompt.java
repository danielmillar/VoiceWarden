package dev.voicewarden.plugin.consent;

import org.bukkit.entity.Player;

public interface ConsentPrompt {

    void open(Player player, ConsentManager manager);
}
