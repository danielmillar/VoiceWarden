package dev.danielmillar.voicewarden.notify;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Small display helpers. */
public final class Formats {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private Formats() {
    }

    public static String clock(Instant instant) {
        return CLOCK.format(instant);
    }

    public static String dateTime(Instant instant) {
        return DATE_TIME.format(instant);
    }
}
