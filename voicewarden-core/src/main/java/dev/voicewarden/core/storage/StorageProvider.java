package dev.voicewarden.core.storage;

import dev.voicewarden.api.VoiceMuteRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageProvider extends AutoCloseable {

    void saveMute(VoiceMuteRecord mute);

    int deactivateActiveMutes(UUID playerId);

    Optional<VoiceMuteRecord> findActiveMute(UUID playerId, Instant now);

    List<VoiceMuteRecord> findAllActiveMutes(Instant now);

    List<VoiceMuteRecord> findMuteHistory(UUID playerId, int limit);

    int countMutesInWindow(UUID playerId, Instant since, boolean networkWide);

    StrikeRecord recordStrike(StrikeRecord strike);

    int countActiveStrikes(UUID playerId, Instant since, boolean networkWide);

    int pardonStrikes(UUID playerId);

    default PardonResult pardonPlayer(UUID playerId) {
        int strikes = pardonStrikes(playerId);
        int mutes = deactivateActiveMutes(playerId);
        return new PardonResult(strikes, mutes);
    }

    VoiceReportRecord saveReport(VoiceReportRecord report);

    List<VoiceReportRecord> findReports(boolean unreviewedOnly, int limit);

    Optional<VoiceReportRecord> findReportById(long id);

    boolean markReportReviewed(long id, String reviewer, Instant reviewedAt);

    int countOpenReportsByReporter(UUID reporterId);

    boolean isAvailable();

    @Override
    void close();
}
