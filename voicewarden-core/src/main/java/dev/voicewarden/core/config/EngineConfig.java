package dev.voicewarden.core.config;

import java.util.Objects;

public record EngineConfig(
        Profile profile,
        int threads,
        int maxMemoryMb,
        String modelsDir,
        boolean autoDownload,
        int nicePriority,
        boolean doubleCheckEnabled,
        boolean requireConfirmation,
        SileroVadConfig sileroVad) {

    public enum Profile {
        ENGLISH,
        ENGLISH_ACCURATE,
        MULTILANG,
        MULTILANG_GLOBAL
    }

    public EngineConfig {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(modelsDir, "modelsDir");
        Objects.requireNonNull(sileroVad, "sileroVad");
    }
}
