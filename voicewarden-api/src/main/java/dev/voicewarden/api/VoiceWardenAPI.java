package dev.voicewarden.api;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

public interface VoiceWardenAPI {

    boolean isMuted(UUID playerId);

    Optional<VoiceMuteRecord> getActiveMute(UUID playerId);

    Optional<Duration> getRemainingMute(UUID playerId);

    String getFormattedMuteRemaining(UUID playerId);

    int getActiveStrikes(UUID playerId);

    boolean hasConsent(UUID playerId);

    CompletableFuture<VoiceMuteRecord> mutePlayer(
            UUID playerId, String playerName, @Nullable Duration duration, String reason, String moderator);

    CompletableFuture<Boolean> unmutePlayer(UUID playerId, String moderator);

    CompletableFuture<Boolean> pardonPlayer(UUID playerId, String moderator);

    List<VoiceMuteRecord> getMuteHistory(UUID playerId, int limit);

    Collection<VoiceMuteRecord> getActiveMutes();
}
