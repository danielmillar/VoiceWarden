package dev.danielmillar.voicesentinel.config;

import dev.danielmillar.voicesentinel.testutil.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path folder;

    @Test void bundledDefaultConfigLoadsWithoutAnyWarnings() throws Exception {
        Fakes.resource(folder, "config.yml");
        var loaded = ConfigLoader.load(folder, folder.resolve("config.yml"));
        assertEquals(java.util.List.of(), loaded.warnings());
        assertTrue(ConfigLoader.unknownKeys(folder.resolve("config.yml")).isEmpty());
        assertEquals("parakeet-tdt-v2", loaded.config().speechToText().model());
        assertFalse(loaded.config().speechToText().vad().trimSilence());
        assertEquals(Duration.ofMillis(100), loaded.config().speechToText().vad().minSpeech());
        assertEquals(Duration.ofMillis(160), loaded.config().audio().minUtterance());
        assertEquals(-65, loaded.config().audio().minLevelDbfs());
        assertEquals(new PluginConfig.InputGain(true, -20, 40), loaded.config().speechToText().inputGain());
    }

    @Test void existingConfigUsesGainDefaultsAndExplicitOptOutIsRespected() throws Exception {
        var file = folder.resolve("config.yml");
        java.nio.file.Files.writeString(file, "audio:\n  min-level-dbfs: -50\n");
        var config = ConfigLoader.load(folder, file).config();
        assertEquals(new PluginConfig.InputGain(true, -20, 40), config.speechToText().inputGain());
        assertEquals(-50, config.audio().minLevelDbfs());
        java.nio.file.Files.writeString(file, "speech-to-text:\n  input-gain:\n    enabled: false\n");
        assertFalse(ConfigLoader.load(folder, file).config().speechToText().inputGain().enabled());
    }

    @Test void unsafeGainSettingsFallBackWithWarnings() throws Exception {
        var file = folder.resolve("config.yml");
        java.nio.file.Files.writeString(file,
                "speech-to-text:\n  input-gain:\n    target-dbfs: .nan\n    max-gain-db: 100\n");
        var loaded = ConfigLoader.load(folder, file);
        assertEquals(new PluginConfig.InputGain(true, -20, 40), loaded.config().speechToText().inputGain());
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("input-gain.target-dbfs")));
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("input-gain.max-gain-db")));
    }

    @Test void existingConfigDefaultsToPreservingAudioAndTrimmingCanBeOptedInto() throws Exception {
        java.nio.file.Files.writeString(folder.resolve("config.yml"), "speech-to-text:\n  vad:\n    enabled: true\n");
        assertFalse(ConfigLoader.load(folder, folder.resolve("config.yml")).config().speechToText().vad().trimSilence());
        java.nio.file.Files.writeString(folder.resolve("config.yml"),
                "speech-to-text:\n  vad:\n    trim-silence: true\n");
        assertTrue(ConfigLoader.load(folder, folder.resolve("config.yml")).config().speechToText().vad().trimSilence());
    }

    @Test void completeBundledConfigBundleContainsRulesAndNoWarnings() throws Exception {
        for (String resource : java.util.List.of("config.yml", "messages.yml", "rules.yml", "wordlist.txt")) {
            Fakes.resource(folder, resource);
        }
        ConfigBundle bundle = ConfigBundle.load(folder);
        assertTrue(bundle.rules().ruleCount() > 0);
        assertEquals(java.util.List.of(), bundle.warnings());
        assertFalse(bundle.messages().raw("player.warned").isBlank());
    }

    @Test void invalidValuesFallBackToBundledDefaultsWithWarnings() throws Exception {
        PluginConfig defaults = Fakes.config(folder);
        Path file = folder.resolve("config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        yaml.set("audio.silence-timeout", "2x");
        yaml.set("audio.max-buffered-frames", -1);
        yaml.set("audio.min-level-dbfs", 4);
        yaml.set("recordings.mode", "garbage");
        yaml.save(file.toFile());
        var loaded = ConfigLoader.load(folder, file);
        assertEquals(4, loaded.warnings().size());
        assertEquals(defaults.audio().silenceTimeout(), loaded.config().audio().silenceTimeout());
        assertEquals(defaults.audio().maxBufferedFrames(), loaded.config().audio().maxBufferedFrames());
        assertEquals(defaults.audio().minLevelDbfs(), loaded.config().audio().minLevelDbfs());
        assertEquals(defaults.recordings().mode(), loaded.config().recordings().mode());
        for (String key : java.util.List.of("audio.silence-timeout", "audio.max-buffered-frames", "audio.min-level-dbfs", "recordings.mode")) {
            assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains(key)), key);
        }
    }

    @Test void invalidYamlThrowsUsefulIOException() throws Exception {
        java.nio.file.Files.writeString(folder.resolve("config.yml"), "audio: [unterminated");
        var error = assertThrows(java.io.IOException.class, () -> ConfigLoader.load(folder, folder.resolve("config.yml")));
        assertTrue(error.getMessage().contains("valid YAML"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2.5", "4294967296", ".nan", ".inf"})
    void invalidWholeNumbersCannotBeTruncatedOrWrapped(String value) throws Exception {
        PluginConfig defaults = Fakes.config(folder);
        Path file = folder.resolve("config.yml");
        java.nio.file.Files.writeString(file, "audio:\n  max-buffered-frames: " + value + "\n");
        var loaded = ConfigLoader.load(folder, file);
        assertEquals(defaults.audio().maxBufferedFrames(), loaded.config().audio().maxBufferedFrames());
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("audio.max-buffered-frames")));
    }

    @Test void nonFiniteAudioLevelFallsBackWithWarning() throws Exception {
        PluginConfig defaults = Fakes.config(folder);
        Path file = folder.resolve("config.yml");
        java.nio.file.Files.writeString(file, "audio:\n  min-level-dbfs: .nan\n");
        var loaded = ConfigLoader.load(folder, file);
        assertEquals(defaults.audio().minLevelDbfs(), loaded.config().audio().minLevelDbfs());
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("audio.min-level-dbfs")));
    }

    @Test void muteLadderUsesExactFloorFirstAndMaximumBeyondLastTier() {
        TreeMap<Integer, Duration> tiers = new TreeMap<>();
        tiers.put(2, Duration.ofMinutes(5));
        tiers.put(4, Duration.ofMinutes(30));
        var ladder = new PluginConfig.MuteLadder(true, tiers, Duration.ofHours(2), 7);
        assertEquals(Duration.ofMinutes(5), ladder.durationFor(1));
        assertEquals(Duration.ofMinutes(5), ladder.durationFor(2));
        assertEquals(Duration.ofMinutes(5), ladder.durationFor(3));
        assertEquals(Duration.ofMinutes(30), ladder.durationFor(4));
        assertEquals(Duration.ofHours(2), ladder.durationFor(5));
        assertEquals(Duration.ofHours(2), ladder.durationFor(100));
        assertEquals(Duration.ofHours(2), new PluginConfig.MuteLadder(true, new TreeMap<>(), Duration.ofHours(2), 0).durationFor(1));
    }

    @Test void configuredLadderAndDisabledLadderHaveExpectedSemantics() throws Exception {
        PluginConfig config = Fakes.config(folder);
        var moderation = config.moderation();
        var ladder = Fakes.with(moderation.ladder(), "enabled", true);
        var enabled = Fakes.with(moderation, "ladder", ladder);
        assertEquals(ladder.durationFor(1), enabled.muteDurationFor(1));
        assertEquals(ladder.maxTierDuration(), enabled.muteDurationFor(ladder.tiers().lastKey() + 1));
        var disabled = Fakes.with(moderation, "ladder", Fakes.with(ladder, "enabled", false));
        assertEquals(moderation.muteDuration(), disabled.muteDurationFor(100));
    }
}
