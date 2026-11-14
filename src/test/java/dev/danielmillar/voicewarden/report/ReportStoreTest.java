package dev.danielmillar.voicewarden.report;

import dev.danielmillar.voicewarden.TestFixtures;
import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ReportStoreTest {
    @TempDir Path folder;
    private Database database;

    @BeforeEach void open() {
        database = Fakes.database(folder);
    }

    @AfterEach void close() {
        database.close();
    }

    @Test void reportsAndReviewsSurviveARestart() {
        var store = store();
        store.add(TestFixtures.report());
        var reloaded = restartedStore();
        var stored = reloaded.open().getFirst();
        assertEquals(TestFixtures.report().lines(), stored.lines());
        assertEquals(TestFixtures.report().createdAt(), stored.createdAt());
        assertTrue(reloaded.markReviewed(TestFixtures.report().id(), "Staff"));
        var reviewed = restartedStore();
        assertTrue(reviewed.open().isEmpty());
        assertEquals("Staff", reviewed.find(TestFixtures.report().id().toString()).orElseThrow().reviewer());
    }

    private ReportStore store() {
        var store = new ReportStore(database, Logger.getAnonymousLogger());
        store.load();
        return store;
    }

    private ReportStore restartedStore() {
        database.close();
        database = Fakes.database(folder);
        return store();
    }
}
