package dev.voicewarden.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoiceMuteRecordTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private static VoiceMuteRecord record(Instant expiresAt, boolean active) {
        return new VoiceMuteRecord(PLAYER, "Steve", "slur", "Console", CREATED, expiresAt, active, "survival");
    }

    @Test
    void permanentMuteNeverExpires() {
        VoiceMuteRecord permanent = record(null, true);
        Instant farFuture = CREATED.plus(Duration.ofDays(36500));

        assertTrue(permanent.isPermanent());
        assertFalse(permanent.isExpired(farFuture));
        assertTrue(permanent.isEffectivelyActive(farFuture));
        assertEquals(Optional.empty(), permanent.remainingDuration(farFuture));
    }

    @Test
    void temporaryMuteReportsRemainingUntilExpiry() {
        VoiceMuteRecord temporary = record(CREATED.plus(Duration.ofMinutes(10)), true);
        Instant now = CREATED.plus(Duration.ofMinutes(4));

        assertFalse(temporary.isPermanent());
        assertFalse(temporary.isExpired(now));
        assertTrue(temporary.isEffectivelyActive(now));
        assertEquals(Optional.of(Duration.ofMinutes(6)), temporary.remainingDuration(now));
    }

    @Test
    void temporaryMuteExpiresExactlyAtExpiresAt() {
        Instant expiresAt = CREATED.plus(Duration.ofMinutes(10));
        VoiceMuteRecord temporary = record(expiresAt, true);

        assertTrue(temporary.isExpired(expiresAt));
        assertFalse(temporary.isEffectivelyActive(expiresAt));
        assertEquals(Optional.empty(), temporary.remainingDuration(expiresAt));
        assertFalse(temporary.isExpired(expiresAt.minusMillis(1)));
    }

    @Test
    void inactiveMuteIsNotEffectivelyActive() {
        VoiceMuteRecord lifted = record(CREATED.plus(Duration.ofHours(1)), false);
        Instant now = CREATED.plus(Duration.ofMinutes(1));

        assertFalse(lifted.isExpired(now));
        assertFalse(lifted.isEffectivelyActive(now));
        assertEquals(Optional.empty(), lifted.remainingDuration(now));
        assertFalse(record(null, false).isEffectivelyActive(now));
    }

    @Test
    void withActiveCopiesEveryOtherField() {
        VoiceMuteRecord original = record(CREATED.plusSeconds(60), true);
        VoiceMuteRecord lifted = original.withActive(false);

        assertFalse(lifted.active());
        assertEquals(original, lifted.withActive(true));
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThrows(NullPointerException.class,
                () -> new VoiceMuteRecord(null, "Steve", "r", "m", CREATED, null, true, "s"));
        assertThrows(NullPointerException.class,
                () -> new VoiceMuteRecord(PLAYER, null, "r", "m", CREATED, null, true, "s"));
        assertThrows(NullPointerException.class,
                () -> new VoiceMuteRecord(PLAYER, "Steve", null, "m", CREATED, null, true, "s"));
        assertThrows(NullPointerException.class,
                () -> new VoiceMuteRecord(PLAYER, "Steve", "r", null, CREATED, null, true, "s"));
        assertThrows(NullPointerException.class,
                () -> new VoiceMuteRecord(PLAYER, "Steve", "r", "m", null, null, true, "s"));
        assertThrows(NullPointerException.class,
                () -> new VoiceMuteRecord(PLAYER, "Steve", "r", "m", CREATED, null, true, null));
    }

    @Test
    void expiryBeforeCreationIsTreatedAsExpired() {
        VoiceMuteRecord skewed = record(CREATED.minusSeconds(1), true);
        assertTrue(skewed.isExpired(CREATED));
        assertFalse(skewed.isEffectivelyActive(CREATED));
    }

    @Test
    void helpersRejectNullNow() {
        VoiceMuteRecord permanent = record(null, true);
        assertThrows(NullPointerException.class, () -> permanent.isExpired(null));
        assertThrows(NullPointerException.class, () -> permanent.isEffectivelyActive(null));
        assertThrows(NullPointerException.class, () -> permanent.remainingDuration(null));
    }
}
