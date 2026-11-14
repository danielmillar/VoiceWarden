package dev.danielmillar.voicewarden.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * VoiceWarden's SQLite database ({@code data/voicewarden.db}). One connection, used only by one writer thread, so
 * statements never interleave and writes land in the order they were submitted. Services keep their state in memory
 * and submit writes from inside the same atomic update that changes it; nothing here runs on the server or voice
 * threads except {@link #read} during startup.
 */
public final class Database implements AutoCloseable {

    public static final int SCHEMA_VERSION = 4;
    private static final int SQLITE_CORRUPT = 11;
    private static final int SQLITE_NOTADB = 26;

    /** The file exists but is not a usable SQLite database. Only this case moves the file aside. */
    private static final class CorruptDatabase extends SQLException {
        CorruptDatabase(String message, @org.jspecify.annotations.Nullable Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface Change {
        void run(Connection connection) throws SQLException;
    }

    private final Connection connection;
    private final ExecutorService thread;
    private final Logger logger;

    private Database(Connection connection, Logger logger) {
        this.connection = connection;
        this.logger = logger;
        this.thread = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("VoiceWarden Database").daemon().factory());
    }

    /**
     * Opens (or creates) the database. A file that is not a SQLite database, or fails its integrity check, is moved
     * aside as {@code voicewarden.db.corrupt-<uuid>} and a fresh one is created. Any other failure (locked file, I/O
     * error, newer schema) leaves the file alone and is thrown, so a healthy database is never set aside.
     */
    public static Database open(Path file, Logger logger) throws IOException, SQLException {
        Files.createDirectories(file.getParent());
        try {
            return connect(file, logger);
        } catch (CorruptDatabase e) {
            if (!Files.exists(file)) {
                throw e;
            }
            Path preserved = file.resolveSibling(file.getFileName() + ".corrupt-" + UUID.randomUUID());
            Files.move(file, preserved);
            for (String suffix : new String[]{"-wal", "-shm"}) {
                Path sidecar = file.resolveSibling(file.getFileName() + suffix);
                if (Files.exists(sidecar)) {
                    Files.move(sidecar, preserved.resolveSibling(preserved.getFileName() + suffix));
                }
            }
            logger.log(Level.WARNING, "Could not read " + file + "; moved it to " + preserved
                    + " and started with an empty database.", e);
            return connect(file, logger);
        }
    }

    private static Connection openConnection(Path file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("The SQLite driver (org.xerial:sqlite-jdbc) is not on the server classpath", e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    }

    private static Database connect(Path file, Logger logger) throws SQLException {
        Connection connection = openConnection(file);
        try {
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                try (ResultSet check = s.executeQuery("PRAGMA quick_check")) {
                    if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) {
                        throw new CorruptDatabase("Integrity check failed", null);
                    }
                } catch (CorruptDatabase e) {
                    throw e;
                } catch (SQLException e) {
                    int code = e.getErrorCode() & 0xff;
                    if (code == SQLITE_NOTADB || code == SQLITE_CORRUPT) {
                        throw new CorruptDatabase(e.getMessage(), e);
                    }
                    throw e;
                }
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
            }
            migrate(connection);
            return new Database(connection, logger);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    private static void migrate(Connection connection) throws SQLException {
        int version;
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
            version = r.next() ? r.getInt(1) : 0;
        }
        if (version > SCHEMA_VERSION) {
            // Not an SQLException: a newer database is valid data and must not be moved aside as corrupt.
            throw new IllegalStateException("voicewarden.db was written by a newer VoiceWarden (schema " + version
                    + "); update VoiceWarden or restore an older database");
        }
        if (version < 1) {
            inTransaction(connection, Database::createVersion1);
        }
        if (version < 2) {
            inTransaction(connection, Database::migrateToVersion2);
        }
        if (version < 3) {
            inTransaction(connection, Database::migrateToVersion3);
        }
        if (version < 4) {
            inTransaction(connection, Database::migrateToVersion4);
        }
    }

    private static void createVersion1(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE mutes (
                        player_id   TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        created_at  INTEGER NOT NULL,
                        expires_at  INTEGER NOT NULL,
                        reason      TEXT NOT NULL,
                        actor       TEXT NOT NULL,
                        automatic   INTEGER NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE mute_mirrors (
                        player_id         TEXT NOT NULL,
                        position          INTEGER NOT NULL,
                        speak_permission  TEXT NOT NULL,
                        listen_permission TEXT NOT NULL,
                        server_context    TEXT NOT NULL,
                        PRIMARY KEY (player_id, position)
                    )""");
            s.execute("""
                    CREATE TABLE offenses (
                        player_id       TEXT PRIMARY KEY,
                        count           INTEGER NOT NULL,
                        last_offense_at INTEGER NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE reports (
                        id             TEXT PRIMARY KEY,
                        created_at     TEXT NOT NULL,
                        reporter_id    TEXT,
                        reporter_name  TEXT NOT NULL,
                        target_id      TEXT NOT NULL,
                        target_name    TEXT NOT NULL,
                        window_seconds INTEGER NOT NULL,
                        reviewed       INTEGER NOT NULL,
                        reviewer       TEXT,
                        reviewed_at    TEXT
                    )""");
            s.execute("""
                    CREATE TABLE report_lines (
                        report_id TEXT NOT NULL,
                        position  INTEGER NOT NULL,
                        spoken_at TEXT NOT NULL,
                        text      TEXT NOT NULL,
                        PRIMARY KEY (report_id, position)
                    )""");
            s.execute("PRAGMA user_version = 1");
        }
    }

    /** Adds the mute history. Mutes active at upgrade get their {@code mute} event, so later lifts have a start. */
    private static void migrateToVersion2(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE mute_events (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_id   TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        type        TEXT NOT NULL,
                        at          INTEGER NOT NULL,
                        actor       TEXT NOT NULL,
                        reason      TEXT NOT NULL,
                        expires_at  INTEGER,
                        automatic   INTEGER NOT NULL
                    )""");
            s.execute("CREATE INDEX mute_events_by_player ON mute_events (player_id, id)");
            s.execute("""
                    INSERT INTO mute_events (player_id, player_name, type, at, actor, reason, expires_at, automatic)
                    SELECT player_id, player_name, 'mute', created_at, actor, reason, expires_at, automatic
                    FROM mutes ORDER BY created_at""");
            s.execute("PRAGMA user_version = 2");
        }
    }

    /** Adds each player's latest answer to the voice moderation consent prompt. */
    private static void migrateToVersion3(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE consents (
                        player_id     TEXT PRIMARY KEY,
                        accepted      INTEGER NOT NULL,
                        rules_version INTEGER NOT NULL,
                        decided_at    INTEGER NOT NULL
                    )""");
            s.execute("PRAGMA user_version = 3");
        }
    }

    /**
     * Records when each player with a consent answer was last seen, so answers of players who stopped playing can be
     * deleted. Existing answers count as seen at the upgrade, so none is pruned before its player had a chance to return.
     */
    private static void migrateToVersion4(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE consents ADD COLUMN last_seen_at INTEGER NOT NULL DEFAULT 0");
            s.execute("UPDATE consents SET last_seen_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000");
            s.execute("PRAGMA user_version = 4");
        }
    }

    /** Runs a read on the database thread and waits for it. Use at startup, never on a hot path. */
    public <T> T read(Work<T> work) throws SQLException {
        try {
            return query(work).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof SQLException sql) {
                throw sql;
            }
            throw e;
        }
    }

    /**
     * Queues a read behind the writes submitted so far. Completes exceptionally with the {@link SQLException}, or when
     * the database is closed.
     */
    public <T> CompletableFuture<T> query(Work<T> work) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return work.run(connection);
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, thread);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Queues a change to run in its own transaction. Failures are logged; the in-memory state that triggered the write
     * stays in effect. The future completes either way, once the change has been attempted.
     */
    public CompletableFuture<Void> write(String what, Change change) {
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    inTransaction(connection, change);
                } catch (SQLException | RuntimeException e) {
                    logger.log(Level.WARNING, "Could not save " + what + " to the database", e);
                }
            }, thread);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not save " + what + ": the database is closed", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Completes when every write submitted so far has been attempted. */
    public CompletableFuture<Void> flush() {
        return write("nothing", c -> { });
    }

    /** Runs {@code change} in one transaction on the calling thread. Only for code already on the database thread. */
    public static void inTransaction(Connection connection, Change change) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            change.run(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    /** Lets queued writes finish (bounded), then closes the connection. */
    public void close(Duration wait) {
        thread.shutdown();
        try {
            if (!thread.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                logger.warning("Database writes did not finish within " + wait.toSeconds() + "s; some changes may be lost");
                thread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not close the database", e);
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(10));
    }
}
