package dev.voicewarden.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EnumsTest {

    @Test
    void severityIsOrderedLowestToHighest() {
        assertArrayEquals(
                new FlagSeverity[] {FlagSeverity.MILD, FlagSeverity.MODERATE, FlagSeverity.SEVERE},
                FlagSeverity.values());
    }

    @Test
    void highestPicksTheMoreSevereEitherWay() {
        for (FlagSeverity a : FlagSeverity.values()) {
            for (FlagSeverity b : FlagSeverity.values()) {
                FlagSeverity expected = a.ordinal() >= b.ordinal() ? a : b;
                assertEquals(expected, FlagSeverity.highest(a, b));
                assertEquals(expected, FlagSeverity.highest(b, a));
            }
        }
    }

    @Test
    void highestRejectsNull() {
        assertThrows(NullPointerException.class, () -> FlagSeverity.highest(null, FlagSeverity.MILD));
        assertThrows(NullPointerException.class, () -> FlagSeverity.highest(FlagSeverity.MILD, null));
    }

    @Test
    void isAtLeastComparesBySeverity() {
        assertTrue(FlagSeverity.SEVERE.isAtLeast(FlagSeverity.MODERATE));
        assertTrue(FlagSeverity.MODERATE.isAtLeast(FlagSeverity.MODERATE));
        assertFalse(FlagSeverity.MILD.isAtLeast(FlagSeverity.MODERATE));
    }

    @Test
    void confirmationStatesAreStable() {
        assertArrayEquals(
                new ConfirmationState[] {
                    ConfirmationState.CONFIRMED, ConfirmationState.UNCONFIRMED, ConfirmationState.UNCHECKED
                },
                ConfirmationState.values());
    }

    @Test
    void voiceActionsAreStable() {
        assertArrayEquals(
                new VoiceAction[] {VoiceAction.REPORT, VoiceAction.WARN, VoiceAction.MUTE}, VoiceAction.values());
    }
}
