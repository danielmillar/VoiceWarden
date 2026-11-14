package dev.danielmillar.voicewarden.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {
    @TempDir Path folder;

    @Test void unreadableFileIsMovedAsideAndReplaced() throws Exception {
        Path file = folder.resolve("data/voicewarden.db");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not a database");
        try (Database db = Database.open(file, Logger.getAnonymousLogger())) {
            assertEquals(0, (int) db.<Integer>read(c -> {
                try (Statement s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM mutes")) {
                    r.next();
                    return r.getInt(1);
                }
            }));
        }
        try (var files = Files.list(file.getParent())) {
            var preserved = files.filter(p -> p.getFileName().toString().startsWith("voicewarden.db.corrupt-")).toList();
            assertEquals(1, preserved.size());
            assertEquals("not a database", Files.readString(preserved.getFirst()));
        }
    }

    @Test void newerSchemaIsRefusedAndLeftInPlace() throws Exception {
        Path file = folder.resolve("data/voicewarden.db");
        try (Database db = Database.open(file, Logger.getAnonymousLogger())) {
            db.write("a newer schema", c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("PRAGMA user_version = " + (Database.SCHEMA_VERSION + 1));
                }
            });
        }
        assertThrows(IllegalStateException.class, () -> Database.open(file, Logger.getAnonymousLogger()));
        try (var files = Files.list(file.getParent())) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().contains("corrupt")));
        }
    }

    @Test void lockedDatabaseIsNotMovedAside() throws Exception {
        Path file = folder.resolve("data/voicewarden.db");
        Database.open(file, Logger.getAnonymousLogger()).close();
        try (var holder = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = holder.createStatement()) {
            s.execute("PRAGMA locking_mode=EXCLUSIVE");
            s.execute("BEGIN EXCLUSIVE");
            assertThrows(java.sql.SQLException.class, () -> Database.open(file, Logger.getAnonymousLogger()));
            s.execute("ROLLBACK");
        }
        try (var files = Files.list(file.getParent())) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().contains("corrupt")));
        }
    }

    @Test void versionOneDatabaseIsMigratedInPlaceKeepingDataAndRecordingActiveMutes() throws Exception {
        Path file = folder.resolve("data/voicewarden.db");
        Files.createDirectories(file.getParent());
        try (var v1 = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = v1.createStatement()) {
            createVersion1(s);
            s.execute("INSERT INTO mutes VALUES ('00000000-0000-0000-0000-000000000001', 'Alice', 1000, 61000, 'spam', 'Mod', 0)");
            s.execute("INSERT INTO offenses VALUES ('00000000-0000-0000-0000-000000000001', 3, 1000)");
            s.execute("PRAGMA user_version = 1");
        }
        try (Database db = Database.open(file, Logger.getAnonymousLogger())) {
            assertEquals(Database.SCHEMA_VERSION, (int) db.<Integer>read(c -> single(c, "PRAGMA user_version")));
            assertEquals(1, (int) db.<Integer>read(c -> single(c, "SELECT COUNT(*) FROM mutes")));
            assertEquals(3, (int) db.<Integer>read(c -> single(c, "SELECT count FROM offenses")));
            assertEquals("mute|Alice|1000|Mod|spam|61000", db.read(c -> {
                try (Statement s = c.createStatement(); var r = s.executeQuery(
                        "SELECT type, player_name, at, actor, reason, expires_at FROM mute_events")) {
                    assertTrue(r.next());
                    String row = String.join("|", r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getString(5), r.getString(6));
                    assertFalse(r.next());
                    return row;
                }
            }));
        }
        try (Database reopened = Database.open(file, Logger.getAnonymousLogger())) {
            assertEquals(1, (int) reopened.<Integer>read(c -> single(c, "SELECT COUNT(*) FROM mute_events")));
        }
    }

    @Test void versionTwoDatabaseGainsAnEmptyConsentTableAndKeepsItsData() throws Exception {
        Path file = folder.resolve("data/voicewarden.db");
        Files.createDirectories(file.getParent());
        try (var v2 = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = v2.createStatement()) {
            createVersion1(s);
            s.execute("""
                    CREATE TABLE mute_events (id INTEGER PRIMARY KEY AUTOINCREMENT, player_id TEXT NOT NULL,
                        player_name TEXT NOT NULL, type TEXT NOT NULL, at INTEGER NOT NULL, actor TEXT NOT NULL,
                        reason TEXT NOT NULL, expires_at INTEGER, automatic INTEGER NOT NULL)""");
            s.execute("INSERT INTO offenses VALUES ('00000000-0000-0000-0000-000000000001', 2, 1000)");
            s.execute("INSERT INTO mute_events (player_id, player_name, type, at, actor, reason, automatic)"
                    + " VALUES ('00000000-0000-0000-0000-000000000001', 'Alice', 'unmute', 1000, 'Mod', '', 0)");
            s.execute("PRAGMA user_version = 2");
        }
        try (Database db = Database.open(file, Logger.getAnonymousLogger())) {
            assertEquals(Database.SCHEMA_VERSION, (int) db.<Integer>read(c -> single(c, "PRAGMA user_version")));
            assertEquals(0, (int) db.<Integer>read(c -> single(c, "SELECT COUNT(*) FROM consents")));
            assertEquals(2, (int) db.<Integer>read(c -> single(c, "SELECT count FROM offenses")));
            assertEquals(1, (int) db.<Integer>read(c -> single(c, "SELECT COUNT(*) FROM mute_events")));
            db.write("a consent", c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("INSERT INTO consents (player_id, accepted, rules_version, decided_at)"
                            + " VALUES ('00000000-0000-0000-0000-000000000001', 1, 1, 1000)");
                }
            });
        }
        try (Database reopened = Database.open(file, Logger.getAnonymousLogger())) {
            assertEquals(1, (int) reopened.<Integer>read(c -> single(c, "SELECT COUNT(*) FROM consents")));
        }
    }

    @Test void versionThreeConsentsCountAsSeenAtTheUpgrade() throws Exception {
        Path file = folder.resolve("data/voicewarden.db");
        Files.createDirectories(file.getParent());
        try (Database db = Database.open(file, Logger.getAnonymousLogger())) {
            db.write("a version 3 database", c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("ALTER TABLE consents DROP COLUMN last_seen_at");
                    s.execute("INSERT INTO consents VALUES ('00000000-0000-0000-0000-000000000001', 1, 1, 1000)");
                    s.execute("PRAGMA user_version = 3");
                }
            }).join();
        }
        long before = System.currentTimeMillis() / 1000 * 1000;
        try (Database db = Database.open(file, Logger.getAnonymousLogger())) {
            assertEquals(4, (int) db.<Integer>read(c -> single(c, "PRAGMA user_version")));
            long seen = db.read(c -> {
                try (Statement s = c.createStatement(); var r = s.executeQuery("SELECT last_seen_at, decided_at FROM consents")) {
                    assertTrue(r.next());
                    assertEquals(1000, r.getLong(2));
                    return r.getLong(1);
                }
            });
            assertTrue(seen >= before, "seen " + seen + " before " + before);
        }
    }

    /** The schema VoiceWarden 2.0 snapshots shipped as version 1. */
    private static void createVersion1(Statement s) throws java.sql.SQLException {
        s.execute("""
                CREATE TABLE mutes (player_id TEXT PRIMARY KEY, player_name TEXT NOT NULL, created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL, reason TEXT NOT NULL, actor TEXT NOT NULL, automatic INTEGER NOT NULL)""");
        s.execute("""
                CREATE TABLE mute_mirrors (player_id TEXT NOT NULL, position INTEGER NOT NULL,
                    speak_permission TEXT NOT NULL, listen_permission TEXT NOT NULL, server_context TEXT NOT NULL,
                    PRIMARY KEY (player_id, position))""");
        s.execute("""
                CREATE TABLE offenses (player_id TEXT PRIMARY KEY, count INTEGER NOT NULL,
                    last_offense_at INTEGER NOT NULL)""");
        s.execute("""
                CREATE TABLE reports (id TEXT PRIMARY KEY, created_at TEXT NOT NULL, reporter_id TEXT,
                    reporter_name TEXT NOT NULL, target_id TEXT NOT NULL, target_name TEXT NOT NULL,
                    window_seconds INTEGER NOT NULL, reviewed INTEGER NOT NULL, reviewer TEXT, reviewed_at TEXT)""");
        s.execute("""
                CREATE TABLE report_lines (report_id TEXT NOT NULL, position INTEGER NOT NULL,
                    spoken_at TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY (report_id, position))""");
    }

    private static int single(java.sql.Connection c, String sql) throws java.sql.SQLException {
        try (Statement s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        }
    }
}
