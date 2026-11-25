package dev.voicewarden.plugin.report;

import dev.voicewarden.plugin.mute.KnownPlayer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ReportMenuHolder implements InventoryHolder {

    public enum Stage {
        PICK_TARGET,
        PICK_WINDOW
    }

    private final Stage stage;
    private final @Nullable KnownPlayer target;
    private final Map<Integer, KnownPlayer> targets;
    private final Map<Integer, Integer> windows;
    private Inventory inventory;

    private ReportMenuHolder(Stage stage, @Nullable KnownPlayer target, Map<Integer, KnownPlayer> targets, Map<Integer, Integer> windows) {
        this.stage = stage;
        this.target = target;
        this.targets = Map.copyOf(targets);
        this.windows = Map.copyOf(windows);
    }

    public static ReportMenuHolder targets(Map<Integer, KnownPlayer> targetsBySlot) {
        return new ReportMenuHolder(Stage.PICK_TARGET, null, targetsBySlot, Map.of());
    }

    public static ReportMenuHolder windows(KnownPlayer target, Map<Integer, Integer> minutesBySlot) {
        return new ReportMenuHolder(Stage.PICK_WINDOW, Objects.requireNonNull(target, "target"), Map.of(), minutesBySlot);
    }

    void attach(Inventory created) {
        this.inventory = created;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public Stage stage() {
        return stage;
    }

    public Optional<KnownPlayer> target() {
        return Optional.ofNullable(target);
    }

    public Optional<KnownPlayer> targetAt(int slot) {
        return Optional.ofNullable(targets.get(slot));
    }

    public Optional<Integer> minutesAt(int slot) {
        return Optional.ofNullable(windows.get(slot));
    }

    public Map<Integer, KnownPlayer> targetSlots() {
        return targets;
    }

    public Map<Integer, Integer> windowSlots() {
        return windows;
    }
}
