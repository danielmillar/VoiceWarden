package dev.voicewarden.plugin.placeholder;

import dev.voicewarden.api.VoiceMuteRecord;
import dev.voicewarden.api.VoiceWardenAPI;
import dev.voicewarden.plugin.consent.ConsentView;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VoiceWardenExpansion extends PlaceholderExpansion {

    public static final String IDENTIFIER = "voicewarden";

    private final VoiceWardenAPI api;
    private final ConsentView consent;
    private final String version;

    VoiceWardenExpansion(VoiceWardenAPI api, ConsentView consent, String version) {
        this.api = Objects.requireNonNull(api, "api");
        this.consent = Objects.requireNonNull(consent, "consent");
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public @NotNull String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public @NotNull String getAuthor() {
        return "VoiceWarden";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        UUID playerId = player.getUniqueId();
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "muted" -> Boolean.toString(api.isMuted(playerId));
            case "mute_remaining" -> api.getFormattedMuteRemaining(playerId);
            case "mute_reason" -> api.getActiveMute(playerId).map(VoiceMuteRecord::reason).orElse("");
            case "strikes" -> Integer.toString(api.getActiveStrikes(playerId));
            case "consent" -> consent.placeholderStatus(playerId);
            default -> null;
        };
    }
}
