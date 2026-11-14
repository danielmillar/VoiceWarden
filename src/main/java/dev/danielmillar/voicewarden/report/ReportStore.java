package dev.danielmillar.voicewarden.report;

import dev.danielmillar.voicewarden.moderation.TranscriptLine;
import dev.danielmillar.voicewarden.storage.Database;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Voice-report inbox, stored in the {@code reports} and {@code report_lines} tables. Every report is kept in memory
 * (at most {@value #MAX_STORED}) so commands never wait on storage; changes are written on the database thread.
 */
public final class ReportStore {

    private static final int MAX_STORED = 1_000;

    public record StoredReport(UUID id, Instant createdAt, @Nullable UUID reporterId, String reporterName, UUID targetId,
                               String targetName, long windowSeconds, List<TranscriptLine> lines, boolean reviewed,
                               @Nullable String reviewer, @Nullable Instant reviewedAt) {
        public String shortId() {
            return id.toString().substring(0, 8);
        }
    }

    private final ConcurrentHashMap<UUID, StoredReport> reports = new ConcurrentHashMap<>();
    private final Database database;
    private final Logger logger;

    public ReportStore(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** Reads every stored report. Startup only. */
    public void load() {
        try {
            database.read(ReportStore::loadAll).forEach(r -> reports.put(r.id(), r));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not load voice reports from the database; the inbox starts empty", e);
        }
        trim();
    }

    public void add(VoiceReport report) {
        StoredReport stored = new StoredReport(report.id(), report.createdAt(), report.reporterId(),
                report.reporterName(), report.targetId(), report.targetName(), report.window().toSeconds(),
                report.lines(), false, null, null);
        reports.compute(report.id(), (id, previous) -> {
            database.write("voice report " + stored.shortId(), c -> save(c, stored));
            return stored;
        });
        trim();
    }

    public List<StoredReport> open() {
        return reports.values().stream().filter(r -> !r.reviewed())
                .sorted(Comparator.comparing(StoredReport::createdAt).reversed()).toList();
    }

    /** Finds by full id or unique id prefix (as shown in chat). */
    public Optional<StoredReport> find(String idOrPrefix) {
        String needle = idOrPrefix.toLowerCase(Locale.ROOT);
        List<StoredReport> hits = new ArrayList<>();
        for (StoredReport r : reports.values()) {
            if (r.id().toString().startsWith(needle)) {
                hits.add(r);
            }
        }
        return hits.size() == 1 ? Optional.of(hits.getFirst()) : Optional.empty();
    }

    public boolean markReviewed(UUID id, String reviewer) {
        StoredReport updated = reports.computeIfPresent(id, (k, r) -> {
            StoredReport next = new StoredReport(r.id(), r.createdAt(), r.reporterId(), r.reporterName(), r.targetId(),
                    r.targetName(), r.windowSeconds(), r.lines(), true, reviewer, Instant.now());
            database.write("the review of voice report " + r.shortId(), c -> markReviewed(c, next));
            return next;
        });
        return updated != null;
    }

    /** Keeps the newest {@value #MAX_STORED} reports, dropping reviewed ones first. */
    private void trim() {
        if (reports.size() <= MAX_STORED) {
            return;
        }
        reports.values().stream()
                .sorted(Comparator.comparing(StoredReport::reviewed).reversed().thenComparing(StoredReport::createdAt))
                .limit(reports.size() - MAX_STORED)
                .forEach(r -> reports.computeIfPresent(r.id(), (id, current) -> {
                    database.write("the removal of voice report " + r.shortId(), c -> delete(c, id));
                    return null;
                }));
    }

    private static List<StoredReport> loadAll(Connection c) throws SQLException {
        Map<UUID, List<TranscriptLine>> lines = new HashMap<>();
        try (PreparedStatement s = c.prepareStatement(
                "SELECT report_id, spoken_at, text FROM report_lines ORDER BY report_id, position");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                lines.computeIfAbsent(UUID.fromString(r.getString(1)), id -> new ArrayList<>())
                        .add(new TranscriptLine(Instant.parse(r.getString(2)), r.getString(3)));
            }
        }
        List<StoredReport> loaded = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT id, created_at, reporter_id, reporter_name, target_id, target_name, window_seconds,
                       reviewed, reviewer, reviewed_at
                FROM reports""");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                UUID id = UUID.fromString(r.getString(1));
                String reporter = r.getString(3);
                String reviewedAt = r.getString(10);
                loaded.add(new StoredReport(id, Instant.parse(r.getString(2)),
                        reporter == null ? null : UUID.fromString(reporter), r.getString(4),
                        UUID.fromString(r.getString(5)), r.getString(6), r.getLong(7),
                        List.copyOf(lines.getOrDefault(id, List.of())), r.getBoolean(8), r.getString(9),
                        reviewedAt == null ? null : Instant.parse(reviewedAt)));
            }
        }
        return loaded;
    }

    private static void save(Connection c, StoredReport report) throws SQLException {
        delete(c, report.id());
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO reports (id, created_at, reporter_id, reporter_name, target_id, target_name,
                                     window_seconds, reviewed, reviewer, reviewed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            s.setString(1, report.id().toString());
            s.setString(2, report.createdAt().toString());
            s.setString(3, report.reporterId() == null ? null : report.reporterId().toString());
            s.setString(4, report.reporterName());
            s.setString(5, report.targetId().toString());
            s.setString(6, report.targetName());
            s.setLong(7, report.windowSeconds());
            s.setBoolean(8, report.reviewed());
            s.setString(9, report.reviewer());
            s.setString(10, report.reviewedAt() == null ? null : report.reviewedAt().toString());
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO report_lines (report_id, position, spoken_at, text) VALUES (?, ?, ?, ?)")) {
            int position = 0;
            for (TranscriptLine line : report.lines()) {
                s.setString(1, report.id().toString());
                s.setInt(2, position++);
                s.setString(3, line.spokenAt().toString());
                s.setString(4, line.text());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static void markReviewed(Connection c, StoredReport report) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE reports SET reviewed = 1, reviewer = ?, reviewed_at = ? WHERE id = ?")) {
            s.setString(1, report.reviewer());
            s.setString(2, report.reviewedAt() == null ? null : report.reviewedAt().toString());
            s.setString(3, report.id().toString());
            s.executeUpdate();
        }
    }

    private static void delete(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM report_lines WHERE report_id = ?")) {
            s.setString(1, id.toString());
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("DELETE FROM reports WHERE id = ?")) {
            s.setString(1, id.toString());
            s.executeUpdate();
        }
    }
}
