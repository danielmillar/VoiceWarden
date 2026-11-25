package dev.voicewarden.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.api.VoiceMuteRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VoiceEventsTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static VoiceFlagEvent flagEvent(boolean async, List<String> words) {
        return new VoiceFlagEvent(
                async, PLAYER, "Alex", "bad words here", words,
                FlagSeverity.MODERATE, ConfirmationState.CONFIRMED, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void flagEventHonoursAsyncFlag(boolean async) {
        assertEquals(async, flagEvent(async, List.of("bad")).isAsynchronous());
    }

    @Test
    void flagEventExposesFields() {
        VoiceFlagEvent event = flagEvent(true, List.of("bad", "words"));

        assertEquals(PLAYER, event.getPlayerId());
        assertEquals("Alex", event.getPlayerName());
        assertEquals("bad words here", event.getTranscript());
        assertEquals(List.of("bad", "words"), event.getMatchedWords());
        assertEquals(FlagSeverity.MODERATE, event.getSeverity());
        assertEquals(ConfirmationState.CONFIRMED, event.getConfirmationState());
        assertTrue(event.isLiveDetection());
    }

    @Test
    void flagEventIsCancellable() {
        VoiceFlagEvent event = flagEvent(false, List.of());
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        event.setCancelled(false);
        assertFalse(event.isCancelled());
    }

    @Test
    void flagEventCopiesMatchedWordsDefensively() {
        List<String> words = new ArrayList<>(List.of("bad"));
        VoiceFlagEvent event = flagEvent(true, words);
        words.add("later");

        assertEquals(List.of("bad"), event.getMatchedWords());
        assertThrows(UnsupportedOperationException.class, () -> event.getMatchedWords().add("x"));
    }

    @Test
    void flagEventRejectsNulls() {
        assertThrows(NullPointerException.class, () -> flagEvent(true, null));
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> flagEvent(true, withNull));
        assertThrows(NullPointerException.class, () -> new VoiceFlagEvent(
                true, null, "Alex", "t", List.of(), FlagSeverity.MILD, ConfirmationState.UNCHECKED, false));
        assertThrows(NullPointerException.class, () -> new VoiceFlagEvent(
                true, PLAYER, "Alex", "t", List.of(), null, ConfirmationState.UNCHECKED, false));
        assertThrows(NullPointerException.class, () -> new VoiceFlagEvent(
                true, PLAYER, "Alex", "t", List.of(), FlagSeverity.MILD, null, false));
    }

    @Test
    void muteEventIsAsyncAndExposesRecord() {
        VoiceMuteRecord record = new VoiceMuteRecord(
                PLAYER, "Alex", "spam", "Console", Instant.EPOCH, null, true, "lobby");
        VoiceMuteEvent event = new VoiceMuteEvent(record, true);

        assertTrue(event.isAsynchronous());
        assertSame(record, event.getRecord());
        assertTrue(event.isAutomatic());
        assertFalse(new VoiceMuteEvent(record, false).isAutomatic());
        assertThrows(NullPointerException.class, () -> new VoiceMuteEvent(null, false));
    }

    @Test
    void unmuteEventIsAsyncAndExposesFields() {
        VoiceUnmuteEvent event = new VoiceUnmuteEvent(PLAYER, "Alex", "Mod", true);

        assertTrue(event.isAsynchronous());
        assertEquals(PLAYER, event.getPlayerId());
        assertEquals("Alex", event.getPlayerName());
        assertEquals("Mod", event.getModerator());
        assertTrue(event.isExpired());
        assertThrows(NullPointerException.class, () -> new VoiceUnmuteEvent(PLAYER, "Alex", null, false));
    }

    @Test
    void strikeEventIsAsyncAndExposesFields() {
        List<String> words = new ArrayList<>(List.of("bad"));
        VoiceStrikeEvent event = new VoiceStrikeEvent(
                PLAYER, "Alex", FlagSeverity.SEVERE, 3, 5, VoiceAction.WARN, "so bad", words);
        words.clear();

        assertTrue(event.isAsynchronous());
        assertEquals(PLAYER, event.getPlayerId());
        assertEquals("Alex", event.getPlayerName());
        assertEquals(FlagSeverity.SEVERE, event.getSeverity());
        assertEquals(3, event.getCurrentStrikes());
        assertEquals(5, event.getThreshold());
        assertEquals(VoiceAction.WARN, event.getAction());
        assertEquals("so bad", event.getTranscript());
        assertEquals(List.of("bad"), event.getMatchedWords());
        assertThrows(UnsupportedOperationException.class, () -> event.getMatchedWords().add("x"));
    }

    @Test
    void everyEventHasItsOwnStaticHandlerList() {
        VoiceMuteRecord record = new VoiceMuteRecord(
                PLAYER, "Alex", "spam", "Console", Instant.EPOCH, null, true, "lobby");

        assertSame(VoiceFlagEvent.getHandlerList(), flagEvent(true, List.of()).getHandlers());
        assertSame(VoiceMuteEvent.getHandlerList(), new VoiceMuteEvent(record, false).getHandlers());
        assertSame(VoiceUnmuteEvent.getHandlerList(), new VoiceUnmuteEvent(PLAYER, "A", "M", false).getHandlers());
        assertSame(VoiceStrikeEvent.getHandlerList(), new VoiceStrikeEvent(
                PLAYER, "A", FlagSeverity.MILD, 0, 1, VoiceAction.REPORT, "t", List.of()).getHandlers());
        assertEquals(4, List.of(
                VoiceFlagEvent.getHandlerList(), VoiceMuteEvent.getHandlerList(),
                VoiceUnmuteEvent.getHandlerList(), VoiceStrikeEvent.getHandlerList())
                .stream().distinct().count());
    }
}
