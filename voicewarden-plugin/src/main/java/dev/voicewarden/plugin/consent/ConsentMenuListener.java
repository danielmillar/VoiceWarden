package dev.voicewarden.plugin.consent;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ConsentMenuListener implements Listener {

    private final ConsentManager consent;

    public ConsentMenuListener(ConsentManager consent) {
        this.consent = Objects.requireNonNull(consent, "consent");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        consent.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        consent.onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ConsentMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= ConsentMenuHolder.SIZE) {
            return;
        }
        ConsentMenuHolder.Action action = ConsentMenuHolder.actionAt(event.getRawSlot());
        if (action == null) {
            return;
        }
        int shownVersion = holder.rulesVersion() > 0 ? holder.rulesVersion() : consent.config().rulesVersion();
        switch (action) {
            case ACCEPT -> consent.accept(player, shownVersion);
            case RULES -> consent.showRules(player);
            case DENY -> consent.deny(player, shownVersion);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ConsentMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof ConsentMenuHolder
                && event.getPlayer() instanceof Player player
                && event.getReason() != InventoryCloseEvent.Reason.PLUGIN
                && event.getReason() != InventoryCloseEvent.Reason.OPEN_NEW
                && event.getReason() != InventoryCloseEvent.Reason.DISCONNECT) {
            consent.onPromptDismissed(player);
        }
    }
}
