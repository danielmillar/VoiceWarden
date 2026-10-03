package dev.danielmillar.voicesentinel.config;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats compact durations such as {@code 500ms}, {@code 30s}, {@code 1h30m}, {@code 2d}. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d|w)");

    private Durations() {
    }

    /**
     * @return the parsed duration, or {@code null} for "perm"/"permanent"/"forever"
     * @throws IllegalArgumentException if the text is not a valid duration
     */
    public static @Nullable Duration parse(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.equals("perm") || s.equals("permanent") || s.equals("forever")) {
            return null;
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty duration");
        }
        if (s.chars().allMatch(Character::isDigit)) {
            return Duration.ofMinutes(Long.parseLong(s));
        }
        Matcher m = PART.matcher(s);
        Duration total = Duration.ZERO;
        int end = 0;
        while (m.find()) {
            if (m.start() != end && !s.substring(end, m.start()).isBlank()) {
                throw new IllegalArgumentException("invalid duration: " + text);
            }
            long n = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                case "w" -> Duration.ofDays(n * 7);
                default -> throw new IllegalStateException();
            });
            end = m.end();
        }
        if (end == 0 || !s.substring(end).isBlank()) {
            throw new IllegalArgumentException("invalid duration: " + text);
        }
        return total;
    }

    /** Parses a duration that may not be permanent, falling back when invalid. */
    public static Duration parseOr(@Nullable String text, Duration fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            Duration d = parse(text);
            return d == null ? fallback : d;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Formats like {@code 1h 30m}, {@code 45s}, {@code 2d 3h}; {@code null} formats as "permanent". */
    public static String format(@Nullable Duration duration) {
        if (duration == null) {
            return "permanent";
        }
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds == 0) {
            return duration.toMillis() > 0 ? duration.toMillis() + "ms" : "0s";
        }
        long d = seconds / 86_400;
        long h = (seconds % 86_400) / 3_600;
        long m = (seconds % 3_600) / 60;
        long s = seconds % 60;
        StringBuilder out = new StringBuilder();
        append(out, d, "d");
        append(out, h, "h");
        append(out, m, "m");
        append(out, s, "s");
        append(out, duration.toMillis() % 1000, "ms");
        return out.toString();
    }

    /**
     * Human-friendly countdown/elapsed text: rounded to whole seconds and limited to the two largest units, e.g.
     * {@code 4m 59s}, {@code 2h 15m}, {@code 3d 4h}. {@code null} formats as "permanent".
     */
    public static String formatShort(@Nullable Duration duration) {
        if (duration == null) {
            return "permanent";
        }
        long seconds = Math.max(0, (duration.toMillis() + 999) / 1000);
        long[] values = {seconds / 86_400, (seconds % 86_400) / 3_600, (seconds % 3_600) / 60, seconds % 60};
        String[] units = {"d", "h", "m", "s"};
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < values.length && shown < 2; i++) {
            if (values[i] > 0 || (shown > 0)) {
                if (values[i] > 0) {
                    append(out, values[i], units[i]);
                }
                shown++;
            }
        }
        return out.isEmpty() ? "0s" : out.toString();
    }

    private static void append(StringBuilder out, long value, String unit) {
        if (value > 0) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(value).append(unit);
        }
    }
}
