package dev.voicewarden.core.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.FlagSeverity;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranscriptBufferTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID JORDAN = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void conversationMergesPeersChronologicallyAndLabelsEachSpeaker() {
        TranscriptBuffer buffer = TranscriptBuffer.withDefaults();
        buffer.append(ALEX, TranscriptEntry.clean(NOW.minusSeconds(50), "alex first"));
        buffer.append(JORDAN, TranscriptEntry.clean(NOW.minusSeconds(40), "jordan first"));
        buffer.append(PLAYER, TranscriptEntry.clean(NOW.minusSeconds(35), "subject line"));
        buffer.append(ALEX, TranscriptEntry.flagged(NOW.minusSeconds(30), "alex flagged", FlagSeverity.MILD));
        buffer.append(JORDAN, TranscriptEntry.clean(NOW.minusSeconds(10), "jordan second"));

        List<ConversationLine> lines = buffer.conversation(Map.of(ALEX, "Alex", JORDAN, "Jordan"), NOW.minusSeconds(60), NOW, 10);

        assertEquals(List.of("alex first", "jordan first", "alex flagged", "jordan second"),
                lines.stream().map(ConversationLine::text).toList());
        assertEquals(List.of("Alex", "Jordan", "Alex", "Jordan"), lines.stream().map(ConversationLine::playerName).toList());
        assertEquals(List.of(ALEX, JORDAN, ALEX, JORDAN), lines.stream().map(ConversationLine::playerId).toList());
        assertTrue(lines.get(2).flagged());
        assertEquals(FlagSeverity.MILD, lines.get(2).severity());
        assertFalse(lines.get(0).flagged());
    }

    @Test
    void conversationKeepsTheMostRecentLinesInsideTheWindow() {
        TranscriptBuffer buffer = TranscriptBuffer.withDefaults();
        buffer.append(ALEX, TranscriptEntry.clean(NOW.minusSeconds(120), "too old"));
        for (int i = 0; i < 6; i++) {
            buffer.append(i % 2 == 0 ? ALEX : JORDAN, TranscriptEntry.clean(NOW.minusSeconds(50 - i * 5L), "line " + i));
        }
        buffer.append(JORDAN, TranscriptEntry.clean(NOW.plusSeconds(5), "after the flag"));
        Map<UUID, String> peers = Map.of(ALEX, "Alex", JORDAN, "Jordan");

        assertEquals(List.of("line 2", "line 3", "line 4", "line 5"),
                buffer.conversation(peers, NOW.minusSeconds(60), NOW, 4).stream().map(ConversationLine::text).toList());
        assertEquals(6, buffer.conversation(peers, NOW.minusSeconds(60), NOW, 50).size());
        assertEquals(List.of(), buffer.conversation(peers, NOW.minusSeconds(60), NOW, 0));
        assertEquals(List.of(), buffer.conversation(Map.of(), NOW.minusSeconds(60), NOW, 4));
        assertEquals(List.of(), buffer.conversation(peers, NOW, NOW.minusSeconds(60), 4));
        assertEquals(List.of(), buffer.conversation(Map.of(PLAYER, "Nobody"), NOW.minusSeconds(60), NOW, 4));
    }

    @Test
    void conversationOrdersSimultaneousLinesDeterministically() {
        TranscriptBuffer buffer = TranscriptBuffer.withDefaults();
        buffer.append(JORDAN, TranscriptEntry.clean(NOW.minusSeconds(5), "from jordan"));
        buffer.append(ALEX, TranscriptEntry.clean(NOW.minusSeconds(5), "from alex"));
        Map<UUID, String> peers = new LinkedHashMap<>();
        peers.put(JORDAN, "Jordan");
        peers.put(ALEX, "Alex");

        assertEquals(List.of("from alex", "from jordan"),
                buffer.conversation(peers, NOW.minusSeconds(60), NOW, 4).stream().map(ConversationLine::text).toList());
    }

    @Test
    void conversationNeverReachesPastRetention() {
        TranscriptBuffer buffer = new TranscriptBuffer(Duration.ofSeconds(30), 100);
        buffer.append(ALEX, TranscriptEntry.clean(NOW.minusSeconds(45), "expired"));
        buffer.append(ALEX, TranscriptEntry.clean(NOW.minusSeconds(10), "kept"));

        assertEquals(List.of("kept"), buffer.conversation(Map.of(ALEX, "Alex"), NOW.minusSeconds(600), NOW, 4)
                .stream().map(ConversationLine::text).toList());
    }

    @Test
    void defaultsMatchThePlan() {
        TranscriptBuffer buffer = TranscriptBuffer.withDefaults();
        assertEquals(Duration.ofHours(12), buffer.retention());
        assertEquals(500, buffer.maxLinesPerPlayer());
    }

    @Test
    void returnsChronologicalEntriesInsideTheWindow() {
        TranscriptBuffer buffer = TranscriptBuffer.withDefaults();
        buffer.append(PLAYER, TranscriptEntry.clean(NOW.minus(Duration.ofMinutes(20)), "old"));
        buffer.append(PLAYER, TranscriptEntry.clean(NOW.minus(Duration.ofMinutes(4)), "hello"));
        buffer.append(PLAYER, TranscriptEntry.flagged(NOW.minus(Duration.ofMinutes(2)), "bad words", FlagSeverity.MODERATE));
        buffer.append(PLAYER, TranscriptEntry.clean(NOW, "bye"));

        List<TranscriptEntry> recent = buffer.getRecent(PLAYER, 5, NOW);
        assertEquals(List.of("hello", "bad words", "bye"), recent.stream().map(TranscriptEntry::text).toList());
        assertEquals(FlagSeverity.MODERATE, recent.get(1).severity());
        assertEquals(4, buffer.getRecent(PLAYER, 30, NOW).size());
    }

    @Test
    void clampsTheRequestedWindow() {
        TranscriptBuffer buffer = new TranscriptBuffer(Duration.ofMinutes(10), 100);
        buffer.append(PLAYER, TranscriptEntry.clean(NOW.minusSeconds(30), "recent"));
        buffer.append(PLAYER, TranscriptEntry.clean(NOW.minus(Duration.ofMinutes(5)), "older"));
        assertEquals(1, buffer.getRecent(PLAYER, 0, NOW).size());
        assertEquals(1, buffer.getRecent(PLAYER, -50, NOW).size());
        assertEquals(2, buffer.getRecent(PLAYER, Integer.MAX_VALUE, NOW).size());
        assertEquals(10, buffer.maxWindowMinutes());
    }

    @Test
    void dropsEntriesOlderThanRetention() {
        TranscriptBuffer buffer = new TranscriptBuffer(Duration.ofMinutes(10), 100);
        buffer.append(PLAYER, TranscriptEntry.clean(NOW.minus(Duration.ofMinutes(11)), "expired"));
        buffer.append(PLAYER, TranscriptEntry.clean(NOW.minus(Duration.ofMinutes(9)), "kept"));
        assertEquals(List.of("kept"), buffer.getRecent(PLAYER, 60, NOW).stream().map(TranscriptEntry::text).toList());
        buffer.prune(NOW.plus(Duration.ofMinutes(2)));
        assertEquals(0, buffer.size(PLAYER));
    }

    @Test
    void evictsOldestLinesPastTheCap() {
        TranscriptBuffer buffer = new TranscriptBuffer(Duration.ofHours(12), 3);
        for (int i = 0; i < 5; i++) {
            buffer.append(PLAYER, TranscriptEntry.clean(NOW.minusSeconds(10 - i), "line " + i));
        }
        assertEquals(3, buffer.size(PLAYER));
        assertEquals(List.of("line 2", "line 3", "line 4"),
                buffer.getRecent(PLAYER, 5, NOW).stream().map(TranscriptEntry::text).toList());
    }

    @Test
    void playersAreIsolatedAndClearable() {
        TranscriptBuffer buffer = TranscriptBuffer.withDefaults();
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        buffer.append(PLAYER, TranscriptEntry.clean(NOW, "mine"));
        buffer.append(other, TranscriptEntry.clean(NOW, "theirs"));
        buffer.clear(PLAYER);
        assertEquals(List.of(), buffer.getRecent(PLAYER, 5, NOW));
        assertEquals(1, buffer.getRecent(other, 5, NOW).size());
    }

    @Test
    void flaggedAndSeverityMustAgree() {
        assertThrows(IllegalArgumentException.class, () -> new TranscriptEntry(NOW, "x", true, null));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptEntry(NOW, "x", false, FlagSeverity.MILD));
    }
}
