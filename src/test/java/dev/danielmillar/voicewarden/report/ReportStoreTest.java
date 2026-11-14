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

    @Test void reportsOlderThanTheRetentionAreDeletedReviewedOrNot() {
        var store = store();
        var old = report(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), TestFixtures.TIME.minus(java.time.Duration.ofDays(91)));
        var reviewed = report(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), TestFixtures.TIME.minus(java.time.Duration.ofDays(95)));
        var recent = report(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), TestFixtures.TIME.minus(java.time.Duration.ofDays(89)));
        store.add(old);
        store.add(reviewed);
        store.add(recent);
        store.markReviewed(reviewed.id(), "Staff");
        assertEquals(0, store.prune(TestFixtures.TIME, 0));
        assertEquals(2, store.prune(TestFixtures.TIME, 90));
        var reloaded = restartedStore();
        assertEquals(java.util.List.of(recent.id()), reloaded.open().stream().map(ReportStore.StoredReport::id).toList());
        assertTrue(reloaded.find(reviewed.id().toString()).isEmpty());
    }

    @Test void forgetDeletesReportsThePlayerFiledOrWasReportedIn() {
        var store = store();
        var alice = java.util.UUID.randomUUID();
        var bob = java.util.UUID.randomUUID();
        var carol = java.util.UUID.randomUUID();
        store.add(report(carol, alice, TestFixtures.TIME));
        store.add(report(alice, carol, TestFixtures.TIME));
        var kept = report(carol, bob, TestFixtures.TIME);
        store.add(kept);
        assertEquals(2, store.forget(alice));
        var reloaded = restartedStore();
        assertTrue(reloaded.involving(alice).isEmpty());
        assertEquals(java.util.List.of(kept.id()), reloaded.open().stream().map(ReportStore.StoredReport::id).toList());
    }

    private static VoiceReport report(java.util.UUID reporter, java.util.UUID target, java.time.Instant at) {
        var r = TestFixtures.report();
        return new VoiceReport(java.util.UUID.randomUUID(), at, reporter, "Reporter", target, "Target", r.window(), r.lines(),
                r.audio(), r.sampleRate());
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
