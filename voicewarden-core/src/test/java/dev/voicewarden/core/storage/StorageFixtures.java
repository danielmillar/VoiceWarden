package dev.voicewarden.core.storage;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceMuteRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

final class StorageFixtures {

    static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    private StorageFixtures() {
    }

    static VoiceMuteRecord mute(UUID player, Instant createdAt, Duration length, String serverId) {
        return new VoiceMuteRecord(player, "Steve", "Toxic voice chat", "VoiceWarden", createdAt,
                length == null ? null : createdAt.plus(length), true, serverId);
    }

    static StrikeRecord strike(UUID player, Instant createdAt, String serverId) {
        return StrikeRecord.unsaved(player, "Steve", FlagSeverity.MODERATE, "badword", "you badword", createdAt, serverId);
    }

    static VoiceReportRecord report(UUID reporter, UUID target, Instant createdAt) {
        return VoiceReportRecord.open(reporter, "Alex", target, "Steve", 5, "Steve: hello there", createdAt, "lobby");
    }
}
