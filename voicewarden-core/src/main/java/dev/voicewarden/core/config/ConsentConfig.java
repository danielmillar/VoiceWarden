package dev.voicewarden.core.config;

import java.util.Objects;

public record ConsentConfig(boolean enabled, Mode mode, OnDeny onDeny, int rulesVersion, int guiDelayTicks) {

    public enum Mode {
        NOTICE,
        GUI
    }

    public enum OnDeny {
        IGNORE,
        KICK
    }

    public ConsentConfig {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(onDeny, "onDeny");
    }
}
