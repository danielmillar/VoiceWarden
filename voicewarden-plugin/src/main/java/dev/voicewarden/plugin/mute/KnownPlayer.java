package dev.voicewarden.plugin.mute;

import java.util.Objects;
import java.util.UUID;

public record KnownPlayer(UUID id, String name) {

    public KnownPlayer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
