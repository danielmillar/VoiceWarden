package dev.voicewarden.core.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

public record MessagesConfig(Map<MessageKey, String> messages, Map<String, String> theme, Map<String, String> prefixes) {

    public static final Map<String, String> DEFAULT_THEME = Map.of(
            "primary", "#3DD6C6",
            "body", "#E4E4EC",
            "muted", "#9FA4B8",
            "dim", "#6E7385",
            "accent", "#FFFFFF",
            "success", "#4BE38B",
            "warning", "#FFB454",
            "error", "#FF5C5C");

    public MessagesConfig(Map<MessageKey, String> messages) {
        this(messages, DEFAULT_THEME, Map.of(
                "prefix", messages.getOrDefault(MessageKey.PREFIX, "<primary><b>VOICE</b></primary> <body>"),
                "hold-up", messages.getOrDefault(MessageKey.HOLD_UP, "<error><b>HOLD UP!</b></error> <body>"),
                "oops", messages.getOrDefault(MessageKey.OOPS, "<error><b>OOPS!</b></error> <body>")));
    }

    public MessagesConfig {
        EnumMap<MessageKey, String> copy = new EnumMap<>(MessageKey.class);
        for (MessageKey key : MessageKey.values()) {
            String value = messages.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Missing message " + key.path());
            }
            copy.put(key, value);
        }
        messages = Collections.unmodifiableMap(copy);

        Map<String, String> themeCopy = new LinkedHashMap<>(DEFAULT_THEME);
        if (theme != null) {
            theme.forEach((name, color) -> {
                if (name != null && !name.isBlank() && color != null && !color.isBlank()) {
                    themeCopy.put(name.strip(), color.strip());
                }
            });
        }
        theme = Collections.unmodifiableMap(themeCopy);

        Map<String, String> prefixCopy = new LinkedHashMap<>();
        prefixCopy.put("prefix", copy.get(MessageKey.PREFIX));
        prefixCopy.put("hold-up", copy.get(MessageKey.HOLD_UP));
        prefixCopy.put("oops", copy.get(MessageKey.OOPS));
        if (prefixes != null) {
            prefixes.forEach((name, snippet) -> {
                if (name != null && !name.isBlank() && snippet != null) {
                    prefixCopy.put(name.strip(), snippet);
                }
            });
        }
        prefixes = Collections.unmodifiableMap(prefixCopy);
    }

    public String get(MessageKey key) {
        return messages.get(key);
    }

    public String prefix() {
        return prefixes.getOrDefault("prefix", get(MessageKey.PREFIX));
    }

    public String holdUp() {
        return prefixes.getOrDefault("hold-up", get(MessageKey.HOLD_UP));
    }

    public String oops() {
        return prefixes.getOrDefault("oops", get(MessageKey.OOPS));
    }
}
