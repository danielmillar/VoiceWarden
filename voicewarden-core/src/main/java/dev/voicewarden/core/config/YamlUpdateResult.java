package dev.voicewarden.core.config;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record YamlUpdateResult(
        String content,
        boolean created,
        boolean changed,
        List<String> addedKeys,
        boolean versionUpdated,
        Optional<String> skippedReason) {

    public YamlUpdateResult {
        Objects.requireNonNull(content, "content");
        addedKeys = List.copyOf(addedKeys);
        Objects.requireNonNull(skippedReason, "skippedReason");
    }

    static YamlUpdateResult skipped(String content, String reason) {
        return new YamlUpdateResult(content, false, false, List.of(), false, Optional.of(reason));
    }
}
