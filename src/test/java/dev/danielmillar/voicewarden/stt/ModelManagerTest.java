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
    void cachedWhisperPresetNeedsNoJoinerOrDownloadsOnRestart(ModelManager.Preset preset) throws Exception {
        Path directory = folder.resolve("models/" + preset.id);
        Files.createDirectories(directory);
        for (var file : preset.files) {
            // Sparse files exercise cache/layout resolution without hundreds of MB of fixtures or network access.
            try (var channel = FileChannel.open(directory.resolve(file.name()),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(file.size() - 1);
                channel.write(ByteBuffer.wrap(new byte[]{0}));
            }
        }
        try (var http = HttpClient.newHttpClient()) {
            var resolved = ModelManager.resolve(settings(preset), folder, new Downloader(http, Logger.getAnonymousLogger()));
            String prefix = preset == ModelManager.Preset.WHISPER_SMALL_EN ? "small.en" : "turbo";
            assertEquals("whisper", resolved.modelType());
            assertEquals(directory.resolve(prefix + "-encoder.int8.onnx"), resolved.encoder());
            assertEquals(directory.resolve(prefix + "-decoder.int8.onnx"), resolved.decoder());
            assertEquals(directory.resolve(prefix + "-tokens.txt"), resolved.tokens());
            assertNull(resolved.joiner());
            assertNull(resolved.vad());
        }
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

    private PluginConfig.SpeechToText settings(ModelManager.Preset preset) throws Exception {
        var settings = Fakes.config(folder).speechToText();
        settings = Fakes.with(settings, "model", preset.id);
        settings = Fakes.with(settings, "autoDownload", false);
        return Fakes.with(settings, "vad", Fakes.with(settings.vad(), "enabled", false));
    }
}
