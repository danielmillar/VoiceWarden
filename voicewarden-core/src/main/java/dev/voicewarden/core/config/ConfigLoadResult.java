package dev.voicewarden.core.config;

import java.util.List;
import java.util.Objects;

public record ConfigLoadResult<T>(T value, List<String> warnings) {

    public ConfigLoadResult {
        Objects.requireNonNull(value, "value");
        warnings = List.copyOf(warnings);
    }
}
