package dev.danielmillar.nevusvoice.moderation;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FlagTrackerTest {
    private final FlagTracker tracker = new FlagTracker();
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);

    @Test void weightsAccumulateAndPlayersAreIndependent() {
        assertEquals(2, tracker.add(alice, 2, 1_000, 100));
        assertEquals(5, tracker.add(alice, 3, 1_050, 100));
        assertEquals(1, tracker.add(bob, 1, 1_050, 100));
    }

    @Test void cutoffIsInclusiveAndOlderHitsExpire() {
        tracker.add(alice, 2, 1_000, 100);
        assertEquals(3, tracker.add(alice, 1, 1_100, 100));
        assertEquals(2, tracker.add(alice, 1, 1_101, 100));
        assertEquals(1, tracker.add(alice, 1, 2_000, 100));
    }

    @Test void pruneAndResetRemoveExpiredHistoryWithoutAffectingOtherPlayers() {
        tracker.add(alice, 10, 1_000, 100);
        tracker.add(bob, 2, 1_150, 100);
        tracker.prune(1_200, 100);
        assertEquals(1, tracker.add(alice, 1, 1_200, 100));
        assertEquals(3, tracker.add(bob, 1, 1_200, 100));
        tracker.reset(bob);
        assertEquals(1, tracker.add(bob, 1, 1_201, 100));
    }
}
