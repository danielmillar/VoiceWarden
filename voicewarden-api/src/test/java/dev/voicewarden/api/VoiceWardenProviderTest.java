package dev.voicewarden.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VoiceWardenProviderTest {

    @AfterEach
    void clear() {
        VoiceWardenProvider.unregister();
    }

    @Test
    void getFailsWithGuidanceBeforeRegistration() {
        IllegalStateException error = assertThrows(IllegalStateException.class, VoiceWardenProvider::get);
        assertTrue(error.getMessage().contains("getOptional"));
        assertTrue(VoiceWardenProvider.getOptional().isEmpty());
    }

    @Test
    void registerExposesInstanceUntilUnregistered() {
        VoiceWardenAPI api = mock(VoiceWardenAPI.class);

        VoiceWardenProvider.register(api);
        assertSame(api, VoiceWardenProvider.get());
        assertSame(api, VoiceWardenProvider.getOptional().orElseThrow());

        VoiceWardenProvider.unregister();
        assertTrue(VoiceWardenProvider.getOptional().isEmpty());
        assertThrows(IllegalStateException.class, VoiceWardenProvider::get);
    }

    @Test
    void reregisteringTheSameInstanceIsAllowed() {
        VoiceWardenAPI api = mock(VoiceWardenAPI.class);
        VoiceWardenProvider.register(api);
        VoiceWardenProvider.register(api);
        assertSame(api, VoiceWardenProvider.get());
    }

    @Test
    void registeringASecondImplementationFails() {
        VoiceWardenAPI first = mock(VoiceWardenAPI.class);
        VoiceWardenProvider.register(first);

        assertThrows(IllegalStateException.class, () -> VoiceWardenProvider.register(mock(VoiceWardenAPI.class)));
        assertSame(first, VoiceWardenProvider.get());
    }

    @Test
    void registerRejectsNull() {
        assertThrows(NullPointerException.class, () -> VoiceWardenProvider.register(null));
    }

    @Test
    void unregisterWhenEmptyIsHarmless() {
        VoiceWardenProvider.unregister();
        assertTrue(VoiceWardenProvider.getOptional().isEmpty());
    }
}
