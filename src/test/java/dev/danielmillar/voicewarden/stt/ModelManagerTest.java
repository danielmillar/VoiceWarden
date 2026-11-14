package dev.danielmillar.voicewarden.stt;

import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ModelManagerTest {

    @TempDir Path folder;

    @ParameterizedTest
    @EnumSource(value = ModelManager.Preset.class, names = {"WHISPER_SMALL_EN", "WHISPER_TURBO"})
    void whisperPresetNeedsNoJoiner(ModelManager.Preset preset) {
        Path directory = folder.resolve(preset.id);
        var resolved = ModelManager.layout(preset, directory, null);
        String prefix = preset == ModelManager.Preset.WHISPER_SMALL_EN ? "small.en" : "turbo";
        assertEquals("whisper", resolved.modelType());
        assertEquals(directory.resolve(prefix + "-encoder.int8.onnx"), resolved.encoder());
        assertEquals(directory.resolve(prefix + "-decoder.int8.onnx"), resolved.decoder());
        assertEquals(directory.resolve(prefix + "-tokens.txt"), resolved.tokens());
        assertNull(resolved.joiner());
        assertNull(resolved.vad());
    }

    @ParameterizedTest
    @EnumSource(value = ModelManager.Preset.class, names = {"PARAKEET_TDT_V2", "PARAKEET_TDT_V3"})
    void sameSizeFileFromAnOlderExportIsTreatedAsOutdated(ModelManager.Preset preset) throws Exception {
        Path directory = folder.resolve("models/" + preset.id);
        Files.createDirectories(directory);
        for (var file : preset.files) {
            // Right size, wrong contents: what an older upstream export with an unchanged size looks like.
            try (var channel = FileChannel.open(directory.resolve(file.name()),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(file.size() - 1);
                channel.write(ByteBuffer.wrap(new byte[]{0}));
            }
        }
        try (var http = HttpClient.newHttpClient()) {
            var error = assertThrows(java.io.IOException.class, () -> ModelManager.resolve(
                    settings(preset), folder, new Downloader(http, Logger.getAnonymousLogger())));
            assertTrue(error.getMessage().contains("outdated"), error.getMessage());
            assertTrue(error.getMessage().contains(preset.files.getFirst().name()), error.getMessage());
        }
    }

    @org.junit.jupiter.api.Test
    void cachedFileMatchesOnlyWithPinnedSizeAndHash() throws Exception {
        byte[] bytes = "tokens".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path path = Files.write(folder.resolve("tokens.txt"), bytes);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertTrue(ModelManager.matches(path, new ModelManager.ModelFile("tokens.txt", bytes.length, sha)));
        assertFalse(ModelManager.matches(path, new ModelManager.ModelFile("tokens.txt", bytes.length, "00".repeat(32))));
        assertFalse(ModelManager.matches(path, new ModelManager.ModelFile("tokens.txt", bytes.length + 1, sha)));
        assertFalse(ModelManager.matches(folder.resolve("missing"), new ModelManager.ModelFile("missing", 1, sha)));
    }

    @ParameterizedTest
    @EnumSource(value = ModelManager.Preset.class, names = {"WHISPER_SMALL_EN", "WHISPER_TURBO"})
    void incompleteOfflineWhisperCacheReportsTheMissingComponent(ModelManager.Preset preset) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var error = assertThrows(java.io.IOException.class, () -> ModelManager.resolve(
                    settings(preset), folder, new Downloader(http, Logger.getAnonymousLogger())));
            assertTrue(error.getMessage().contains(preset.files.getFirst().name()));
            assertTrue(error.getMessage().contains("/resolve/" + preset.revision + "/"));
        }
    }

    @ParameterizedTest
    @EnumSource(ModelManager.Preset.class)
    void everyPresetDownloadsFromAPinnedCommit(ModelManager.Preset preset) {
        assertTrue(preset.revision.matches("[0-9a-f]{40}"), preset.id + " revision " + preset.revision);
        for (var file : preset.files) {
            assertTrue(preset.url(file).toString().endsWith("/resolve/" + preset.revision + "/" + file.name()));
        }
    }

    private PluginConfig.SpeechToText settings(ModelManager.Preset preset) throws Exception {
        var settings = Fakes.config(folder).speechToText();
        settings = Fakes.with(settings, "model", preset.id);
        settings = Fakes.with(settings, "autoDownload", false);
        return Fakes.with(settings, "vad", Fakes.with(settings.vad(), "enabled", false));
    }
}
