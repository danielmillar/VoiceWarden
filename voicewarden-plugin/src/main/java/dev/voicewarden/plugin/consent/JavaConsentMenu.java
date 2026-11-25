package dev.voicewarden.plugin.consent;

import dev.voicewarden.core.config.MessageKey;
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

public final class JavaConsentMenu implements ConsentPrompt {

    private final MessageRenderer messages;

    public JavaConsentMenu(MessageRenderer messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public void open(Player player, ConsentManager manager) {
        ConsentMenuHolder holder = new ConsentMenuHolder(manager.config().rulesVersion());
        Inventory inventory = Bukkit.createInventory(holder, ConsentMenuHolder.SIZE, render(player, MessageKey.CONSENT_GUI_TITLE));
        holder.attach(inventory);
        inventory.setItem(ConsentMenuHolder.ACCEPT_SLOT,
                item(player, Material.LIME_WOOL, MessageKey.CONSENT_GUI_ACCEPT_NAME, MessageKey.CONSENT_GUI_ACCEPT_LORE));
        inventory.setItem(ConsentMenuHolder.RULES_SLOT,
                item(player, Material.BOOK, MessageKey.CONSENT_GUI_RULES_NAME, MessageKey.CONSENT_GUI_RULES_LORE));
        inventory.setItem(ConsentMenuHolder.DENY_SLOT,
                item(player, Material.LIGHT_GRAY_WOOL, MessageKey.CONSENT_GUI_DENY_NAME, MessageKey.CONSENT_GUI_DENY_LORE));
        player.openInventory(inventory);
    }

    private ItemStack item(Player player, Material material, MessageKey name, MessageKey lore) {
        ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> {
            meta.displayName(render(player, name));
            meta.lore(List.of(render(player, lore)));
        });
        return stack;
    }

    private Component render(Player player, MessageKey key) {
        return messages.render(key, player, Map.of()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
