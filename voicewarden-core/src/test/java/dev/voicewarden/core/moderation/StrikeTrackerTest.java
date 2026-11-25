package dev.voicewarden.core.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrikeTrackerTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final InMemoryStrikePersistence persistence = new InMemoryStrikePersistence();
    private final StrikeTracker tracker = new StrikeTracker(persistence, Duration.ofMinutes(10));

    @Test
    void countsStrikesInsideTheRollingWindowOnly() {
        tracker.recordStrike(PLAYER, "Steve", FlagSeverity.MILD, List.of("a"), "a", NOW.minus(Duration.ofMinutes(11)));
        tracker.recordStrike(PLAYER, "Steve", FlagSeverity.MILD, List.of("a"), "a", NOW.minus(Duration.ofMinutes(9)));
        assertEquals(2, tracker.recordStrike(PLAYER, "Steve", FlagSeverity.MILD, List.of("a"), "a", NOW));
        assertEquals(1, tracker.strikesInWindow(PLAYER, NOW.plus(Duration.ofMinutes(9).plusSeconds(30))));
        assertEquals(0, tracker.strikesInWindow(PLAYER, NOW.plus(Duration.ofMinutes(11))));
    }

    @Test
    void pardonClearsStrikesButNotMutes() {
        tracker.recordStrike(PLAYER, "Steve", FlagSeverity.SEVERE, List.of("a"), "a", NOW);
        persistence.recordMute(PLAYER, NOW);
        tracker.pardon(PLAYER);
        assertEquals(0, tracker.strikesInWindow(PLAYER, NOW));
        assertEquals(1, tracker.mutesSince(PLAYER, NOW.minus(Duration.ofDays(1))));
    }

    @Test
    void firstSightingOfAnUtteranceIsNew() {
        assertEquals(StrikeTracker.AdmissionKind.NEW, tracker.admit(PLAYER, "u1", FlagSeverity.MILD, true, NOW).kind());
    }

    @Test
    void confirmedPunishmentIsNeverRepeatedForTheSameUtterance() {
        tracker.remember(PLAYER, "u1", UtteranceVerdict.confirmed(FlagSeverity.MODERATE, Optional.of(VoiceAction.WARN)), NOW);
        assertEquals(StrikeTracker.AdmissionKind.DUPLICATE, tracker.admit(PLAYER, "u1", FlagSeverity.MODERATE, true, NOW).kind());
        assertEquals(StrikeTracker.AdmissionKind.DUPLICATE, tracker.admit(PLAYER, "u1", FlagSeverity.MILD, true, NOW).kind());
        assertEquals(StrikeTracker.AdmissionKind.DUPLICATE, tracker.admit(PLAYER, "u1", FlagSeverity.SEVERE, false, NOW).kind());
        assertEquals(StrikeTracker.AdmissionKind.NEW, tracker.admit(PLAYER, "u2", FlagSeverity.MODERATE, true, NOW).kind());
    }

    @Test
    void higherSeverityLaterInTheSentenceEscalatesUnlessAlreadyMuted() {
        tracker.remember(PLAYER, "u1", UtteranceVerdict.confirmed(FlagSeverity.MILD, Optional.of(VoiceAction.REPORT)), NOW);
        StrikeTracker.Admission admission = tracker.admit(PLAYER, "u1", FlagSeverity.SEVERE, true, NOW);
        assertEquals(StrikeTracker.AdmissionKind.ESCALATION, admission.kind());
        assertEquals(Optional.of(VoiceAction.REPORT), admission.prior().flatMap(UtteranceVerdict::action));

        tracker.remember(PLAYER, "u2", UtteranceVerdict.confirmed(FlagSeverity.MILD, Optional.of(VoiceAction.MUTE)), NOW);
        assertEquals(StrikeTracker.AdmissionKind.DUPLICATE, tracker.admit(PLAYER, "u2", FlagSeverity.SEVERE, true, NOW).kind());
    }

    @Test
    void unconfirmedLiveHitLeavesTheFinalPassAsBackstop() {
        tracker.remember(PLAYER, "u1", UtteranceVerdict.unconfirmed(FlagSeverity.SEVERE), NOW);
        assertEquals(StrikeTracker.AdmissionKind.NEW, tracker.admit(PLAYER, "u1", FlagSeverity.SEVERE, true, NOW).kind());
        assertEquals(StrikeTracker.AdmissionKind.DUPLICATE, tracker.admit(PLAYER, "u1", FlagSeverity.SEVERE, false, NOW).kind());
        assertEquals(StrikeTracker.AdmissionKind.DUPLICATE, tracker.admit(PLAYER, "u1", FlagSeverity.MILD, false, NOW).kind());
        tracker.remember(PLAYER, "u2", UtteranceVerdict.unconfirmed(FlagSeverity.MILD), NOW);
        assertEquals(StrikeTracker.AdmissionKind.NEW, tracker.admit(PLAYER, "u2", FlagSeverity.SEVERE, false, NOW).kind());
    }

    @Test
    void verdictsAreKeyedByPlayerAndUtterance() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        tracker.remember(PLAYER, "u1", UtteranceVerdict.confirmed(FlagSeverity.SEVERE, Optional.of(VoiceAction.MUTE)), NOW);
        assertEquals(StrikeTracker.AdmissionKind.NEW, tracker.admit(other, "u1", FlagSeverity.SEVERE, true, NOW).kind());
    }

    @Test
    void verdictCacheIsBoundedBySizeAndAge() {
        StrikeTracker small = new StrikeTracker(persistence, Duration.ofMinutes(10), 3, Duration.ofMinutes(1));
        for (int i = 0; i < 5; i++) {
            small.remember(PLAYER, "u" + i, UtteranceVerdict.confirmed(FlagSeverity.MILD, Optional.empty()), NOW);
        }
        assertEquals(3, small.trackedUtterances());
        assertTrue(small.verdict(PLAYER, "u0", NOW).isEmpty());
        assertTrue(small.verdict(PLAYER, "u4", NOW).isPresent());
        assertTrue(small.verdict(PLAYER, "u4", NOW.plus(Duration.ofMinutes(2))).isEmpty());
    }
}
