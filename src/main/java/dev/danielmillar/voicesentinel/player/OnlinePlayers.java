package dev.danielmillar.voicesentinel.player;

import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe view of online players for worker threads. Bukkit's own player list is not safe to iterate off the
 * server thread, so join/quit handlers (O(1) on the main thread) keep this map in sync instead.
 */
public final class OnlinePlayers {

    private final ConcurrentHashMap<UUID, Player> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Player> byName = new ConcurrentHashMap<>();

    public void add(Player player) {
        byId.put(player.getUniqueId(), player);
        byName.put(player.getName().toLowerCase(Locale.ROOT), player);
    }

    public void remove(Player player) {
        byId.remove(player.getUniqueId(), player);
        byName.remove(player.getName().toLowerCase(Locale.ROOT), player);
    }

    public @Nullable Player get(UUID id) {
        return byId.get(id);
    }

    public @Nullable Player byName(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Player> all() {
        return Collections.unmodifiableCollection(byId.values());
    }
}
