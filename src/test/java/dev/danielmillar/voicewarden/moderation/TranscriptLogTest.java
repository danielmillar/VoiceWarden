package dev.danielmillar.voicewarden.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptLogTest {
    @TempDir Path folder;
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);
    private final Instant now = LocalDate.of(2026, 10, 31).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();

    @Test void filesOlderThanTheRetentionAreDeletedByTheirDate() throws Exception {
        TranscriptLog log = log();
        Path expired = Files.writeString(folder.resolve("transcripts-2026-10-01.log"), "x\n");
        Path kept = Files.writeString(folder.resolve("transcripts-2026-10-02.log"), "x\n");
        Path other = Files.writeString(folder.resolve("latest.log"), "x\n");
        assertEquals(0, log.prune(now, 0));
        assertEquals(1, log.prune(now, 29));
        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(kept));
        assertTrue(Files.exists(other));
    }

    @Test void forgetRemovesThePlayersWrittenAndPendingLinesOnly() throws Exception {
        TranscriptLog log = log();
        log.append(now, alice, "Alice", "first");
        log.append(now, bob, "Bob", "hello");
        assertEquals(1, log.lines(bob).size()); // writes the pending lines
        log.append(now.plusSeconds(1), alice, "Alice", "not written yet");
        assertEquals(2, log.forget(alice));
        assertEquals(List.of(), log.lines(alice));
        List<String> bobs = log.lines(bob);
        assertEquals(1, bobs.size());
        assertTrue(bobs.getFirst().endsWith("Bob (" + bob + "): hello"));
        log.append(now, alice, "Alice", "only line");
        log.lines(alice);
        assertEquals(1, log.forget(alice));
        log.forget(bob);
        try (var files = Files.list(folder)) {
            assertEquals(0, files.count()); // a file left empty is deleted
        }
    }

    private TranscriptLog log() {
        return new TranscriptLog(folder, Runnable::run, Logger.getAnonymousLogger());
    }
}
