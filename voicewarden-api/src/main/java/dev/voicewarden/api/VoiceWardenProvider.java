package dev.voicewarden.api;

import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

public final class VoiceWardenProvider {

    private static final Object LOCK = new Object();
    private static volatile VoiceWardenAPI instance;

    private VoiceWardenProvider() {
    }

    public static VoiceWardenAPI get() {
        VoiceWardenAPI current = instance;
        if (current == null) {
            throw new IllegalStateException(
                    "VoiceWarden API is not loaded. Depend on VoiceWarden in plugin.yml or paper-plugin.yml "
                            + "and call get() after it enables, or use getOptional().");
        }
        return current;
    }

    public static Optional<VoiceWardenAPI> getOptional() {
        return Optional.ofNullable(instance);
    }

    @ApiStatus.Internal
    public static void register(VoiceWardenAPI api) {
        Objects.requireNonNull(api, "api");
        synchronized (LOCK) {
            if (instance != null && instance != api) {
                throw new IllegalStateException("A VoiceWarden API implementation is already registered");
            }
            instance = api;
        }
    }

    @ApiStatus.Internal
    public static void unregister() {
        synchronized (LOCK) {
            instance = null;
        }
    }
}
