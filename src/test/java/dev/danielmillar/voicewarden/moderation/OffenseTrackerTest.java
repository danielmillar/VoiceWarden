package dev.danielmillar.voicewarden.moderation;

import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OffenseTrackerTest {
    @TempDir Path folder;
    private final UUID player = UUID.randomUUID();
    private Database database;

    @BeforeEach void open() {
        database = Fakes.database(folder);
    }

    @AfterEach void close() {
        database.close();
    }

    @Test void ladderSurvivesARestart() {
        var tracker = tracker();
        assertEquals(1, tracker.recordNext(player, 0));
        assertEquals(2, tracker.recordNext(player, 0));
        restart();
        var reloaded = tracker();
        assertEquals(2, reloaded.lookup(player, 0).join().count());
        assertEquals(3, reloaded.recordNext(player, 0));
    }

    @Test void resetIsStoredAndStartsTheLadderAgain() {
        var tracker = tracker();
        tracker.recordNext(player, 0);
        tracker.recordNext(player, 0);
        tracker.reset(player).join();
        assertEquals(0, storedCount());
        restart();
        assertEquals(1, tracker().recordNext(player, 0));
    }

    @Test void oldOffensesDecayAndDryRunDoesNotWrite() throws Exception {
        database.write("test offences", c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO offenses VALUES (?, 4, 1)")) {
                s.setString(1, player.toString());
                s.executeUpdate();
            }
        }).join();
        var tracker = tracker();
        assertEquals(1, tracker.peekNext(player, 1));
        assertEquals(4, storedCount());
        assertEquals(1, tracker.recordNext(player, 1));
        assertEquals(1, storedCount());
    }

    private OffenseTracker tracker() {
        var tracker = new OffenseTracker(database, Logger.getAnonymousLogger());
        tracker.load();
        return tracker;
    }

    private void restart() {
        database.close();
        database = Fakes.database(folder);
    }

    private int storedCount() {
        try {
            return database.read(c -> {
                try (PreparedStatement s = c.prepareStatement("SELECT count FROM offenses WHERE player_id = ?")) {
                    s.setString(1, player.toString());
                    try (ResultSet r = s.executeQuery()) {
                        return r.next() ? r.getInt(1) : 0;
                    }
                }
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
