package dev.voicewarden.core.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DurationParserTest {

    @Test
    void parsesSingleAndCompoundUnits() {
        assertEquals(Optional.of(Duration.ofSeconds(30)), DurationParser.parse("30s"));
        assertEquals(Optional.of(Duration.ofMinutes(5)), DurationParser.parse("5m"));
        assertEquals(Optional.of(Duration.ofHours(1)), DurationParser.parse("1h"));
        assertEquals(Optional.of(Duration.ofDays(2)), DurationParser.parse("2d"));
        assertEquals(Optional.of(Duration.ofDays(14)), DurationParser.parse("2w"));
        assertEquals(Optional.of(Duration.ofMinutes(90)), DurationParser.parse("1h30m"));
        assertEquals(Optional.of(Duration.ofMinutes(90)), DurationParser.parse(" 1H 30M "));
        assertEquals(Optional.of(Duration.ofSeconds(3_723)), DurationParser.parse("1h2m3s"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"perm", "permanent", "forever", "PERM", " Forever "})
    void permanentWordsReturnEmpty(String input) {
        assertEquals(Optional.empty(), DurationParser.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "5", "five minutes", "5x", "m5", "-5m", "0s", "1h-30m", "permanently", "99999999999999999999d"})
    void invalidInputThrowsInsteadOfMeaningPermanent(String input) {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(input));
    }

    @Test
    void nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(null));
    }

    @Test
    void parseFiniteRejectsPermanent() {
        assertEquals(Duration.ofMinutes(5), DurationParser.parseFinite("5m"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseFinite("perm"));
    }

    @Test
    void formatsForMessages() {
        assertEquals("5m 12s", DurationParser.format(Duration.ofSeconds(312)));
        assertEquals("1h 30m", DurationParser.format(Duration.ofMinutes(90)));
        assertEquals("2d 3h", DurationParser.format(Duration.ofHours(51)));
        assertEquals("0s", DurationParser.format(Duration.ZERO));
        assertEquals("0s", DurationParser.format(Duration.ofMillis(400)));
        assertEquals("0s", DurationParser.format(Duration.ofSeconds(-5)));
        assertEquals("Permanent", DurationParser.format(Optional.empty()));
        assertEquals("15m", DurationParser.format(Optional.of(Duration.ofMinutes(15))));
    }

    @Test
    void formatAndParseRoundTrip() {
        Duration duration = Duration.ofSeconds(93_784);
        assertEquals(Optional.of(duration), DurationParser.parse(DurationParser.format(duration)));
    }

    @Test
    void rejectsExtremeDurationsThatWouldOverflowInstantArithmetic() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("10000000d"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("500000w"));
    }
}
