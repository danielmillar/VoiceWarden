package dev.voicewarden.plugin;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import dev.voicewarden.plugin.mute.KnownPlayer;
import dev.voicewarden.plugin.mute.PlayerDirectory;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

public final class FakePlayers implements PlayerDirectory {

    private final Map<UUID, Player> online = new LinkedHashMap<>();
    private final Map<String, KnownPlayer> offline = new HashMap<>();

    public static Player mockPlayer(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        lenient().when(player.getUniqueId()).thenReturn(id);
        lenient().when(player.getName()).thenReturn(name);
        lenient().when(player.isOnline()).thenReturn(true);
        return player;
    }

    public Player join(String name) {
        Player player = mockPlayer(name);
        online.put(player.getUniqueId(), player);
        return player;
    }

    public void quit(Player player) {
        online.remove(player.getUniqueId());
        offline.put(player.getName().toLowerCase(Locale.ROOT), new KnownPlayer(player.getUniqueId(), player.getName()));
    }

    public KnownPlayer addOffline(String name) {
        KnownPlayer known = new KnownPlayer(UUID.nameUUIDFromBytes(name.getBytes()), name);
        offline.put(name.toLowerCase(Locale.ROOT), known);
        return known;
    }

    @Override
    public Optional<Player> online(UUID playerId) {
        return Optional.ofNullable(online.get(playerId));
    }

    @Override
    public Optional<Player> onlineExact(String name) {
        return online.values().stream().filter(p -> p.getName().equalsIgnoreCase(name)).findFirst();
    }

    @Override
    public Collection<? extends Player> onlinePlayers() {
        return online.values();
    }

    @Override
    public Optional<KnownPlayer> cachedOffline(String name) {
        return Optional.ofNullable(offline.get(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public Optional<String> nameOf(UUID playerId) {
        Player player = online.get(playerId);
        if (player != null) {
            return Optional.of(player.getName());
        }
        return offline.values().stream().filter(k -> k.id().equals(playerId)).map(KnownPlayer::name).findFirst();
    }
}
