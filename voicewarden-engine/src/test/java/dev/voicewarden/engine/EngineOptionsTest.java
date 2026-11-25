package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.core.ipc.IpcEndpoint;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineOptionsTest {

    @Test
    void parsesEverySupervisorArgument() {
        EngineOptions options = EngineOptions.parse(new String[] {
            "--ipc-port", "40123", "--profile", "english_accurate", "--models-dir", "/srv/models",
            "--threads", "3", "--workers", "4", "--max-queued", "9", "--no-auto-download"});
        assertEquals(new IpcEndpoint.Tcp(40123), options.endpoint());
        assertEquals(EngineProfile.ENGLISH_ACCURATE, options.profile());
        assertEquals(Path.of("/srv/models"), options.modelsDir());
        assertEquals(3, options.threads());
        assertEquals(4, options.workers());
        assertEquals(9, options.maxQueuedPerLane());
        assertFalse(options.autoDownload());
        assertFalse(options.doubleCheck());
    }

    @Test
    void defaultsAndDoubleCheckSwitches() {
        EngineOptions defaults = EngineOptions.parse(new String[] {"--ipc-socket", "/tmp/vw.sock"});
        assertEquals(new IpcEndpoint.Unix(Path.of("/tmp/vw.sock")), defaults.endpoint());
        assertEquals(EngineProfile.ENGLISH_ACCURATE, defaults.profile());
        assertTrue(defaults.autoDownload());
        assertFalse(defaults.doubleCheck());
        assertTrue(EngineOptions.parse(new String[] {"--ipc-port", "1", "--profile", "ENGLISH"}).doubleCheck());
        assertFalse(EngineOptions.parse(new String[] {"--ipc-port", "1", "--profile", "ENGLISH", "--no-double-check"})
                .doubleCheck());
        assertFalse(EngineOptions.parse(new String[] {"--ipc-port", "1", "--profile", "MULTILANG"}).doubleCheck());
    }

    @Test
    void sileroVadDefaultsMatchTheShippedConfigAndFlagsOverrideThem() {
        EngineOptions defaults = EngineOptions.parse(new String[] {"--ipc-port", "1"});
        assertEquals(SileroVadSettings.DEFAULTS, defaults.sileroVad());
        assertEquals(new SileroVadSettings(true, 0.5f, 150, 300), defaults.sileroVad());

        EngineOptions tuned = EngineOptions.parse(new String[] {"--ipc-port", "1", "--vad-threshold", "0.35",
            "--vad-min-speech-ms", "96", "--vad-min-silence-ms", "500"});
        assertEquals(new SileroVadSettings(true, 0.35f, 96, 500), tuned.sileroVad());

        EngineOptions off = EngineOptions.parse(new String[] {"--ipc-port", "1", "--no-silero-vad"});
        assertFalse(off.sileroVad().enabled());
        assertTrue(EngineOptions.USAGE.contains(EngineOptions.ARG_NO_SILERO_VAD));
        assertTrue(EngineOptions.USAGE.contains(EngineOptions.ARG_VAD_MIN_SPEECH_MS));
    }

    @Test
    void rejectsBadSileroVadArguments() {
        for (String[] bad : new String[][] {
            {"--vad-threshold", "0"}, {"--vad-threshold", "1"}, {"--vad-threshold", "loud"}, {"--vad-threshold", "NaN"},
            {"--vad-min-speech-ms", "0"}, {"--vad-min-silence-ms", "-5"}, {"--vad-min-speech-ms", "99999"},
            {"--vad-threshold"}}) {
            String[] args = new String[bad.length + 2];
            args[0] = "--ipc-port";
            args[1] = "1";
            System.arraycopy(bad, 0, args, 2, bad.length);
            assertThrows(IllegalArgumentException.class, () -> EngineOptions.parse(args), String.join(" ", bad));
        }
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> EngineOptions.parse(new String[] {}));
        assertThrows(IllegalArgumentException.class,
                () -> EngineOptions.parse(new String[] {"--ipc-port", "1", "--ipc-socket", "/tmp/a"}));
        assertThrows(IllegalArgumentException.class, () -> EngineOptions.parse(new String[] {"--ipc-port", "1", "--bogus"}));
        assertThrows(IllegalArgumentException.class,
                () -> EngineOptions.parse(new String[] {"--ipc-port", "1", "--threads", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> EngineOptions.parse(new String[] {"--ipc-port", "1", "--profile"}));
    }

    @Test
    void mainFailsFastWithoutTokenOrArguments() {
        assertEquals(VoiceWardenEngineMain.EXIT_USAGE, VoiceWardenEngineMain.run(new String[] {"--nope"}, Map.of()));
        assertEquals(VoiceWardenEngineMain.EXIT_CONFIG,
                VoiceWardenEngineMain.run(new String[] {"--ipc-port", "0"}, Map.of()));
    }
}
