package dev.voicewarden.engine;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;

public final class SileroVadGate implements SpeechGate {

    public static final ModelArtifact ARTIFACT = ModelArtifact.sherpaReleaseFile(
            "silero-vad",
            "silero_vad.onnx",
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            ModelKind.SILERO_VAD);

    static final int LEAD_WINDOWS = 3;

    private final VadModelConfig config;
    private final SileroVadSettings settings;
    private final Deque<Vad> idle = new ArrayDeque<>();
    private int created;
    private boolean closed;

    private SileroVadGate(VadModelConfig config, SileroVadSettings settings) {
        this.config = config;
        this.settings = settings;
    }

    public static SileroVadGate open(Path modelDir, SileroVadSettings settings) {
        Objects.requireNonNull(modelDir, "modelDir");
        Objects.requireNonNull(settings, "settings");
        if (!(ModelFiles.discover(ModelKind.SILERO_VAD, modelDir) instanceof ModelFiles.SileroVad files)) {
            throw new ModelLayoutException("Model directory " + modelDir + " does not hold a Silero VAD model");
        }
        requirePinnedModel(files.model());
        SileroVadGate gate = new SileroVadGate(config(files.model()), settings);
        gate.giveBack(gate.newVad());
        return gate;
    }

    private static void requirePinnedModel(Path model) {
        String actual;
        try {
            actual = Sha256.of(model);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read Silero VAD model " + model, e);
        }
        String expected = ARTIFACT.expectedSha256();
        if (!expected.equalsIgnoreCase(actual)) {
            throw new ModelLayoutException("Silero VAD model " + model + " has SHA-256 " + actual + " but " + expected
                    + " is required. onnxruntime aborts the whole engine on a damaged model, so it is not loaded. "
                    + "Delete it so the engine downloads a fresh copy.");
        }
    }

    static VadModelConfig config(Path model) {
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(model.toString())
                .setWindowSize(SpeechSegmenter.WINDOW_SAMPLES)
                .build();
        return VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(SpeechRecognizerEngine.SAMPLE_RATE)
                .setNumThreads(1)
                .setProvider("cpu")
                .setDebug(false)
                .build();
    }

    @Override
    public boolean hasSpeech(float[] samples) {
        if (samples.length == 0) {
            return false;
        }
        return !SpeechSegmenter.segments(probabilities(samples), settings.threshold(), settings.minSpeechWindows(),
                settings.minSilenceWindows()).isEmpty();
    }

    float[] probabilities(float[] samples) {
        Vad vad = borrow();
        try {
            vad.reset();
            int window = SpeechSegmenter.WINDOW_SAMPLES;
            int audioWindows = (samples.length + window - 1) / window;
            float[] probabilities = new float[LEAD_WINDOWS + audioWindows];
            float[] frame = new float[window];
            for (int i = 0; i < LEAD_WINDOWS; i++) {
                probabilities[i] = vad.compute(frame);
            }
            for (int w = 0; w < audioWindows; w++) {
                int from = w * window;
                int count = Math.min(window, samples.length - from);
                System.arraycopy(samples, from, frame, 0, count);
                if (count < window) {
                    Arrays.fill(frame, count, window, 0f);
                }
                probabilities[LEAD_WINDOWS + w] = vad.compute(frame);
            }
            return probabilities;
        } finally {
            giveBack(vad);
        }
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "Silero VAD (threshold %.2f, min speech %d ms, min silence %d ms)",
                settings.threshold(), settings.minSpeechDurationMs(), settings.minSilenceDurationMs());
    }

    synchronized int createdInstances() {
        return created;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            idle.forEach(Vad::release);
            idle.clear();
        }
    }

    private Vad borrow() {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Silero VAD gate is closed");
            }
            Vad pooled = idle.pollFirst();
            if (pooled != null) {
                return pooled;
            }
        }
        return newVad();
    }

    private Vad newVad() {
        Vad vad = new Vad(config);
        synchronized (this) {
            created++;
        }
        return vad;
    }

    private void giveBack(Vad vad) {
        synchronized (this) {
            if (!closed) {
                idle.addFirst(vad);
                return;
            }
        }
        vad.release();
    }
}
