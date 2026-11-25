package dev.voicewarden.core.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MuteLadderTest {

    private static final TreeMap<Integer, Duration> PLAN_TIERS = new TreeMap<>(Map.of(
            1, Duration.ofMinutes(5),
            2, Duration.ofMinutes(15),
            3, Duration.ofMinutes(30),
            4, Duration.ofHours(1),
            5, Duration.ofHours(6)));

    private static MuteLadder ladder(boolean enabled, TreeMap<Integer, Duration> tiers, Duration max) {
        return new MuteLadder(new MuteLadderSettings(enabled, tiers, max, Duration.ofDays(30), Duration.ofMinutes(60)));
    }

    @Test
    void walksThePlanTiersByPriorMuteCount() {
        MuteLadder ladder = ladder(true, PLAN_TIERS, Duration.ofHours(24));
        assertEquals(Duration.ofMinutes(5), ladder.durationFor(0));
        assertEquals(Duration.ofMinutes(15), ladder.durationFor(1));
        assertEquals(Duration.ofMinutes(30), ladder.durationFor(2));
        assertEquals(Duration.ofHours(1), ladder.durationFor(3));
        assertEquals(Duration.ofHours(6), ladder.durationFor(4));
    }

    @Test
    void holdsAtTheLastTierPastTheEnd() {
        MuteLadder ladder = ladder(true, PLAN_TIERS, Duration.ofHours(24));
        assertEquals(Duration.ofHours(6), ladder.durationFor(5));
        assertEquals(Duration.ofHours(6), ladder.durationFor(500));
        assertEquals(Duration.ofMinutes(5), ladder.durationFor(-3));
    }

    @Test
    void capsAtMaxTierDuration() {
        TreeMap<Integer, Duration> tiers = new TreeMap<>(Map.of(1, Duration.ofHours(1), 2, Duration.ofDays(3)));
        MuteLadder ladder = ladder(true, tiers, Duration.ofHours(24));
        assertEquals(Duration.ofHours(1), ladder.durationFor(0));
        assertEquals(Duration.ofHours(24), ladder.durationFor(1));
    }

    @Test
    void gapsUseTheNearestLowerTier() {
        TreeMap<Integer, Duration> tiers = new TreeMap<>(Map.of(2, Duration.ofMinutes(10), 4, Duration.ofMinutes(40)));
        MuteLadder ladder = ladder(true, tiers, Duration.ofHours(24));
        assertEquals(Duration.ofMinutes(10), ladder.durationFor(0));
        assertEquals(Duration.ofMinutes(10), ladder.durationFor(2));
        assertEquals(Duration.ofMinutes(40), ladder.durationFor(3));
    }

    @Test
    void disabledOrEmptyUsesFallbackMinutes() {
        assertEquals(Duration.ofMinutes(60), ladder(false, PLAN_TIERS, Duration.ofHours(24)).durationFor(3));
        assertEquals(Duration.ofMinutes(60), ladder(true, new TreeMap<>(), Duration.ofHours(24)).durationFor(0));
    }

    @Test
    void resetWindowStartsResetAfterDaysAgo() {
        Instant now = Instant.parse("2026-11-24T12:00:00Z");
        assertEquals(Instant.parse("2026-10-25T12:00:00Z"), ladder(true, PLAN_TIERS, Duration.ofHours(24)).ladderWindowStart(now));
    }
}
