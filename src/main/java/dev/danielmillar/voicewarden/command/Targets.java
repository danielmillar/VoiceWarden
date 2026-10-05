package dev.danielmillar.voicewarden.command;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.danielmillar.voicewarden.VoiceWardenPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Resolves player names (online, recently heard, or any LuckPerms user) without blocking. */
final class Targets {

    record Target(UUID id, String name) {
    }

    private Targets() {
    }

    static CompletableFuture<Optional<Target>> resolve(VoiceWardenPlugin plugin, String name) {
        Player online = plugin.players().byName(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new Target(online.getUniqueId(), online.getName())));
        }
        for (Map.Entry<UUID, String> known : plugin.transcriptBuffer().knownPlayers().entrySet()) {
            if (known.getValue().equalsIgnoreCase(name)) {
                return CompletableFuture.completedFuture(Optional.of(new Target(known.getKey(), known.getValue())));
            }
        }
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name); // never does a web lookup
        if (cached != null) {
            String cachedName = cached.getName() != null ? cached.getName() : name;
            return CompletableFuture.completedFuture(Optional.of(new Target(cached.getUniqueId(), cachedName)));
        }
        return plugin.luckPerms().lookupUuid(name)
                .thenApply(id -> id == null ? Optional.<Target>empty() : Optional.of(new Target(id, name)))
                .exceptionally(e -> Optional.empty());
    }

    static CompletableFuture<Suggestions> suggest(VoiceWardenPlugin plugin, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        for (Player player : plugin.players().all()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                builder.suggest(player.getName());
            }
        }
        return builder.buildFuture();
    }
}
