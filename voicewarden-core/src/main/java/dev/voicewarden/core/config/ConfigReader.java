package dev.voicewarden.core.config;

import dev.voicewarden.core.moderation.DurationParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

final class ConfigReader {

    private final Map<?, ?> user;
    private final Map<?, ?> defaults;
    private final String fileName;
    private final List<String> warnings;

    ConfigReader(Map<?, ?> user, Map<?, ?> defaults, String fileName, List<String> warnings) {
        this.user = user;
        this.defaults = defaults;
        this.fileName = fileName;
        this.warnings = warnings;
    }

    static Object lookup(Map<?, ?> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = find(map, part);
        }
        return current;
    }

    private static Object find(Map<?, ?> map, String key) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (String.valueOf(entry.getKey()).equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    boolean hasUserValue(String path) {
        return lookup(user, path) != null;
    }

    <T> Optional<T> readIfPresent(String path, String expected, Function<Object, Optional<T>> parser) {
        Object value = lookup(user, path);
        if (value == null) {
            return Optional.empty();
        }
        Optional<T> parsed = parser.apply(value);
        if (parsed.isPresent()) {
            return parsed;
        }
        warn(path, "expected " + expected + " but found '" + value + "'; using the default");
        return Optional.empty();
    }

    <T> T read(String path, String expected, Function<Object, Optional<T>> parser) {
        Object value = lookup(user, path);
        if (value != null) {
            Optional<T> parsed = parser.apply(value);
            if (parsed.isPresent()) {
                return parsed.get();
            }
            warn(path, "expected " + expected + " but found '" + value + "'; using the default");
        }
        Object fallback = lookup(defaults, path);
        return parser.apply(fallback).orElseThrow(
                () -> new IllegalStateException("Bundled default for " + path + " is missing or invalid: " + fallback));
    }

    int integer(String path, int min, int max) {
        long value = read(path, "a whole number", ConfigReader::asLong);
        if (value < min || value > max) {
            long clamped = Math.max(min, Math.min(max, value));
            warn(path, value + " is outside " + min + ".." + max + "; using " + clamped);
            return (int) clamped;
        }
        return (int) value;
    }

    double decimal(String path, double min, double max) {
        double value = read(path, "a number", raw -> raw instanceof Number number && Double.isFinite(number.doubleValue())
                ? Optional.of(number.doubleValue())
                : asString(raw).flatMap(ConfigReader::parseDouble));
        if (value < min || value > max) {
            double clamped = Math.max(min, Math.min(max, value));
            warn(path, value + " is outside " + min + ".." + max + "; using " + clamped);
            return clamped;
        }
        return value;
    }

    boolean bool(String path) {
        return read(path, "true or false", raw -> {
            if (raw instanceof Boolean value) {
                return Optional.of(value);
            }
            String text = String.valueOf(raw).strip().toLowerCase(Locale.ROOT);
            return switch (text) {
                case "true", "yes", "on" -> Optional.of(true);
                case "false", "no", "off" -> Optional.of(false);
                default -> Optional.empty();
            };
        });
    }

    String string(String path) {
        return read(path, "text", ConfigReader::asString);
    }

    <E extends Enum<E>> E enumValue(String path, Class<E> type) {
        return read(path, "one of " + List.of(type.getEnumConstants()), raw -> asString(raw).flatMap(text -> enumConstant(type, text)));
    }

    List<String> stringList(String path) {
        return read(path, "a list of text", ConfigReader::asStringList);
    }

    List<Integer> intList(String path) {
        return read(path, "a list of whole numbers", raw -> {
            if (!(raw instanceof List<?> list)) {
                return Optional.empty();
            }
            List<Integer> out = new ArrayList<>();
            for (Object item : list) {
                Optional<Long> value = asLong(item);
                if (value.isEmpty() || value.get() < Integer.MIN_VALUE || value.get() > Integer.MAX_VALUE) {
                    return Optional.empty();
                }
                out.add(value.get().intValue());
            }
            return Optional.of(List.copyOf(out));
        });
    }

    Duration duration(String path) {
        return read(path, "a duration such as 30m or 24h", raw -> asString(raw).flatMap(ConfigReader::parseDuration));
    }

    int color(String path) {
        return read(path, "a colour such as \"#E74C3C\"", raw -> {
            if (raw instanceof Number number) {
                return inColorRange(number.longValue());
            }
            String text = String.valueOf(raw).strip();
            if (text.startsWith("#")) {
                text = text.substring(1);
            } else if (text.toLowerCase(Locale.ROOT).startsWith("0x")) {
                text = text.substring(2);
            }
            try {
                return inColorRange(Long.parseLong(text, 16));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    Map<String, Object> map(String path) {
        return read(path, "a map", raw -> {
            if (!(raw instanceof Map<?, ?> map)) {
                return Optional.empty();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
            return Optional.of(out);
        });
    }

    void warn(String path, String message) {
        warnings.add(fileName + ": " + path + ": " + message);
    }

    static Optional<String> asString(Object raw) {
        if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) {
            return Optional.of(String.valueOf(raw));
        }
        return Optional.empty();
    }

    static Optional<List<String>> asStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                Optional<String> text = asString(item);
                if (text.isEmpty()) {
                    return Optional.empty();
                }
                out.add(text.get());
            }
            return Optional.of(List.copyOf(out));
        }
        return asString(raw).map(List::of);
    }

    static Optional<Long> asLong(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return Optional.of(((Number) raw).longValue());
        }
        if (raw instanceof String text) {
            try {
                return Optional.of(Long.parseLong(text.strip()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    static Optional<Duration> parseDuration(String text) {
        try {
            return DurationParser.parse(text);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Double> parseDouble(String text) {
        try {
            double value = Double.parseDouble(text.strip());
            return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> inColorRange(long value) {
        return value >= 0 && value <= 0xFFFFFF ? Optional.of((int) value) : Optional.empty();
    }

    static <E extends Enum<E>> Optional<E> enumConstant(Class<E> type, String text) {
        String normalized = text.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }
}
