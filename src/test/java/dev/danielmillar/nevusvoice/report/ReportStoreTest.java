package dev.danielmillar.nevusvoice.report;

import dev.danielmillar.nevusvoice.TestFixtures;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ReportStoreTest {
    @TempDir Path folder;

    @ParameterizedTest
    @ValueSource(strings = {"[unreadable original state", "null", "[null]", "[{}]"})
    void invalidStateIsPreservedBeforeFreshSave(String original) throws Exception {
        Path file = folder.resolve("data/reports.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, original);
        var store = new ReportStore(folder, Runnable::run, Logger.getAnonymousLogger());
        store.load();
        assertTrue(store.open().isEmpty());
        store.add(TestFixtures.report());
        try (var files = Files.list(file.getParent())) {
            var preserved = files.filter(path -> path.getFileName().toString().startsWith("reports.json.corrupt-")).toList();
            assertEquals(1, preserved.size());
            assertEquals(original, Files.readString(preserved.getFirst()));
        }
        var reloaded = new ReportStore(folder, Runnable::run, Logger.getAnonymousLogger());
        reloaded.load();
        assertEquals(TestFixtures.report().id(), reloaded.open().getFirst().id());
        assertTrue(reloaded.markReviewed(TestFixtures.report().id(), "Staff"));
        var reviewed = new ReportStore(folder, Runnable::run, Logger.getAnonymousLogger());
        reviewed.load();
        assertTrue(reviewed.open().isEmpty());
        assertEquals("Staff", reviewed.find(TestFixtures.report().id().toString()).orElseThrow().reviewer());
    }
}
