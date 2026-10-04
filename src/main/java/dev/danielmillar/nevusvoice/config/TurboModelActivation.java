package dev.danielmillar.nevusvoice.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;

/** Applies an explicitly staged Turbo selection once, when the supporting plugin starts. */
public final class TurboModelActivation {
    public static final String REQUEST_FILE = "activate-whisper-turbo";

    private TurboModelActivation() {
    }

    /** Leaves a byte-for-byte backup and preserves unrelated NevusVoice settings. */
    public static Optional<Path> apply(Path dataFolder) throws IOException {
        Path request = dataFolder.resolve(REQUEST_FILE);
        if (!Files.exists(request)) {
            return Optional.empty();
        }
        if (!Files.readString(request, StandardCharsets.UTF_8).strip().equals("whisper-turbo")) {
            throw new IOException(REQUEST_FILE + " must contain only 'whisper-turbo'");
        }
        Path configFile = dataFolder.resolve("config.yml");
        byte[] original = Files.readAllBytes(configFile);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.loadFromString(new String(original, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException("Cannot activate Turbo: config.yml is not valid YAML", e);
        }
        yaml.set("speech-to-text.model", "whisper-turbo");
        yaml.set("speech-to-text.input-gain.enabled", true);
        yaml.set("speech-to-text.input-gain.target-dbfs", -20);
        yaml.set("speech-to-text.input-gain.max-gain-db", 40);
        yaml.set("audio.min-level-dbfs", -65);

        Path backups = dataFolder.resolve("backups");
        Files.createDirectories(backups);
        // Private temporary files avoid exposing webhook credentials in either the backup or new config.
        Path backup = Files.createTempFile(backups, "config-before-turbo-", ".yml");
        Files.write(backup, original);
        Path replacement = Files.createTempFile(dataFolder, "config-turbo-", ".tmp");
        try {
            Files.writeString(replacement, yaml.saveToString(), StandardCharsets.UTF_8);
            if (!Arrays.equals(original, Files.readAllBytes(configFile))) {
                throw new IOException("config.yml changed during Turbo activation; keeping the latest config");
            }
            // If atomic replacement is unsupported, keep the original rather than risk a partial config.
            Files.move(replacement, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.move(request, dataFolder.resolve(REQUEST_FILE + ".applied"), StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(backup);
        } finally {
            Files.deleteIfExists(replacement);
        }
    }
}
