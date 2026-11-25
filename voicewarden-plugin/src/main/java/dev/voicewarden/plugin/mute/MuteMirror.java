package dev.voicewarden.plugin.mute;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

public interface MuteMirror {

    String LUCKPERMS_PLUGIN = "LuckPerms";

    MuteMirror NONE = new MuteMirror() {
        @Override
        public void apply(UUID playerId, @Nullable Duration duration) {
        }

        @Override
        public void remove(UUID playerId) {
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    };

    void apply(UUID playerId, @Nullable Duration duration);

    void remove(UUID playerId);

    default CompletableFuture<Void> applyAsync(UUID playerId, @Nullable Duration duration) {
        apply(playerId, duration);
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> removeAsync(UUID playerId) {
        remove(playerId);
        return CompletableFuture.completedFuture(null);
    }

    boolean isAvailable();

    static MuteMirror detectLuckPerms() {
        if (Bukkit.getPluginManager().isPluginEnabled(LUCKPERMS_PLUGIN)) {
            return LuckPermsBridge.create();
        }
        return NONE;
    }
}
