package dev.voicewarden.plugin.pipeline;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.core.moderation.StrikePersistence;
import dev.voicewarden.core.storage.StorageProvider;
import dev.voicewarden.core.storage.StrikeRecord;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class StorageStrikePersistence implements StrikePersistence {

    private final StorageProvider storage;
    private final String serverId;
    private final boolean networkStrikes;

    public StorageStrikePersistence(StorageProvider storage, String serverId, boolean networkStrikes) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.networkStrikes = networkStrikes;
    }

    @Override
    public void recordStrike(UUID playerId, String playerName, FlagSeverity severity, List<String> matchedWords, String transcript, Instant now) {
        storage.recordStrike(StrikeRecord.unsaved(playerId, playerName, severity, String.join(", ", matchedWords), transcript, now, serverId));
    }

    @Override
    public int countRecentStrikes(UUID playerId, Instant since) {
        return storage.countActiveStrikes(playerId, since, networkStrikes);
    }

    @Override
    public int countRecentMutes(UUID playerId, Instant since) {
        return storage.countMutesInWindow(playerId, since, networkStrikes);
    }

    @Override
    public void pardonStrikes(UUID playerId) {
        storage.pardonStrikes(playerId);
    }
}
