package dev.danielmillar.voicesentinel.moderation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptBufferTest {
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);
    private final Instant now = Instant.parse("2026-10-03T12:00:00Z");

    @Test void maxLinesEvictsOldestTextAndAudioPerPlayer() {
        TranscriptBuffer buffer = buffer(2, 1_000);
        buffer.add(alice, "Alice", now, "one", new short[10]);
        TranscriptBuffer.Entry first = buffer.since(alice, Instant.EPOCH).getFirst();
        buffer.add(alice, "Alice", now.plusSeconds(1), "two", new short[10]);
        buffer.add(alice, "Alice", now.plusSeconds(2), "three", new short[10]);
        assertEquals(List.of("two", "three"), buffer.recentLines(alice, 10).stream().map(TranscriptLine::text).toList());
        assertNull(first.audio());
        assertEquals(40, buffer.audioBytes());
    }

    @Test void retentionPrunesStrictlyBeforeCutoffAndRemovesEmptyPlayers() {
        TranscriptBuffer buffer = buffer(10, 1_000);
        buffer.add(alice, "Alice", now.minusSeconds(61), "old", new short[10]);
        buffer.add(alice, "Alice", now.minusSeconds(60), "boundary", new short[10]);
        buffer.add(bob, "Bob", now.minusSeconds(100), "gone", new short[10]);
        buffer.prune(now);
        assertEquals(List.of("boundary"), buffer.recentLines(alice, 10).stream().map(TranscriptLine::text).toList());
        assertTrue(buffer.lastKnownName(bob).isEmpty());
        assertFalse(buffer.knownPlayers().containsKey(bob));
        assertEquals(20, buffer.audioBytes());
    }

    @Test void globalAudioBudgetEvictsOldestAudioAcrossPlayersWhileKeepingText() {
        TranscriptBuffer buffer = buffer(10, 40);
        buffer.add(alice, "Alice", now, "a1", new short[10]);
        buffer.add(bob, "Bob", now.plusSeconds(1), "b1", new short[10]);
        buffer.add(alice, "Alice", now.plusSeconds(2), "a2", new short[10]);
        var entries = buffer.since(alice, Instant.EPOCH);
        assertNull(entries.getFirst().audio());
        assertNotNull(entries.getLast().audio());
        assertNotNull(buffer.since(bob, Instant.EPOCH).getFirst().audio());
        assertEquals(40, buffer.audioBytes());
        buffer.add(bob, "Bob", now.plusSeconds(3), "b2", new short[10]);
        assertNull(buffer.since(bob, Instant.EPOCH).getFirst().audio());
        assertEquals(40, buffer.audioBytes());
        assertEquals(2, buffer.recentLines(alice, 10).size());
    }

    @Test void reducingBudgetEvictsImmediatelyAndZeroBudgetKeepsOnlyText() {
        TranscriptBuffer buffer = buffer(10, 100);
        buffer.add(alice, "Alice", now, "a", new short[10]);
        buffer.add(bob, "Bob", now.plusSeconds(1), "b", new short[10]);
        buffer.setLimits(new TranscriptBuffer.Limits(Duration.ofMinutes(1), 10, 20));
        assertNull(buffer.since(alice, Instant.EPOCH).getFirst().audio());
        assertEquals(20, buffer.audioBytes());
        buffer.setLimits(new TranscriptBuffer.Limits(Duration.ofMinutes(1), 10, 0));
        buffer.add(alice, "Alice", now.plusSeconds(2), "new", new short[10]);
        assertEquals(0, buffer.audioBytes());
        assertTrue(buffer.since(alice, Instant.EPOCH).stream().allMatch(e -> e.audio() == null));
    }

    @Test void sinceIsInclusiveAndRecentLinesReturnsLatestSubsetOldestFirst() {
        TranscriptBuffer buffer = buffer(10, 0);
        for (int i = 0; i < 5; i++) buffer.add(alice, "Alice", now.plusSeconds(i), "line" + i, null);
        assertEquals(List.of("line2", "line3", "line4"), buffer.since(alice, now.plusSeconds(2)).stream().map(TranscriptBuffer.Entry::text).toList());
        assertEquals(List.of("line3", "line4"), buffer.recentLines(alice, 2).stream().map(TranscriptLine::text).toList());
        assertTrue(buffer.recentLines(alice, 0).isEmpty());
        assertTrue(buffer.recentLines(bob, 5).isEmpty());
        assertTrue(buffer.since(bob, now).isEmpty());
        buffer.add(alice, "Renamed", now.plusSeconds(5), "last", null);
        assertEquals("Renamed", buffer.lastKnownName(alice).orElseThrow());
        assertEquals(6, buffer.recentLines(alice, 10).size());
    }

    @Test void oversizedAudioIsEvictedInsteadOfExceedingBudget() {
        TranscriptBuffer buffer = buffer(10, 10);
        buffer.add(alice, "Alice", now, "text", new short[100]);
        assertEquals(0, buffer.audioBytes());
        assertNull(buffer.since(alice, Instant.EPOCH).getFirst().audio());
        assertEquals("text", buffer.recentLines(alice, 1).getFirst().text());
    }

    private TranscriptBuffer buffer(int maxLines, long budget) {
        return new TranscriptBuffer(new TranscriptBuffer.Limits(Duration.ofMinutes(1), maxLines, budget));
    }
}
