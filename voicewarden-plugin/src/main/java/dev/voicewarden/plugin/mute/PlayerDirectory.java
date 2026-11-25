package dev.voicewarden.plugin.mute;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

public interface PlayerDirectory {

    Optional<Player> online(UUID playerId);

    Optional<Player> onlineExact(String name);

    Collection<? extends Player> onlinePlayers();

    Optional<KnownPlayer> cachedOffline(String name);

    Optional<String> nameOf(UUID playerId);
}
