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
        ENGLISH(true),
        ENGLISH_ACCURATE(false),
        MULTILANG(false),
        MULTILANG_GLOBAL(false);

        private final boolean hasDoubleCheckVerifier;

        Profile(boolean hasDoubleCheckVerifier) {
            this.hasDoubleCheckVerifier = hasDoubleCheckVerifier;
        }

        public boolean hasDoubleCheckVerifier() {
            return hasDoubleCheckVerifier;
        }
    }

    public EngineConfig {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(modelsDir, "modelsDir");
        Objects.requireNonNull(sileroVad, "sileroVad");
    }

    public boolean doubleCheckActive() {
        return doubleCheckEnabled && profile.hasDoubleCheckVerifier();
    }
}
