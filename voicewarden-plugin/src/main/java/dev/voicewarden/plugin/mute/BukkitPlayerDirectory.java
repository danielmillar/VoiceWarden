package dev.voicewarden.plugin.mute;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

public final class BukkitPlayerDirectory implements PlayerDirectory {

    private final Server server;

    public BukkitPlayerDirectory(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Optional<Player> online(UUID playerId) {
        return Optional.ofNullable(server.getPlayer(playerId));
    }

    @Override
    public Optional<Player> onlineExact(String name) {
        return Optional.ofNullable(server.getPlayerExact(name));
    }

    @Override
    public Collection<? extends Player> onlinePlayers() {
        return server.getOnlinePlayers();
    }

    @Override
    public Optional<KnownPlayer> cachedOffline(String name) {
        OfflinePlayer offline = server.getOfflinePlayerIfCached(name);
        if (offline == null) {
            return Optional.empty();
        }
        String knownName = offline.getName() != null ? offline.getName() : name;
        return Optional.of(new KnownPlayer(offline.getUniqueId(), knownName));
    }

    @Override
    public Optional<String> nameOf(UUID playerId) {
        Player online = server.getPlayer(playerId);
        if (online != null) {
            return Optional.of(online.getName());
        }
        return Optional.ofNullable(server.getOfflinePlayer(playerId).getName());
    }
}
