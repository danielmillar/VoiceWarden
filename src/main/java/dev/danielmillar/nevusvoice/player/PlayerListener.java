package dev.danielmillar.nevusvoice.player;

import dev.danielmillar.nevusvoice.config.Messages;
import dev.danielmillar.nevusvoice.config.PluginConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Main-thread join/quit bookkeeping. Each handler is O(1); anything heavier is handed to other threads. */
public final class PlayerListener implements Listener {

    private final OnlinePlayers players;
    private final Supplier<PluginConfig> config;
    private final Supplier<Messages> messages;
    private final Consumer<UUID> onQuit;

    public PlayerListener(OnlinePlayers players, Supplier<PluginConfig> config, Supplier<Messages> messages,
                          Consumer<UUID> onQuit) {
        this.players = players;
        this.config = config;
        this.messages = messages;
        this.onQuit = onQuit;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        players.add(event.getPlayer());
        if (config.get().privacyJoinMessage() && !messages.get().isEmpty("privacy-join")) {
            event.getPlayer().sendMessage(messages.get().render("privacy-join"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer());
        onQuit.accept(event.getPlayer().getUniqueId());
    }
}
