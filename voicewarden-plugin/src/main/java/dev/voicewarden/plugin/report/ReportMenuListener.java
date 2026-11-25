package dev.voicewarden.plugin.report;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class ReportMenuListener implements Listener {

    private final VoiceReportManager reports;

    public ReportMenuListener(VoiceReportManager reports) {
        this.reports = Objects.requireNonNull(reports, "reports");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ReportMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
            reports.onMenuClick(player, holder, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ReportMenuHolder) {
            event.setCancelled(true);
        }
    }
}
