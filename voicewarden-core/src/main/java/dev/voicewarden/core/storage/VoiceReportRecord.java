package dev.voicewarden.core.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VoiceReportRecord(
        long id,
        UUID reporterId,
        String reporterName,
        UUID targetId,
        String targetName,
        int windowMinutes,
        String transcriptSnapshot,
        Instant createdAt,
        boolean reviewed,
        String reviewedBy,
        Instant reviewedAt,
        String serverId) {

    public VoiceReportRecord {
        Objects.requireNonNull(reporterId, "reporterId");
        Objects.requireNonNull(reporterName, "reporterName");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(transcriptSnapshot, "transcriptSnapshot");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(serverId, "serverId");
        if (windowMinutes < 0) {
            throw new IllegalArgumentException("windowMinutes must not be negative, got " + windowMinutes);
        }
    }

    public static VoiceReportRecord open(
            UUID reporterId,
            String reporterName,
            UUID targetId,
            String targetName,
            int windowMinutes,
            String transcriptSnapshot,
            Instant createdAt,
            String serverId) {
        return new VoiceReportRecord(
                0L, reporterId, reporterName, targetId, targetName, windowMinutes, transcriptSnapshot,
                createdAt, false, null, null, serverId);
    }

    public VoiceReportRecord withId(long newId) {
        return new VoiceReportRecord(
                newId, reporterId, reporterName, targetId, targetName, windowMinutes, transcriptSnapshot,
                createdAt, reviewed, reviewedBy, reviewedAt, serverId);
    }

    public VoiceReportRecord markedReviewed(String reviewer, Instant at) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(at, "at");
        return new VoiceReportRecord(
                id, reporterId, reporterName, targetId, targetName, windowMinutes, transcriptSnapshot,
                createdAt, true, reviewer, at, serverId);
    }
}
