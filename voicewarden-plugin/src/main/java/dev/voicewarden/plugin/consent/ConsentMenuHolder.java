package dev.voicewarden.plugin.consent;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ConsentMenuHolder implements InventoryHolder {

    public enum Action {
        ACCEPT,
        RULES,
        DENY
    }

    public static final int SIZE = 9;
    public static final int ACCEPT_SLOT = 2;
    public static final int RULES_SLOT = 4;
    public static final int DENY_SLOT = 6;

    private final int rulesVersion;
    private Inventory inventory;

    public ConsentMenuHolder() {
        this(0);
    }

    public ConsentMenuHolder(int rulesVersion) {
        this.rulesVersion = rulesVersion;
    }

    public int rulesVersion() {
        return rulesVersion;
    }

    void attach(Inventory created) {
        this.inventory = created;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static @Nullable Action actionAt(int rawSlot) {
        return switch (rawSlot) {
            case ACCEPT_SLOT -> Action.ACCEPT;
            case RULES_SLOT -> Action.RULES;
            case DENY_SLOT -> Action.DENY;
            default -> null;
        };
    }
}
