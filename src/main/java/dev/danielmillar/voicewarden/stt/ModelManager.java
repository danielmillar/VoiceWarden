package dev.danielmillar.voicewarden.stt;

import dev.danielmillar.voicewarden.config.PluginConfig;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Resolves the speech model files, downloading and verifying presets on first use. Blocking: IO threads only. */
public final class ModelManager {

    record ModelFile(String name, long size, @Nullable String sha256) {
    }

    /** Models published by the sherpa-onnx project on Hugging Face, pinned by size and SHA-256. */
    public enum Preset {
        PARAKEET_TDT_V2("parakeet-tdt-v2", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
                "NVIDIA Parakeet TDT 0.6B v2 (English, int8)", List.of(
                new ModelFile("encoder.int8.onnx", 652_184_296L, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
                new ModelFile("decoder.int8.onnx", 7_257_753L, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                new ModelFile("joiner.int8.onnx", 1_739_080L, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
                new ModelFile("tokens.txt", 9_384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"))),
        PARAKEET_TDT_V3("parakeet-tdt-v3", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
                "NVIDIA Parakeet TDT 0.6B v3 (25 European languages, int8)", List.of(
                new ModelFile("encoder.int8.onnx", 652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                new ModelFile("decoder.int8.onnx", 11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                new ModelFile("joiner.int8.onnx", 6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                new ModelFile("tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"))),
        WHISPER_SMALL_EN("whisper-small-en", "csukuangfj/sherpa-onnx-whisper-small.en",
                "Whisper Small (English, int8)", "d9533f69affd85061aee349af7fea5cb2996dbbe", List.of(
                new ModelFile("small.en-encoder.int8.onnx", 112_442_483L, "8bdac288f369aa94ee2194059238c465ed82ea9d47ee8fa4a8c0a891873e462f"),
                new ModelFile("small.en-decoder.int8.onnx", 262_223_042L, "710ccf890e10f3faa15f51ec346081a2723c9f3adb6e4da81c6573a5a6f877fb"),
                new ModelFile("small.en-tokens.txt", 835_554L, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"))),
        WHISPER_TURBO("whisper-turbo", "csukuangfj/sherpa-onnx-whisper-turbo",
                "Whisper Large-v3 Turbo (English transcription, int8)", "2ca6ff69fc878651b770880507669577ac41c2ff", List.of(
                new ModelFile("turbo-encoder.int8.onnx", 674_716_297L, "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b"),
                new ModelFile("turbo-decoder.int8.onnx", 361_080_764L, "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547"),
                new ModelFile("turbo-tokens.txt", 816_730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126")));

        final String id;
        final String repository;
        final String description;
        final String revision;
        final List<ModelFile> files;

        Preset(String id, String repository, String description, List<ModelFile> files) {
            this(id, repository, description, "main", files);
        }

        Preset(String id, String repository, String description, String revision, List<ModelFile> files) {
            this.id = id;
            this.repository = repository;
            this.description = description;
            this.revision = revision;
            this.files = files;
        }

        public static Optional<Preset> byId(String id) {
            return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst();
        }

        URI url(ModelFile file) {
            return URI.create("https://huggingface.co/" + repository + "/resolve/" + revision + "/" + file.name());
        }
    }

    private static final ModelFile SILERO_VAD = new ModelFile("silero_vad.onnx", 643_854L,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6");
    private static final URI SILERO_VAD_URL =
            URI.create("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx");

    /** Absolute paths of everything the engine needs. */
    public record ResolvedModel(String description, String modelType, Path encoder, Path decoder, @Nullable Path joiner,
                                Path tokens, @Nullable Path vad) {
    }

    private ModelManager() {
    }

    public static ResolvedModel resolve(PluginConfig.SpeechToText settings, Path dataFolder, Downloader downloader)
            throws IOException, InterruptedException {
        Path models = dataFolder.resolve("models");
        Path vad = null;
        if (settings.vad().enabled()) {
            vad = models.resolve(SILERO_VAD.name());
            ensure(downloader, vad, SILERO_VAD, SILERO_VAD_URL, settings.autoDownload(), "Silero VAD model");
        }
        if (settings.model().equals("custom")) {
            PluginConfig.CustomModel c = settings.custom();
            Path encoder = existing(dataFolder, c.encoder(), "encoder");
            Path decoder = existing(dataFolder, c.decoder(), "decoder");
            Path joiner = c.modelType().equals("whisper") ? null : existing(dataFolder, c.joiner(), "joiner");
            Path tokens = existing(dataFolder, c.tokens(), "tokens");
            return new ResolvedModel("custom " + c.modelType() + " model", c.modelType(), encoder, decoder, joiner, tokens, vad);
        }
        Preset preset = Preset.byId(settings.model()).orElseThrow(() -> new IOException(
                "Unknown speech-to-text.model '" + settings.model()
                        + "'. Use parakeet-tdt-v2, parakeet-tdt-v3, whisper-small-en, whisper-turbo or custom."));
        Path dir = models.resolve(preset.id);
        for (ModelFile file : preset.files) {
            ensure(downloader, dir.resolve(file.name()), file, preset.url(file), settings.autoDownload(), preset.id + "/" + file.name());
        }
        if (preset == Preset.WHISPER_SMALL_EN || preset == Preset.WHISPER_TURBO) {
            String prefix = preset == Preset.WHISPER_SMALL_EN ? "small.en" : "turbo";
            return new ResolvedModel(preset.description, "whisper",
                    dir.resolve(prefix + "-encoder.int8.onnx"), dir.resolve(prefix + "-decoder.int8.onnx"),
                    null, dir.resolve(prefix + "-tokens.txt"), vad);
        }
        return new ResolvedModel(preset.description, "nemo_transducer",
                dir.resolve("encoder.int8.onnx"), dir.resolve("decoder.int8.onnx"),
                dir.resolve("joiner.int8.onnx"), dir.resolve("tokens.txt"), vad);
    }

    private static void ensure(Downloader downloader, Path path, ModelFile file, URI url, boolean autoDownload, String label)
            throws IOException, InterruptedException {
        if (Files.isRegularFile(path) && Files.size(path) == file.size()) {
            return;
        }
        if (!autoDownload) {
            throw new IOException(label + " is missing or incomplete at " + path.toAbsolutePath()
                    + ". Enable speech-to-text.auto-download or download it manually from " + url);
        }
        Files.deleteIfExists(path);
        downloader.download(url, path, file.size(), file.sha256(), label);
    }

    private static Path existing(Path dataFolder, String configured, String what) throws IOException {
        Path p = Path.of(configured);
        Path resolved = (p.isAbsolute() ? p : dataFolder.resolve(p)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("speech-to-text.custom." + what + " not found: " + resolved);
        }
        return resolved;
    }
}
