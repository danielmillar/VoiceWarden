package dev.danielmillar.voicesentinel.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Carries the original plugin's data forward without copying models or overwriting an existing installation. */
public final class LegacyDataMigration {
    private LegacyDataMigration() {
    }

    public static boolean migrate(Path dataFolder) throws IOException {
        Path legacy = dataFolder.resolveSibling("VoiceSentinel");
        if (!Files.isDirectory(legacy) || legacy.equals(dataFolder)) {
            return false;
        }
        if (Files.exists(dataFolder)) {
            try (var contents = Files.list(dataFolder)) {
                if (contents.findAny().isPresent()) {
                    return false;
                }
            }
            Files.delete(dataFolder);
        }
        // Both folders share a parent: moving the directory preserves all data without reading model/audio files.
        Files.move(legacy, dataFolder);
        return true;
    }
}
