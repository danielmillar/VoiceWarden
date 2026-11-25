package dev.voicewarden.plugin.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.core.config.ConfigFiles;
import dev.voicewarden.core.config.ConfigLoader;
import dev.voicewarden.core.config.EngineConfig;
import dev.voicewarden.core.config.VoiceWardenConfig;
import dev.voicewarden.core.discord.DiscordWebhookConfig;
import dev.voicewarden.core.discord.IncludeTranscriptMode;
import dev.voicewarden.core.ipc.EngineProcessConfig;
import dev.voicewarden.core.ipc.IpcEndpoint;
import dev.voicewarden.core.storage.InMemoryFallbackStore;
import dev.voicewarden.core.storage.StorageConfig;
import dev.voicewarden.core.storage.StorageType;
import dev.voicewarden.core.wordlist.WordlistConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigBridgesTest {

    private static final Path DATA = Path.of("/srv/mc/plugins/VoiceWarden");

    private static VoiceWardenConfig config(String... replacements) {
        String yaml = ConfigFiles.defaultConfigTemplate();
        for (int i = 0; i < replacements.length; i += 2) {
            yaml = yaml.replace(replacements[i], replacements[i + 1]);
        }
        return ConfigLoader.loadConfig(yaml).value();
    }

    @Test
    void sqliteFilesLiveInTheDataFolder() {
        StorageConfig storage = ConfigBridges.storage(config(), DATA);
        assertEquals(StorageType.SQLITE, storage.type());
        assertEquals(DATA.resolve("data").resolve(StorageConfig.MUTE_DATABASE_FILE), storage.muteDatabasePath());
        assertEquals(DATA.resolve("data").resolve(StorageConfig.REPORT_DATABASE_FILE), storage.reportDatabasePath());
        assertEquals("server-1", storage.serverId());
        assertTrue(storage.networkStrikes());
        assertEquals(2000L, storage.queryTimeoutMs());
    }

    @Test
    void discordTargetsCarryEachWebhook() {
        VoiceWardenConfig config = config(
                "  enabled: false\n  webhook_url: \"\"\n  # Name and avatar", "  enabled: true\n  webhook_url: \"https://flag\"\n  # Name and avatar",
                "  webhook_url: \"\"\n  # Attach a playable", "  webhook_url: \"https://report\"\n  # Attach a playable");
        DiscordWebhookConfig discord = ConfigBridges.discord(config, "Test server");
        assertTrue(discord.flagWebhook().isConfigured());
        assertEquals("https://flag", discord.flagWebhook().url());
        assertEquals(IncludeTranscriptMode.BOTH, discord.flagWebhook().includeTranscript());
        assertFalse(discord.muteWebhook().isConfigured());
        assertEquals(IncludeTranscriptMode.MUTED, discord.muteWebhook().includeTranscript());
        assertTrue(discord.reportWebhook().isConfigured());
        assertEquals("https://report", discord.reportWebhook().url());
        assertTrue(discord.reportWebhook().includeAudio());
        assertEquals("Test server", discord.serverName());
        assertEquals("server-1", discord.serverId());
    }

    @Test
    void wordlistOverridesMapToTiers() {
        WordlistConfig wordlist = ConfigBridges.wordlist(config(
                "profanity_words: []", "profanity_words: [heck]",
                "mute-list: []", "mute-list: [zorblax]",
                "allow_words: []", "allow_words: [scunthorpe]"));
        assertEquals(List.of("en"), wordlist.enabledLanguages());
        assertEquals(List.of("heck"), wordlist.extraProfanityWords());
        assertEquals(List.of("zorblax"), wordlist.extraMuteWords());
        assertEquals(List.of("scunthorpe"), wordlist.extraAllowWords());
        assertFalse(wordlist.partialMatch());
    }

    @Test
    void engineFlagsFollowTheConfig() {
        EngineConfig defaults = config().engine();
        List<String> vadDefaults = List.of(ConfigBridges.ARG_VAD_THRESHOLD, "0.5", ConfigBridges.ARG_VAD_MIN_SPEECH_MS, "150",
                ConfigBridges.ARG_VAD_MIN_SILENCE_MS, "300");
        assertEquals(vadDefaults, ConfigBridges.engineArgs(defaults));
        EngineConfig offline = config("auto_download: true", "auto_download: false",
                "    enabled: true\n    # Hits the verifier", "    enabled: false\n    # Hits the verifier").engine();
        List<String> offlineArgs = new ArrayList<>(List.of(ConfigBridges.ARG_NO_AUTO_DOWNLOAD,
                ConfigBridges.ARG_NO_DOUBLE_CHECK));
        offlineArgs.addAll(vadDefaults);
        assertEquals(offlineArgs, ConfigBridges.engineArgs(offline));
        assertTrue(ConfigBridges.engineRestartNeeded(defaults, offline));
        EngineConfig confirmationOnly = config("require_confirmation: true", "require_confirmation: false").engine();
        assertFalse(ConfigBridges.engineRestartNeeded(defaults, confirmationOnly));
    }

    @Test
    void sileroVadSettingsReachTheEngineAndRestartIt() {
        EngineConfig defaults = config().engine();
        EngineConfig noVad = config("    # Skip recognition for audio the detector hears no speech in.\n    enabled: true",
                "    # Skip recognition for audio the detector hears no speech in.\n    enabled: false").engine();
        assertFalse(noVad.sileroVad().enabled());
        assertEquals(List.of(ConfigBridges.ARG_NO_SILERO_VAD), ConfigBridges.engineArgs(noVad));
        assertTrue(ConfigBridges.engineRestartNeeded(defaults, noVad));

        EngineConfig tuned = config("threshold: 0.5", "threshold: 0.35", "min_speech_duration_ms: 150",
                "min_speech_duration_ms: 96").engine();
        assertEquals(List.of(ConfigBridges.ARG_VAD_THRESHOLD, "0.35", ConfigBridges.ARG_VAD_MIN_SPEECH_MS, "96",
                ConfigBridges.ARG_VAD_MIN_SILENCE_MS, "300"), ConfigBridges.engineArgs(tuned));
        assertTrue(ConfigBridges.engineRestartNeeded(defaults, tuned));

        List<String> command = ConfigBridges.engineProcess(config(), DATA, DATA.resolve("engine.jar"),
                IpcEndpoint.Tcp.ephemeral()).command(Optional.empty());
        assertTrue(command.containsAll(List.of(ConfigBridges.ARG_VAD_THRESHOLD, "0.5")), String.join(" ", command));
    }

    @Test
    void engineProcessCommandIsComplete() {
        VoiceWardenConfig config = config("auto_download: true", "auto_download: false");
        IpcEndpoint endpoint = ConfigBridges.engineEndpoint(DATA, false, Path.of("/tmp"));
        Path jar = EngineBinaryExtractor.target(DATA);
        EngineProcessConfig process = ConfigBridges.engineProcess(config, DATA, jar, endpoint);
        List<String> command = process.command(Optional.empty());

        assertTrue(command.containsAll(List.of("--enable-native-access=ALL-UNNAMED", "-Xmx2048m", "-jar", jar.toAbsolutePath().toString())));
        assertTrue(command.containsAll(List.of(IpcEndpoint.ARG_SOCKET, DATA.resolve("engine/engine.sock").toString())));
        assertTrue(command.containsAll(List.of("--profile", "ENGLISH_ACCURATE", "--threads", "2", ConfigBridges.ARG_NO_AUTO_DOWNLOAD)));
        assertEquals(DATA.resolve("models"), process.modelsDir());
        assertEquals(10, process.niceLevel());
    }

    @Test
    void longDataFoldersFallBackToAShortStableSocketPath() {
        Path deep = Path.of("/" + "very-long-directory-name/".repeat(6) + "plugins/VoiceWarden");
        IpcEndpoint endpoint = ConfigBridges.engineEndpoint(deep, false, Path.of("/tmp"));
        IpcEndpoint.Unix unix = assertInstanceOf(IpcEndpoint.Unix.class, endpoint);
        assertEquals(Path.of("/tmp", "vw-" + ConfigBridges.shortStableHash(deep) + ".sock"), unix.socketPath());
        assertEquals(ConfigBridges.shortStableHash(deep), ConfigBridges.shortStableHash(Path.of(deep.toString())));
        assertEquals(ConfigBridges.SOCKET_HASH_CHARS, ConfigBridges.shortStableHash(deep).length());
    }

    @Test
    void windowsUsesLoopbackTcp() {
        assertInstanceOf(IpcEndpoint.Tcp.class, ConfigBridges.engineEndpoint(DATA, true, Path.of("C:/tmp")));
    }

    @Test
    void onlyEnglishProfilesSupportDoubleCheck() {
        assertTrue(ConfigBridges.supportsDoubleCheck(EngineConfig.Profile.ENGLISH));
        assertTrue(ConfigBridges.supportsDoubleCheck(EngineConfig.Profile.ENGLISH_ACCURATE));
        assertFalse(ConfigBridges.supportsDoubleCheck(EngineConfig.Profile.MULTILANG));
        assertFalse(ConfigBridges.supportsDoubleCheck(EngineConfig.Profile.MULTILANG_GLOBAL));
    }

    @Test
    void storageStrikePersistenceUsesTheStore() {
        InMemoryFallbackStore store = new InMemoryFallbackStore("server-1");
        StorageStrikePersistence persistence = new StorageStrikePersistence(store, "server-1", true);
        UUID player = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-24T12:00:00Z");
        persistence.recordStrike(player, "Speaker", FlagSeverity.SEVERE, List.of("a", "b"), "a b", now);
        assertEquals(1, persistence.countRecentStrikes(player, now.minusSeconds(60)));
        assertEquals(0, persistence.countRecentMutes(player, now.minusSeconds(60)));
        persistence.pardonStrikes(player);
        assertEquals(0, persistence.countRecentStrikes(player, now.minusSeconds(60)));
    }

    @Test
    void extractorWritesOnceAndReplacesChangedBinaries(@TempDir Path dir) throws IOException {
        Path target = EngineBinaryExtractor.target(dir);
        byte[] first = "engine-v1".getBytes(StandardCharsets.UTF_8);
        assertEquals(EngineBinaryExtractor.Result.EXTRACTED, EngineBinaryExtractor.extract(() -> new ByteArrayInputStream(first), target));
        assertEquals(EngineBinaryExtractor.Result.UNCHANGED, EngineBinaryExtractor.extract(() -> new ByteArrayInputStream(first), target));
        byte[] second = "engine-v2".getBytes(StandardCharsets.UTF_8);
        assertEquals(EngineBinaryExtractor.Result.EXTRACTED, EngineBinaryExtractor.extract(() -> new ByteArrayInputStream(second), target));
        assertEquals("engine-v2", Files.readString(target));
        assertEquals(EngineBinaryExtractor.Result.MISSING_RESOURCE, EngineBinaryExtractor.extract(() -> null, target));
        try (var files = Files.list(target.getParent())) {
            assertEquals(List.of(target), files.toList());
        }
    }
}
