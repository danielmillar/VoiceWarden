package dev.voicewarden.core.storage;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceMuteRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

abstract class JdbcStorageProvider implements StorageProvider {

    static final String MUTES = "vw_mutes";
    static final String STRIKES = "vw_strikes";
    static final String REPORTS = "vw_voice_reports";

    private static final String MUTE_COLUMNS =
            "player_id, player_name, reason, moderator, created_at, expires_at, active, server_id";
    private static final String STRIKE_COLUMNS =
            "id, player_id, player_name, severity, matched_words, transcript, created_at, server_id, pardoned";
    private static final String REPORT_COLUMNS =
            "id, reporter_id, reporter_name, target_id, target_name, window_minutes, transcript_snapshot, "
                    + "created_at, reviewed, reviewed_by, reviewed_at, server_id";
    private static final String ACTIVE_MUTE_FILTER = "active = 1 AND (expires_at IS NULL OR expires_at > ?)";

    protected final String serverId;
    protected final int queryTimeoutSeconds;

    protected JdbcStorageProvider(StorageConfig config) {
        this.serverId = config.serverId();
        this.queryTimeoutSeconds = config.queryTimeoutSeconds();
    }

    @FunctionalInterface
    protected interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    protected abstract <T> T withMuteConnection(SqlWork<T> work) throws SQLException;

    protected abstract <T> T withReportConnection(SqlWork<T> work) throws SQLException;

    private <T> T mutes(String action, SqlWork<T> work) {
        try {
            return withMuteConnection(work);
        } catch (SQLException e) {
            throw new StorageException("Storage failed to " + action + ": " + e.getMessage(), e);
        }
    }

    private <T> T reports(String action, SqlWork<T> work) {
        try {
            return withReportConnection(work);
        } catch (SQLException e) {
            throw new StorageException("Storage failed to " + action + ": " + e.getMessage(), e);
        }
    }

    protected PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(queryTimeoutSeconds);
        return statement;
    }

    protected PreparedStatement prepareInsert(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        statement.setQueryTimeout(queryTimeoutSeconds);
        return statement;
    }

    protected void executeDdl(Connection connection, List<String> statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Override
    public void saveMute(VoiceMuteRecord mute) {
        Objects.requireNonNull(mute, "mute");
        mutes("save mute", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "INSERT INTO " + MUTES + " (" + MUTE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, mute.playerId().toString());
                statement.setString(2, mute.playerName());
                statement.setString(3, mute.reason());
                statement.setString(4, mute.moderator());
                statement.setLong(5, mute.createdAt().toEpochMilli());
                setNullableInstant(statement, 6, mute.expiresAt());
                statement.setBoolean(7, mute.active());
                statement.setString(8, mute.serverId());
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public int deactivateActiveMutes(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return mutes("deactivate mutes", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "UPDATE " + MUTES + " SET active = 0 WHERE player_id = ? AND active = 1")) {
                statement.setString(1, playerId.toString());
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public Optional<VoiceMuteRecord> findActiveMute(UUID playerId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(now, "now");
        return mutes("find active mute", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT " + MUTE_COLUMNS + " FROM " + MUTES + " WHERE player_id = ? AND " + ACTIVE_MUTE_FILTER
                            + " ORDER BY created_at DESC, id DESC LIMIT 1")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, now.toEpochMilli());
                List<VoiceMuteRecord> found = readMutes(statement);
                return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
            }
        });
    }

    @Override
    public List<VoiceMuteRecord> findAllActiveMutes(Instant now) {
        Objects.requireNonNull(now, "now");
        return mutes("list active mutes", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT " + MUTE_COLUMNS + " FROM " + MUTES + " WHERE " + ACTIVE_MUTE_FILTER
                            + " ORDER BY created_at DESC, id DESC")) {
                statement.setLong(1, now.toEpochMilli());
                return readMutes(statement);
            }
        });
    }

    @Override
    public List<VoiceMuteRecord> findMuteHistory(UUID playerId, int limit) {
        Objects.requireNonNull(playerId, "playerId");
        return mutes("read mute history", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT " + MUTE_COLUMNS + " FROM " + MUTES + " WHERE player_id = ?"
                            + " ORDER BY created_at DESC, id DESC LIMIT ?")) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, Math.max(0, limit));
                return readMutes(statement);
            }
        });
    }

    @Override
    public int countMutesInWindow(UUID playerId, Instant since, boolean networkWide) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(since, "since");
        return mutes("count mutes", connection -> {
            String sql = "SELECT COUNT(*) FROM " + MUTES + " WHERE player_id = ? AND created_at >= ?"
                    + (networkWide ? "" : " AND server_id = ?");
            try (PreparedStatement statement = prepare(connection, sql)) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, since.toEpochMilli());
                if (!networkWide) {
                    statement.setString(3, serverId);
                }
                return readCount(statement);
            }
        });
    }

    @Override
    public StrikeRecord recordStrike(StrikeRecord strike) {
        Objects.requireNonNull(strike, "strike");
        return mutes("record strike", connection -> {
            try (PreparedStatement statement = prepareInsert(connection,
                    "INSERT INTO " + STRIKES + " (player_id, player_name, severity, matched_words, transcript,"
                            + " created_at, server_id, pardoned) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, strike.playerId().toString());
                statement.setString(2, strike.playerName());
                statement.setString(3, strike.severity().name());
                statement.setString(4, strike.matchedWords());
                statement.setString(5, strike.transcript());
                statement.setLong(6, strike.createdAt().toEpochMilli());
                statement.setString(7, strike.serverId());
                statement.setBoolean(8, strike.pardoned());
                statement.executeUpdate();
                return strike.withId(generatedId(statement));
            }
        });
    }

    @Override
    public int countActiveStrikes(UUID playerId, Instant since, boolean networkWide) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(since, "since");
        return mutes("count strikes", connection -> {
            String sql = "SELECT COUNT(*) FROM " + STRIKES + " WHERE player_id = ? AND pardoned = 0 AND created_at >= ?"
                    + (networkWide ? "" : " AND server_id = ?");
            try (PreparedStatement statement = prepare(connection, sql)) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, since.toEpochMilli());
                if (!networkWide) {
                    statement.setString(3, serverId);
                }
                return readCount(statement);
            }
        });
    }

    @Override
    public int pardonStrikes(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return mutes("pardon strikes", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "UPDATE " + STRIKES + " SET pardoned = 1 WHERE player_id = ? AND pardoned = 0")) {
                statement.setString(1, playerId.toString());
                return statement.executeUpdate();
            }
        });
    }

    List<StrikeRecord> findStrikes(UUID playerId) {
        return mutes("list strikes", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT " + STRIKE_COLUMNS + " FROM " + STRIKES + " WHERE player_id = ? ORDER BY created_at, id")) {
                statement.setString(1, playerId.toString());
                List<StrikeRecord> strikes = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        strikes.add(new StrikeRecord(
                                rows.getLong("id"),
                                UUID.fromString(rows.getString("player_id")),
                                rows.getString("player_name"),
                                FlagSeverity.valueOf(rows.getString("severity")),
                                rows.getString("matched_words"),
                                rows.getString("transcript"),
                                Instant.ofEpochMilli(rows.getLong("created_at")),
                                rows.getString("server_id"),
                                rows.getBoolean("pardoned")));
                    }
                }
                return strikes;
            }
        });
    }

    @Override
    public VoiceReportRecord saveReport(VoiceReportRecord report) {
        Objects.requireNonNull(report, "report");
        return reports("save voice report", connection -> {
            try (PreparedStatement statement = prepareInsert(connection,
                    "INSERT INTO " + REPORTS + " (reporter_id, reporter_name, target_id, target_name, window_minutes,"
                            + " transcript_snapshot, created_at, reviewed, reviewed_by, reviewed_at, server_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, report.reporterId().toString());
                statement.setString(2, report.reporterName());
                statement.setString(3, report.targetId().toString());
                statement.setString(4, report.targetName());
                statement.setInt(5, report.windowMinutes());
                statement.setString(6, report.transcriptSnapshot());
                statement.setLong(7, report.createdAt().toEpochMilli());
                statement.setBoolean(8, report.reviewed());
                statement.setString(9, report.reviewedBy());
                setNullableInstant(statement, 10, report.reviewedAt());
                statement.setString(11, report.serverId());
                statement.executeUpdate();
                return report.withId(generatedId(statement));
            }
        });
    }

    @Override
    public List<VoiceReportRecord> findReports(boolean unreviewedOnly, int limit) {
        return reports("list voice reports", connection -> {
            String sql = "SELECT " + REPORT_COLUMNS + " FROM " + REPORTS
                    + (unreviewedOnly ? " WHERE reviewed = 0" : "")
                    + " ORDER BY created_at DESC, id DESC LIMIT ?";
            try (PreparedStatement statement = prepare(connection, sql)) {
                statement.setInt(1, Math.max(0, limit));
                return readReports(statement);
            }
        });
    }

    @Override
    public Optional<VoiceReportRecord> findReportById(long id) {
        return reports("find voice report", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT " + REPORT_COLUMNS + " FROM " + REPORTS + " WHERE id = ?")) {
                statement.setLong(1, id);
                List<VoiceReportRecord> found = readReports(statement);
                return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
            }
        });
    }

    @Override
    public boolean markReportReviewed(long id, String reviewer, Instant reviewedAt) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        return reports("mark voice report reviewed", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "UPDATE " + REPORTS + " SET reviewed = 1, reviewed_by = ?, reviewed_at = ? WHERE id = ? AND reviewed = 0")) {
                statement.setString(1, reviewer);
                statement.setLong(2, reviewedAt.toEpochMilli());
                statement.setLong(3, id);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public int countOpenReportsByReporter(UUID reporterId) {
        Objects.requireNonNull(reporterId, "reporterId");
        return reports("count open voice reports", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT COUNT(*) FROM " + REPORTS + " WHERE reporter_id = ? AND reviewed = 0")) {
                statement.setString(1, reporterId.toString());
                return readCount(statement);
            }
        });
    }

    private static void setNullableInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value.toEpochMilli());
        }
    }

    private static Instant readNullableInstant(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static int readCount(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getLong(1);
            }
        }
        throw new SQLException("The database did not return a generated id");
    }

    private static List<VoiceMuteRecord> readMutes(PreparedStatement statement) throws SQLException {
        List<VoiceMuteRecord> mutes = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                mutes.add(new VoiceMuteRecord(
                        UUID.fromString(rows.getString("player_id")),
                        rows.getString("player_name"),
                        rows.getString("reason"),
                        rows.getString("moderator"),
                        Instant.ofEpochMilli(rows.getLong("created_at")),
                        readNullableInstant(rows, "expires_at"),
                        rows.getBoolean("active"),
                        rows.getString("server_id")));
            }
        }
        return mutes;
    }

    private static List<VoiceReportRecord> readReports(PreparedStatement statement) throws SQLException {
        List<VoiceReportRecord> reports = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                reports.add(new VoiceReportRecord(
                        rows.getLong("id"),
                        UUID.fromString(rows.getString("reporter_id")),
                        rows.getString("reporter_name"),
                        UUID.fromString(rows.getString("target_id")),
                        rows.getString("target_name"),
                        rows.getInt("window_minutes"),
                        rows.getString("transcript_snapshot"),
                        Instant.ofEpochMilli(rows.getLong("created_at")),
                        rows.getBoolean("reviewed"),
                        rows.getString("reviewed_by"),
                        readNullableInstant(rows, "reviewed_at"),
                        rows.getString("server_id")));
            }
        }
        return reports;
    }
}
