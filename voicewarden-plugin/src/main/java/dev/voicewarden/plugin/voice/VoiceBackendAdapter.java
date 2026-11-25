package dev.voicewarden.plugin.voice;

import java.time.Duration;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public interface VoiceBackendAdapter {

    String name();

    boolean isAvailable();

    void register();

    void unregister();

    default void close() {
        unregister();
    }

    default void applyRemoteMute(UUID playerId, boolean muted, @Nullable Duration duration, String reason) {
    }
}
