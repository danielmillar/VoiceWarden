package dev.voicewarden.core.moderation;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    public static final String PERMANENT_LABEL = "Permanent";
    public static final long MAX_DURATION_SECONDS = 3_155_760_000L;

    private static final Set<String> PERMANENT_WORDS = Set.of("perm", "permanent", "forever");
    private static final Pattern COMPONENT = Pattern.compile("(\\d+)\\s*([wdhms])");
    private static final Pattern FULL = Pattern.compile("(?:\\d+\\s*[wdhms]\\s*)+");

    private DurationParser() {
    }

    public static Optional<Duration> parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Duration is missing");
        }
        String text = input.strip().toLowerCase(Locale.ROOT);
        if (PERMANENT_WORDS.contains(text)) {
            return Optional.empty();
        }
        if (text.isEmpty() || !FULL.matcher(text).matches()) {
            throw new IllegalArgumentException("Not a duration: '" + input + "'. Use values like 30s, 5m, 1h30m or perm");
        }
        long seconds = 0;
        Matcher matcher = COMPONENT.matcher(text);
        try {
            while (matcher.find()) {
                long amount = Long.parseLong(matcher.group(1));
                seconds = Math.addExact(seconds, Math.multiplyExact(amount, unitSeconds(matcher.group(2).charAt(0))));
            }
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Duration is too long: '" + input + "'", e);
        }
        if (seconds == 0) {
            throw new IllegalArgumentException("Duration must be longer than zero: '" + input + "'");
        }
        if (seconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("Duration is too long: '" + input + "'");
        }
        return Optional.of(Duration.ofSeconds(seconds));
    }

    public static Duration parseFinite(String input) {
        return parse(input).orElseThrow(() -> new IllegalArgumentException("A permanent duration is not allowed here: '" + input + "'"));
    }

    public static String format(Optional<Duration> duration) {
        return duration.map(DurationParser::format).orElse(PERMANENT_LABEL);
    }

    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds == 0) {
            return "0s";
        }
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long secs = seconds % 60;
        StringBuilder out = new StringBuilder();
        append(out, days, 'd');
        append(out, hours, 'h');
        append(out, minutes, 'm');
        append(out, secs, 's');
        return out.toString();
    }

    private static void append(StringBuilder out, long amount, char unit) {
        if (amount == 0) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(amount).append(unit);
    }

    private static long unitSeconds(char unit) {
        return switch (unit) {
            case 'w' -> 604_800L;
            case 'd' -> 86_400L;
            case 'h' -> 3_600L;
            case 'm' -> 60L;
            case 's' -> 1L;
            default -> throw new IllegalArgumentException("Unknown unit " + unit);
        };
    }
}
