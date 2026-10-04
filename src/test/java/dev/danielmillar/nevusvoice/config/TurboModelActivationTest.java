package dev.danielmillar.nevusvoice.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TurboModelActivationTest {
    @TempDir Path folder;

    @Test void activationMergesLatestConfigKeepsCommentsAndConsumesRequestOnce() throws Exception {
        Path config = folder.resolve("config.yml");
        String original = """
                # Preserve my settings
                server-name: Exchanger
                speech-to-text:
                  model: parakeet-tdt-v2
                  cpu-threads: 2
                  workers: 1
                audio:
                  min-level-dbfs: -50
                  silence-timeout: 550ms
                discord:
                  flags:
                    enabled: false
                    webhook-url: 'https://example.invalid/private-webhook'
                custom-commands:
                  mute: ['say player muted']
                """;
        Files.writeString(config, original);
        Files.writeString(folder.resolve(TurboModelActivation.REQUEST_FILE), "whisper-turbo\n");

        Path backup = TurboModelActivation.apply(folder).orElseThrow();
        assertEquals(original, Files.readString(backup));
        assertTrue(Files.readString(config).contains("# Preserve my settings"));
        assertFalse(Files.exists(folder.resolve(TurboModelActivation.REQUEST_FILE)));
        assertTrue(Files.exists(folder.resolve(TurboModelActivation.REQUEST_FILE + ".applied")));
        var loaded = ConfigLoader.load(folder, config);
        assertEquals(List.of(), loaded.warnings());
        assertEquals("whisper-turbo", loaded.config().speechToText().model());
        assertEquals(new PluginConfig.InputGain(true, -20, 40), loaded.config().speechToText().inputGain());
        assertEquals(-65, loaded.config().audio().minLevelDbfs());
        var yaml = YamlConfiguration.loadConfiguration(config.toFile());
        assertFalse(yaml.getBoolean("discord.flags.enabled"));
        assertEquals("https://example.invalid/private-webhook", yaml.getString("discord.flags.webhook-url"));
        assertEquals("550ms", yaml.getString("audio.silence-timeout"));
        assertEquals(1, yaml.getInt("speech-to-text.workers"));
        assertEquals(2, yaml.getInt("speech-to-text.cpu-threads"));
        assertEquals(List.of("say player muted"), yaml.getStringList("custom-commands.mute"));

        // An admin's later model selection must survive every subsequent startup.
        yaml.set("speech-to-text.model", "whisper-small-en");
        yaml.save(config.toFile());
        byte[] edited = Files.readAllBytes(config);
        assertTrue(TurboModelActivation.apply(folder).isEmpty());
        assertArrayEquals(edited, Files.readAllBytes(config));
        try (var files = Files.list(folder.resolve("backups"))) {
            assertEquals(1, files.count());
        }
    }

    @Test void noRequestLeavesConfigUntouched() throws Exception {
        Path config = folder.resolve("config.yml");
        Files.writeString(config, "speech-to-text:\n  model: parakeet-tdt-v2\n");
        byte[] original = Files.readAllBytes(config);
        assertTrue(TurboModelActivation.apply(folder).isEmpty());
        assertArrayEquals(original, Files.readAllBytes(config));
        assertFalse(Files.exists(folder.resolve("backups")));
    }

    @Test void invalidRequestKeepsOriginalAndRequestForCorrection() throws Exception {
        Path config = folder.resolve("config.yml");
        Files.writeString(config, "speech-to-text:\n  model: parakeet-tdt-v2\n");
        Files.writeString(folder.resolve(TurboModelActivation.REQUEST_FILE), "unexpected-model\n");
        byte[] original = Files.readAllBytes(config);
        assertThrows(IOException.class, () -> TurboModelActivation.apply(folder));
        assertArrayEquals(original, Files.readAllBytes(config));
        assertTrue(Files.exists(folder.resolve(TurboModelActivation.REQUEST_FILE)));
    }

    @Test void malformedConfigCannotBeOverwrittenByActivation() throws Exception {
        Files.writeString(folder.resolve("config.yml"), "audio: [unterminated");
        Files.writeString(folder.resolve(TurboModelActivation.REQUEST_FILE), "whisper-turbo\n");
        assertThrows(IOException.class, () -> TurboModelActivation.apply(folder));
        assertEquals("audio: [unterminated", Files.readString(folder.resolve("config.yml")));
        assertTrue(Files.exists(folder.resolve(TurboModelActivation.REQUEST_FILE)));
    }

    @Test void backupFailureLeavesOriginalConfigAndRequestIntact() throws Exception {
        Path config = folder.resolve("config.yml");
        Files.writeString(config, "speech-to-text:\n  model: parakeet-tdt-v2\n");
        Files.writeString(folder.resolve(TurboModelActivation.REQUEST_FILE), "whisper-turbo\n");
        Files.writeString(folder.resolve("backups"), "blocked backup directory");
        byte[] original = Files.readAllBytes(config);
        assertThrows(IOException.class, () -> TurboModelActivation.apply(folder));
        assertArrayEquals(original, Files.readAllBytes(config));
        assertTrue(Files.exists(folder.resolve(TurboModelActivation.REQUEST_FILE)));
    }
}
