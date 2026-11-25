package dev.voicewarden.core.moderation;

import dev.voicewarden.api.FlagSeverity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StrikePersistence {

    void recordStrike(UUID playerId, String playerName, FlagSeverity severity, List<String> matchedWords, String transcript, Instant now);

    int countRecentStrikes(UUID playerId, Instant since);

    int countRecentMutes(UUID playerId, Instant since);

    void pardonStrikes(UUID playerId);
}
