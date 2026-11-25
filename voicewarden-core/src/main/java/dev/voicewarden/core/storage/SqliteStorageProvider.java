package dev.voicewarden.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import org.sqlite.SQLiteDataSource;

public final class SqliteStorageProvider extends JdbcStorageProvider {

    private static final List<String> MUTE_SCHEMA = List.of(
            "CREATE TABLE IF NOT EXISTS " + MUTES + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " player_id TEXT NOT NULL,"
                    + " player_name TEXT NOT NULL,"
                    + " reason TEXT NOT NULL,"
                    + " moderator TEXT NOT NULL,"
                    + " created_at INTEGER NOT NULL,"
                    + " expires_at INTEGER,"
                    + " active INTEGER NOT NULL DEFAULT 1,"
                    + " server_id TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_vw_mutes_player ON " + MUTES + " (player_id, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_vw_mutes_active ON " + MUTES + " (active, expires_at)",
            "CREATE TABLE IF NOT EXISTS " + STRIKES + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " player_id TEXT NOT NULL,"
                    + " player_name TEXT NOT NULL,"
                    + " severity TEXT NOT NULL,"
                    + " matched_words TEXT NOT NULL,"
                    + " transcript TEXT NOT NULL,"
                    + " created_at INTEGER NOT NULL,"
                    + " server_id TEXT NOT NULL,"
                    + " pardoned INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS idx_vw_strikes_player ON " + STRIKES + " (player_id, pardoned, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_vw_strikes_server ON " + STRIKES + " (server_id, player_id, created_at)");

    private static final List<String> REPORT_SCHEMA = List.of(
            "CREATE TABLE IF NOT EXISTS " + REPORTS + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " reporter_id TEXT NOT NULL,"
                    + " reporter_name TEXT NOT NULL,"
                    + " target_id TEXT NOT NULL,"
                    + " target_name TEXT NOT NULL,"
                    + " window_minutes INTEGER NOT NULL,"
                    + " transcript_snapshot TEXT NOT NULL,"
                    + " created_at INTEGER NOT NULL,"
                    + " reviewed INTEGER NOT NULL DEFAULT 0,"
                    + " reviewed_by TEXT,"
                    + " reviewed_at INTEGER,"
                    + " server_id TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_vw_reports_open ON " + REPORTS + " (reviewed, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_vw_reports_reporter ON " + REPORTS + " (reporter_id, reviewed)");

    private final Path muteDatabase;
    private final Path reportDatabase;
    private final Connection muteConnection;
    private final Connection reportConnection;
    private final ReentrantLock muteLock = new ReentrantLock();
    private final ReentrantLock reportLock = new ReentrantLock();
    private volatile boolean closed;

    public SqliteStorageProvider(StorageConfig config) {
        super(config);
        this.muteDatabase = config.muteDatabasePath();
        this.reportDatabase = config.reportDatabasePath();
        try {
            Files.createDirectories(config.dataDirectory());
        } catch (IOException e) {
            throw new StorageException("Cannot create storage directory " + config.dataDirectory() + ": " + e.getMessage(), e);
        }
        Connection mutes = null;
        try {
            mutes = open(muteDatabase, config);
            executeDdl(mutes, MUTE_SCHEMA);
            Connection reports = open(reportDatabase, config);
            try {
                executeDdl(reports, REPORT_SCHEMA);
            } catch (SQLException e) {
                closeQuietly(reports);
                throw e;
            }
            this.muteConnection = mutes;
            this.reportConnection = reports;
        } catch (SQLException e) {
            closeQuietly(mutes);
            throw new StorageException("Cannot open SQLite storage in " + config.dataDirectory() + ": " + e.getMessage(), e);
        }
    }

    public Path muteDatabase() {
        return muteDatabase;
    }

    public Path reportDatabase() {
        return reportDatabase;
    }

    String journalMode(boolean reportDb) {
        try {
            return (reportDb ? withReportConnection(SqliteStorageProvider::readJournalMode)
                    : withMuteConnection(SqliteStorageProvider::readJournalMode));
        } catch (SQLException e) {
            throw new StorageException("Cannot read journal mode: " + e.getMessage(), e);
        }
    }

    @Override
    protected <T> T withMuteConnection(SqlWork<T> work) throws SQLException {
        return locked(muteLock, muteConnection, work);
    }

    @Override
    protected <T> T withReportConnection(SqlWork<T> work) throws SQLException {
        return locked(reportLock, reportConnection, work);
    }

    @Override
    public boolean isAvailable() {
        if (closed) {
            return false;
        }
        try {
            return withMuteConnection(connection -> connection.isValid(queryTimeoutSeconds))
                    && withReportConnection(connection -> connection.isValid(queryTimeoutSeconds));
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        muteLock.lock();
        reportLock.lock();
        try {
            closed = true;
            closeQuietly(muteConnection);
            closeQuietly(reportConnection);
        } finally {
            reportLock.unlock();
            muteLock.unlock();
        }
    }

    private <T> T locked(ReentrantLock lock, Connection connection, SqlWork<T> work) throws SQLException {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted waiting for SQLite connection lock", e);
        }
        try {
            if (closed) {
                throw new SQLException("SQLite storage is closed");
            }
            return work.run(connection);
        } finally {
            lock.unlock();
        }
    }

    private Connection open(Path file, StorageConfig config) throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA synchronous=NORMAL;");
            statement.execute("PRAGMA busy_timeout=" + config.queryTimeoutMs() + ";");
            statement.execute("PRAGMA foreign_keys=ON;");
        } catch (SQLException e) {
            closeQuietly(connection);
            throw e;
        }
        return connection;
    }

    private static String readJournalMode(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA journal_mode;")) {
            return rows.next() ? rows.getString(1).toLowerCase(Locale.ROOT) : "";
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
