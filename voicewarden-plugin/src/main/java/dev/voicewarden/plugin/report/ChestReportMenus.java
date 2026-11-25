package dev.voicewarden.plugin.report;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.plugin.mute.KnownPlayer;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

public final class ChestReportMenus implements ReportMenus {

    public static final int MAX_SIZE = 54;

    private final MessageRenderer messages;

    public ChestReportMenus(MessageRenderer messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    static int rowsFor(int items) {
        int rows = Math.max(1, (Math.min(items, MAX_SIZE) + 8) / 9);
        return rows * 9;
    }

    @Override
    public void openTargets(Player reporter, ReportMenuHolder holder, List<KnownPlayer> targets) {
        Inventory inventory = Bukkit.createInventory(holder, rowsFor(targets.size()),
                noItalic(messages.render(MessageKey.REPORT_GUI_TITLE, reporter, Map.of("player", "a player"))));
        holder.attach(inventory);
        holder.targetSlots().forEach((slot, target) -> {
            ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, meta -> {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(target.id()));
                meta.displayName(noItalic(messages.renderTemplate("<accent><player>", reporter, Map.of("player", target.name()))));
            });
            inventory.setItem(slot, head);
        });
        reporter.openInventory(inventory);
    }

    @Override
    public void openWindows(Player reporter, ReportMenuHolder holder, List<Integer> minutes) {
        String targetName = holder.target().map(KnownPlayer::name).orElse("?");
        Inventory inventory = Bukkit.createInventory(holder, rowsFor(minutes.size()),
                noItalic(messages.render(MessageKey.REPORT_GUI_TITLE, reporter, Map.of("player", targetName))));
        holder.attach(inventory);
        holder.windowSlots().forEach((slot, window) -> {
            ItemStack clock = ItemStack.of(Material.CLOCK, Math.max(1, Math.min(64, window)));
            clock.editMeta(meta -> meta.displayName(noItalic(messages.render(
                    MessageKey.REPORT_GUI_PRESET_NAME, reporter, Map.of("minutes", Integer.toString(window), "player", targetName)))));
            inventory.setItem(slot, clock);
        });
        reporter.openInventory(inventory);
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
