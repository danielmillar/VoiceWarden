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
}
