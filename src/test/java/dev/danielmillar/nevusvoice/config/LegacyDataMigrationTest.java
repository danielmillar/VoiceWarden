package dev.danielmillar.nevusvoice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LegacyDataMigrationTest {
    @TempDir Path plugins;

    @Test void movesModelsRecordingsAndPersistentMutesTogether() throws Exception {
        Path legacy = plugins.resolve("VoiceSentinel");
        for (String file : new String[]{"config.yml", "wordlist.txt", "messages.yml", "rules.yml", "data/mutes.json",
                "data/reports.json", "models/parakeet-tdt-v2/encoder.int8.onnx", "recordings/incident.wav"}) {
            Path path = legacy.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, file);
        }
        Path renamed = plugins.resolve("NevusVoice");
        assertTrue(LegacyDataMigration.migrate(renamed));
        assertFalse(Files.exists(legacy));
        assertEquals("data/mutes.json", Files.readString(renamed.resolve("data/mutes.json")));
        assertEquals("models/parakeet-tdt-v2/encoder.int8.onnx",
                Files.readString(renamed.resolve("models/parakeet-tdt-v2/encoder.int8.onnx")));
        assertEquals("recordings/incident.wav", Files.readString(renamed.resolve("recordings/incident.wav")));
        assertFalse(LegacyDataMigration.migrate(renamed), "Migration must not repeat");
    }

    @Test void acceptsAnEmptyDirectoryCreatedByTheServer() throws Exception {
        Path legacy = Files.createDirectory(plugins.resolve("VoiceSentinel"));
        Files.writeString(legacy.resolve("config.yml"), "existing config");
        Path renamed = Files.createDirectory(plugins.resolve("NevusVoice"));
        assertTrue(LegacyDataMigration.migrate(renamed));
        assertEquals("existing config", Files.readString(renamed.resolve("config.yml")));
    }

    @Test void neverOverwritesAnExistingNevusVoiceInstallation() throws Exception {
        Path legacy = Files.createDirectory(plugins.resolve("VoiceSentinel"));
        Files.writeString(legacy.resolve("config.yml"), "legacy config");
        Path renamed = Files.createDirectory(plugins.resolve("NevusVoice"));
        Files.writeString(renamed.resolve("config.yml"), "new config");
        assertFalse(LegacyDataMigration.migrate(renamed));
        assertEquals("new config", Files.readString(renamed.resolve("config.yml")));
        assertEquals("legacy config", Files.readString(legacy.resolve("config.yml")));
    }

    @Test void freshInstallationNeedsNoMigration() throws Exception {
        Path renamed = plugins.resolve("NevusVoice");
        assertFalse(LegacyDataMigration.migrate(renamed));
        assertFalse(Files.exists(renamed));
    }
}
