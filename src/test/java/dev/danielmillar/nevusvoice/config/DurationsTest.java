package dev.danielmillar.nevusvoice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DurationsTest {
    @Test void compoundAndMillisecondsParse() {
        assertEquals(Duration.ofMinutes(90), Durations.parse("1h30m"));
        assertEquals(Duration.ofMillis(500), Durations.parse("500ms"));
        assertEquals(Duration.ofMinutes(90), Durations.parse(" 1H 30M "));
        assertEquals(Duration.ofDays(14), Durations.parse("2w"));
    }

    @Test void bareNumberMeansMinutes() { assertEquals(Duration.ofMinutes(12), Durations.parse("12")); }

    @ParameterizedTest @ValueSource(strings = {"perm", "permanent", "forever", " PERM "})
    void permanentAliasesRoundTrip(String input) {
        assertNull(Durations.parse(input));
        assertNull(Durations.parse(Durations.format(null)));
    }

    @ParameterizedTest @ValueSource(strings = {"2x", "", "-1m", "1.5h", "1hX30m", "1m?"})
    void invalidDurationsThrow(String input) { assertThrows(IllegalArgumentException.class, () -> Durations.parse(input)); }

    @ParameterizedTest @ValueSource(strings = {"0s", "500ms", "1500ms", "1h30m", "2d3h4m5s", "1d500ms", "2w"})
    void formattingPreservesEveryMillisecond(String input) {
        Duration parsed = Durations.parse(input);
        assertEquals(parsed, Durations.parse(Durations.format(parsed)));
    }

    @Test void parseOrFallsBackForInvalidPermanentOrMissingValue() {
        Duration fallback = Duration.ofSeconds(42);
        assertEquals(fallback, Durations.parseOr("2x", fallback));
        assertEquals(fallback, Durations.parseOr("perm", fallback));
        assertEquals(fallback, Durations.parseOr(null, fallback));
        assertEquals(Duration.ofSeconds(2), Durations.parseOr("2s", fallback));
    }
}
