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

    /**
     * Models published by the sherpa-onnx project on Hugging Face, pinned by commit, size and SHA-256.
     * Always pin a commit: upstream re-uploads files in place, so a branch name serves bytes that no longer match.
     */
    public enum Preset {
        PARAKEET_TDT_V2("parakeet-tdt-v2", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
                "NVIDIA Parakeet TDT 0.6B v2 (English, int8)", "84b478d541cdbf0c296a7ab5f1e20a6f8f998114", List.of(
                new ModelFile("encoder.int8.onnx", 652_282_309L, "92a10bd9c23936ea0566c29f963bc57c2a5df4261b6a8921c39abea66557ca8b"),
                new ModelFile("decoder.int8.onnx", 7_257_753L, "4d097bae3bac5c96018d5293ba39f1b657296102b142affda40378f38b020f41"),
                new ModelFile("joiner.int8.onnx", 1_739_080L, "77a29f3e5ac7d37074e0af465391b80f314015d5b97f0f90c6c7767f1d56f428"),
                new ModelFile("tokens.txt", 9_384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"))),
        PARAKEET_TDT_V3("parakeet-tdt-v3", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
                "NVIDIA Parakeet TDT 0.6B v3 (25 European languages, int8)", "ed5d745623472f97cfc2e6c2916ee248798fc5a8", List.of(
                new ModelFile("encoder.int8.onnx", 652_282_294L, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
                new ModelFile("decoder.int8.onnx", 11_845_274L, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
                new ModelFile("joiner.int8.onnx", 6_355_277L, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
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
                new ModelFile("turbo-tokens.txt", 816_730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"))),
        /** Second opinion for {@code speech-to-text.double-check}; not selectable as {@code speech-to-text.model}. */
        MOONSHINE_BASE_EN("moonshine-base-en", "csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
                "Moonshine v2 Base (English, quantized)", "8f4d6c58c03d40bcea40043bb7120a878f2bbef6", List.of(
                new ModelFile("encoder_model.ort", 31_326_816L, "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf"),
                new ModelFile("decoder_model_merged.ort", 109_424_400L, "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d"),
                new ModelFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049")));

        final String id;
        final String repository;
        final String description;
        final String revision;
        final List<ModelFile> files;

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
        Preset preset = Preset.byId(settings.model()).filter(p -> p != Preset.MOONSHINE_BASE_EN).orElseThrow(() -> new IOException(
                "Unknown speech-to-text.model '" + settings.model()
                        + "'. Use parakeet-tdt-v2, parakeet-tdt-v3, whisper-small-en, whisper-turbo or custom."));
        return download(preset, models, settings.autoDownload(), downloader, vad);
    }

    /** The second model for {@code speech-to-text.double-check}. It runs on clips the first model already accepted: no VAD. */
    public static ResolvedModel resolveDoubleCheck(PluginConfig.SpeechToText settings, Path dataFolder, Downloader downloader)
            throws IOException, InterruptedException {
        return download(Preset.MOONSHINE_BASE_EN, dataFolder.resolve("models"), settings.autoDownload(), downloader, null);
    }

    private static ResolvedModel download(Preset preset, Path models, boolean autoDownload, Downloader downloader,
                                          @Nullable Path vad) throws IOException, InterruptedException {
        Path dir = models.resolve(preset.id);
        for (ModelFile file : preset.files) {
            ensure(downloader, dir.resolve(file.name()), file, preset.url(file), autoDownload, preset.id + "/" + file.name());
        }
        return layout(preset, dir, vad);
    }

    static ResolvedModel layout(Preset preset, Path dir, @Nullable Path vad) {
        if (preset == Preset.MOONSHINE_BASE_EN) {
            return new ResolvedModel(preset.description, "moonshine", dir.resolve("encoder_model.ort"),
                    dir.resolve("decoder_model_merged.ort"), null, dir.resolve("tokens.txt"), vad);
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

    /**
     * One file the engine needs, as found on disk. {@code actualSize} is -1 when missing; {@code expectedSize} is -1
     * for a custom model, which has no pin.
     */
    public record FileState(String label, Path path, long expectedSize, long actualSize) {
        public boolean present() {
            return actualSize >= 0;
        }

        public boolean matchesPin() {
            return present() && (expectedSize < 0 || actualSize == expectedSize);
        }
    }

    /**
     * Read-only listing of every file {@link #resolve} and {@link #resolveDoubleCheck} would use, with sizes only:
     * hashing a 600 MB model is left to the load, which always verifies. Never downloads or deletes.
     */
    public static List<FileState> inspect(PluginConfig.SpeechToText settings, Path dataFolder) {
        Path models = dataFolder.resolve("models");
        List<FileState> files = new java.util.ArrayList<>();
        if (settings.vad().enabled()) {
            files.add(state(SILERO_VAD.name(), models.resolve(SILERO_VAD.name()), SILERO_VAD.size()));
        }
        if (settings.model().equals("custom")) {
            PluginConfig.CustomModel c = settings.custom();
            files.add(state("custom encoder", configured(dataFolder, c.encoder()), -1));
            files.add(state("custom decoder", configured(dataFolder, c.decoder()), -1));
            if (!c.modelType().equals("whisper")) {
                files.add(state("custom joiner", configured(dataFolder, c.joiner()), -1));
            }
            files.add(state("custom tokens", configured(dataFolder, c.tokens()), -1));
        } else {
            Preset.byId(settings.model()).filter(p -> p != Preset.MOONSHINE_BASE_EN)
                    .ifPresent(p -> addPreset(files, p, models));
        }
        if (settings.doubleCheck()) {
            addPreset(files, Preset.MOONSHINE_BASE_EN, models);
        }
        return List.copyOf(files);
    }

    private static void addPreset(List<FileState> files, Preset preset, Path models) {
        for (ModelFile file : preset.files) {
            files.add(state(preset.id + "/" + file.name(), models.resolve(preset.id).resolve(file.name()), file.size()));
        }
    }

    private static FileState state(String label, Path path, long expected) {
        long actual;
        try {
            actual = Files.isRegularFile(path) ? Files.size(path) : -1;
        } catch (IOException e) {
            actual = -1;
        }
        return new FileState(label, path, expected, actual);
    }

    private static Path configured(Path dataFolder, String configured) {
        Path p = Path.of(configured);
        return (p.isAbsolute() ? p : dataFolder.resolve(p)).toAbsolutePath().normalize();
    }

    private static void ensure(Downloader downloader, Path path, ModelFile file, URI url, boolean autoDownload, String label)
            throws IOException, InterruptedException {
        if (matches(path, file)) {
            return;
        }
        if (!autoDownload) {
            throw new IOException(label + " is missing, incomplete or outdated at " + path.toAbsolutePath()
                    + ". Enable speech-to-text.auto-download or download it manually from " + url);
        }
        Files.deleteIfExists(path);
        downloader.download(url, path, file.size(), file.sha256(), label);
    }

    /** Same size alone is not enough: upstream re-exports can keep a file's size while changing its contents. */
    static boolean matches(Path path, ModelFile file) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
            return false;
        }
        return file.sha256() == null || file.sha256().equalsIgnoreCase(Downloader.sha256(path));
    }

    private static Path existing(Path dataFolder, String configured, String what) throws IOException {
        Path resolved = configured(dataFolder, configured);
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("speech-to-text.custom." + what + " not found: " + resolved);
        }
        return resolved;
    }
}
