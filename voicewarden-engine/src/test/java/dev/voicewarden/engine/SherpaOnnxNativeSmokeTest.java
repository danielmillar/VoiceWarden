package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.VersionInfo;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SherpaOnnxNativeSmokeTest {

    @TempDir
    Path tmp;

    @Test
    void nativeLibraryLoadsOnThisMachine() throws IOException {
        NativePlatform platform = NativePlatform.current();
        assertTrue(NativeLibraryLoader.bundledOnClasspath(platform),
                "the test runtime classpath should carry the " + platform.artifactName() + " jar");
        NativeLibraryLoader loader = new NativeLibraryLoader(tmp, false, VerifiedDownloader.createDefault(), platform);
        String version = loader.ensureLoaded();
        assertEquals(NativeLibraryLoader.SHERPA_VERSION, version);
        assertEquals(version, VersionInfo.getVersion());
        assertFalse(VersionInfo.getOnnxruntimeVersion().isBlank());
    }

    @Test
    void discoversKrokoStreamingLayout() throws IOException {
        Path dir = tmp.resolve("kroko");
        TestArchives.touch(dir, "README.md", "decoder.onnx", "encoder.onnx", "tokens.txt", "joiner.onnx");
        ModelFiles.Transducer files = assertInstanceOf(ModelFiles.Transducer.class,
                ModelFiles.discover(EngineProfile.ENGLISH.primary().kind(), dir));
        assertEquals(dir.resolve("encoder.onnx"), files.encoder());
        assertEquals(dir.resolve("decoder.onnx"), files.decoder());
        assertEquals(dir.resolve("joiner.onnx"), files.joiner());
        assertEquals(dir.resolve("tokens.txt"), files.tokens());
    }

    @Test
    void discoversParakeetV2AccurateLayout() throws IOException {
        Path dir = tmp.resolve("parakeet-v2");
        TestArchives.touch(dir, "tokens.txt", "joiner.int8.onnx", "decoder.int8.onnx", "encoder.int8.onnx");
        ModelFiles.Transducer files = assertInstanceOf(ModelFiles.Transducer.class,
                ModelFiles.discover(EngineProfile.ENGLISH_ACCURATE.primary().kind(), dir));
        assertEquals(dir.resolve("encoder.int8.onnx"), files.encoder());
        assertEquals(dir.resolve("joiner.int8.onnx"), files.joiner());
    }

    @Test
    void discoversParakeetLayoutAndPrefersInt8() throws IOException {
        Path dir = tmp.resolve("parakeet");
        TestArchives.touch(dir, "tokens.txt", "joiner.int8.onnx", "decoder.int8.onnx", "encoder.int8.onnx",
                "encoder.onnx", "decoder.onnx", "joiner.onnx");
        ModelFiles.Transducer files = assertInstanceOf(ModelFiles.Transducer.class,
                ModelFiles.discover(EngineProfile.MULTILANG.primary().kind(), dir));
        assertEquals(dir.resolve("encoder.int8.onnx"), files.encoder());
        assertEquals(dir.resolve("decoder.int8.onnx"), files.decoder());
        assertEquals(dir.resolve("joiner.int8.onnx"), files.joiner());
    }

    @Test
    void discoversWhisperTurboLayout() throws IOException {
        Path dir = tmp.resolve("whisper");
        TestArchives.touch(dir, "turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt");
        ModelFiles.Whisper files = assertInstanceOf(ModelFiles.Whisper.class,
                ModelFiles.discover(EngineProfile.MULTILANG_GLOBAL.primary().kind(), dir));
        assertEquals(dir.resolve("turbo-encoder.int8.onnx"), files.encoder());
        assertEquals(dir.resolve("turbo-decoder.int8.onnx"), files.decoder());
        assertEquals(dir.resolve("turbo-tokens.txt"), files.tokens());
    }

    @Test
    void discoversMoonshineV2OrtLayout() throws IOException {
        Path dir = tmp.resolve("moonshine2");
        TestArchives.touch(dir, "decoder_model_merged.ort", "LICENSE", "tokens.txt", "encoder_model.ort");
        ModelFiles.MoonshineV2 files = assertInstanceOf(ModelFiles.MoonshineV2.class,
                ModelFiles.discover(ModelKind.OFFLINE_MOONSHINE, dir));
        assertEquals(dir.resolve("encoder_model.ort"), files.encoder());
        assertEquals(dir.resolve("decoder_model_merged.ort"), files.mergedDecoder());
    }

    @Test
    void discoversMoonshineV1Layout() throws IOException {
        Path dir = tmp.resolve("moonshine1");
        TestArchives.touch(dir, "preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx",
                "cached_decode.int8.onnx", "tokens.txt");
        ModelFiles.MoonshineV1 files = assertInstanceOf(ModelFiles.MoonshineV1.class,
                ModelFiles.discover(ModelKind.OFFLINE_MOONSHINE, dir));
        assertEquals(dir.resolve("preprocess.onnx"), files.preprocessor());
        assertEquals(dir.resolve("encode.int8.onnx"), files.encoder());
        assertEquals(dir.resolve("uncached_decode.int8.onnx"), files.uncachedDecoder());
        assertEquals(dir.resolve("cached_decode.int8.onnx"), files.cachedDecoder());
    }

    @Test
    void missingFilesAreReportedWithTheDirectoryListing() throws IOException {
        Path dir = tmp.resolve("broken");
        TestArchives.touch(dir, "encoder.onnx", "tokens.txt");
        ModelLayoutException e = assertThrows(ModelLayoutException.class,
                () -> ModelFiles.discover(ModelKind.ONLINE_TRANSDUCER, dir));
        assertTrue(e.getMessage().contains("decoder"), e.getMessage());
        assertTrue(e.getMessage().contains("encoder.onnx, tokens.txt"), e.getMessage());
    }

    @Test
    void recognizerConfigsCarryDiscoveredPaths() throws IOException {
        Path kroko = tmp.resolve("k");
        TestArchives.touch(kroko, "encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt");
        OnlineRecognizerConfig online = SherpaOnnxSpeechEngine.onlineConfig(
                ModelFiles.discover(ModelKind.ONLINE_TRANSDUCER, kroko), "", 2);
        assertEquals(kroko.resolve("encoder.onnx").toString(), online.getModelConfig().getTransducer().getEncoder());
        assertEquals(2, online.getModelConfig().getNumThreads());

        Path moonshine = tmp.resolve("m");
        TestArchives.touch(moonshine, "encoder_model.ort", "decoder_model_merged.ort", "tokens.txt");
        OfflineRecognizerConfig offline = SherpaOnnxSpeechEngine.offlineConfig(
                ModelFiles.discover(ModelKind.OFFLINE_MOONSHINE, moonshine), "", 1);
        assertEquals(moonshine.resolve("decoder_model_merged.ort").toString(),
                offline.getModelConfig().getMoonshine().getMergedDecoder());

        Path parakeet = tmp.resolve("p");
        TestArchives.touch(parakeet, "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt");
        OfflineRecognizerConfig nemo = SherpaOnnxSpeechEngine.offlineConfig(
                ModelFiles.discover(ModelKind.OFFLINE_TRANSDUCER, parakeet), "nemo_transducer", 1);
        assertEquals("nemo_transducer", nemo.getModelConfig().getModelType());
    }

    @Test
    void pcmIsNormalizedToUnitRange() {
        float[] f = SpeechRecognizerEngine.toFloat(new short[] {0, Short.MAX_VALUE, Short.MIN_VALUE, 16384});
        assertEquals(0f, f[0]);
        assertEquals(32767 / 32768f, f[1]);
        assertEquals(-1f, f[2]);
        assertEquals(0.5f, f[3]);
    }

    @Test
    void offlinePaddingWrapsSamplesWithLeadingAndTrailingSilence() {
        float[] raw = {0.25f, -0.5f, 0.75f};
        float[] padded = SherpaOnnxSpeechEngine.padOffline(raw);
        assertEquals(
                SherpaOnnxSpeechEngine.OFFLINE_LEAD_PADDING_SAMPLES + raw.length + SherpaOnnxSpeechEngine.OFFLINE_TAIL_PADDING_SAMPLES,
                padded.length);
        assertEquals(0f, padded[0]);
        assertEquals(0f, padded[SherpaOnnxSpeechEngine.OFFLINE_LEAD_PADDING_SAMPLES - 1]);
        assertEquals(0.25f, padded[SherpaOnnxSpeechEngine.OFFLINE_LEAD_PADDING_SAMPLES]);
        assertEquals(-0.5f, padded[SherpaOnnxSpeechEngine.OFFLINE_LEAD_PADDING_SAMPLES + 1]);
        assertEquals(0.75f, padded[SherpaOnnxSpeechEngine.OFFLINE_LEAD_PADDING_SAMPLES + 2]);
        assertEquals(0f, padded[padded.length - 1]);
        assertTrue(SherpaOnnxSpeechEngine.ONLINE_LEAD_PADDING_SAMPLES >= SpeechRecognizerEngine.SAMPLE_RATE / 4);
        assertTrue(SherpaOnnxSpeechEngine.TAIL_PADDING_SAMPLES >= SpeechRecognizerEngine.SAMPLE_RATE * 4 / 5);
    }
}
