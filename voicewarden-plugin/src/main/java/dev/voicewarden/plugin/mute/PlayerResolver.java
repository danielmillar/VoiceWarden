package dev.voicewarden.plugin.mute;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

public final class PlayerResolver {

    public static final String BEDROCK_PREFIX = ".";

    private final PlayerDirectory directory;
    private final Map<String, KnownPlayer> seen = new ConcurrentHashMap<>();

    public PlayerResolver(PlayerDirectory directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public void remember(UUID playerId, String name) {
        seen.put(name.toLowerCase(Locale.ROOT), new KnownPlayer(playerId, name));
    }

    public Optional<KnownPlayer> resolve(String input) {
        String text = Objects.requireNonNull(input, "input").strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Optional<UUID> uuid = parseUuid(text);
        if (uuid.isPresent()) {
            UUID id = uuid.get();
            return Optional.of(new KnownPlayer(id, directory.nameOf(id).orElse(text)));
        }
        Optional<KnownPlayer> direct = byName(text);
        if (direct.isPresent()) {
            return direct;
        }
        if (text.startsWith(BEDROCK_PREFIX) && text.length() > BEDROCK_PREFIX.length()) {
            return byName(text.substring(BEDROCK_PREFIX.length()));
        }
        return byName(BEDROCK_PREFIX + text);
    }

    private Optional<KnownPlayer> byName(String name) {
        Optional<Player> online = directory.onlineExact(name);
        if (online.isPresent()) {
            Player player = online.get();
            return Optional.of(new KnownPlayer(player.getUniqueId(), player.getName()));
        }
        KnownPlayer remembered = seen.get(name.toLowerCase(Locale.ROOT));
        if (remembered != null) {
            return Optional.of(remembered);
        }
        return directory.cachedOffline(name);
    }

    private static Optional<UUID> parseUuid(String text) {
        if (text.length() != 36 && text.length() != 32) {
            return Optional.empty();
        }
        String dashed = text.length() == 32
                ? text.substring(0, 8) + "-" + text.substring(8, 12) + "-" + text.substring(12, 16) + "-"
                        + text.substring(16, 20) + "-" + text.substring(20)
                : text;
        try {
            return Optional.of(UUID.fromString(dashed));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
