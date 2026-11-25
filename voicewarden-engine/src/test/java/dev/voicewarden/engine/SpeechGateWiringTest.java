package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpeechGateWiringTest {

    private static final ModelArtifact UNPINNED = new ModelArtifact("silero-vad", "silero_vad.onnx",
            URI.create("http://127.0.0.1:9/silero_vad.onnx"), null, ModelKind.SILERO_VAD, "",
            ModelArtifact.Packaging.SINGLE_FILE);

    @TempDir
    Path tmp;

    private static final class FakeGate implements SpeechGate {
        private final Predicate<float[]> verdict;
        final AtomicInteger calls = new AtomicInteger();

        FakeGate(Predicate<float[]> verdict) {
            this.verdict = verdict;
        }

        @Override
        public boolean hasSpeech(float[] samples) {
            calls.incrementAndGet();
            return verdict.test(samples);
        }

        @Override
        public String describe() {
            return "fake";
        }

        @Override
        public void close() {
        }
    }

    @Test
    void noSpeechSkipsRecognitionEntirely() {
        FakeGate gate = new FakeGate(samples -> false);
        List<float[]> decoded = new ArrayList<>();
        String text = SherpaOnnxSpeechEngine.gated(gate, new float[16_000], samples -> {
            decoded.add(samples);
            return "hallucination";
        }, () -> "");
        assertEquals("", text);
        assertEquals(0, decoded.size(), "recognition must not run when the gate hears no speech");
        assertEquals(1, gate.calls.get());
    }

    @Test
    void speechPassesTheWholeUtteranceUntrimmed() {
        float[] samples = new float[] {0f, 1f, 2f, 3f};
        FakeGate gate = new FakeGate(s -> true);
        assertSame(samples, SherpaOnnxSpeechEngine.gated(gate, samples, s -> s, () -> null),
                "a quiet word the gate missed must still reach recognition when later speech opens the gate");
    }

    @Test
    void openGateTranscribesEverything() {
        float[] samples = new float[] {1f, 2f};
        assertSame(samples, SherpaOnnxSpeechEngine.gated(SpeechGate.OPEN, samples, s -> s, () -> null));
        assertEquals("off", SpeechGate.OPEN.describe());
    }

    @Test
    void aFailingGateFallsBackToTheWholeUtterance() {
        float[] samples = new float[] {1f, 2f, 3f};
        FakeGate gate = new FakeGate(s -> {
            throw new IllegalStateException("native failure");
        });
        assertSame(samples, SherpaOnnxSpeechEngine.gated(gate, samples, s -> s, () -> null));
    }

    @Test
    void disabledSileroVadNeverTouchesTheDownloader() {
        ModelDownloader unreachable = new ModelDownloader(tmp.resolve("models"), true, VerifiedDownloader.createDefault());
        SpeechGate gate = VoiceWardenEngineMain.speechGate(SileroVadSettings.DISABLED, unreachable, dir -> {
            throw new AssertionError("must not open a gate when Silero VAD is off");
        });
        assertSame(SpeechGate.OPEN, gate);
        assertTrue(Files.notExists(tmp.resolve("models")));
    }

    @Test
    void missingModelWithoutAutoDownloadFallsBackWithoutFailingStartup() {
        ModelDownloader offline = new ModelDownloader(tmp.resolve("models"), false, VerifiedDownloader.createDefault());
        SpeechGate gate = VoiceWardenEngineMain.speechGate(SileroVadSettings.DEFAULTS, offline, dir -> {
            throw new AssertionError("must not open a gate without a model");
        });
        assertSame(SpeechGate.OPEN, gate);
    }

    @Test
    void failedDownloadFallsBack() {
        ModelArtifact unreachable = new ModelArtifact("silero-vad", "silero_vad.onnx",
                URI.create("http://127.0.0.1:9/silero_vad.onnx"), null, ModelKind.SILERO_VAD, "",
                ModelArtifact.Packaging.SINGLE_FILE);
        ModelDownloader online = new ModelDownloader(tmp.resolve("models"), true, VerifiedDownloader.createDefault());
        assertSame(SpeechGate.OPEN, VoiceWardenEngineMain.speechGate(SileroVadSettings.DEFAULTS, unreachable, online,
                dir -> new FakeGate(s -> false)));
    }

    @Test
    void damagedPinnedModelIsNeverHandedToNativeCode() throws IOException {
        Path models = installedModel();
        ModelDownloader offline = new ModelDownloader(models, false, VerifiedDownloader.createDefault());
        assertSame(SpeechGate.OPEN, VoiceWardenEngineMain.speechGate(SileroVadSettings.DEFAULTS, offline, dir -> {
            throw new AssertionError("a file that fails the pinned SHA-256 must not reach onnxruntime");
        }));
        ModelLayoutException e = assertThrows(ModelLayoutException.class,
                () -> SileroVadGate.open(SileroVadGate.ARTIFACT.directoryIn(models), SileroVadSettings.DEFAULTS));
        assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
    }

    @Test
    void modelThatFailsToLoadFallsBack() throws IOException {
        Path models = installedModel();
        ModelDownloader offline = new ModelDownloader(models, false, VerifiedDownloader.createDefault());
        assertSame(SpeechGate.OPEN, VoiceWardenEngineMain.speechGate(SileroVadSettings.DEFAULTS, UNPINNED, offline,
                dir -> {
                    throw new UnsatisfiedLinkError("no onnxruntime");
                }));
        assertSame(SpeechGate.OPEN, VoiceWardenEngineMain.speechGate(SileroVadSettings.DEFAULTS, UNPINNED, offline,
                dir -> {
                    throw new IllegalStateException("corrupt model");
                }));
    }

    @Test
    void installedModelOpensTheGate() throws IOException {
        Path models = installedModel();
        ModelDownloader offline = new ModelDownloader(models, false, VerifiedDownloader.createDefault());
        List<Path> opened = new ArrayList<>();
        FakeGate fake = new FakeGate(s -> false);
        SpeechGate gate = VoiceWardenEngineMain.speechGate(SileroVadSettings.DEFAULTS, UNPINNED, offline, dir -> {
            opened.add(dir);
            return fake;
        });
        assertSame(fake, gate);
        assertEquals(List.of(models.resolve(SileroVadGate.ARTIFACT.id())), opened);
    }

    private Path installedModel() throws IOException {
        Path models = tmp.resolve("models");
        Path dir = Files.createDirectories(SileroVadGate.ARTIFACT.directoryIn(models));
        Files.write(dir.resolve("silero_vad.onnx"), new byte[] {1, 2, 3});
        return models;
    }
}
