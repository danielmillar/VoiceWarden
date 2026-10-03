package dev.danielmillar.voicesentinel.stt;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import dev.danielmillar.voicesentinel.audio.AudioLevels;
import dev.danielmillar.voicesentinel.audio.AudioSegment;
import dev.danielmillar.voicesentinel.config.PluginConfig;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Embedded speech recognition via sherpa-onnx (ONNX Runtime) running an offline transducer or Whisper model.
 *
 * <p>One {@link OfflineRecognizer} is shared by all workers: sherpa-onnx documents
 * {@code DecodeStreams} as thread-safe. Each worker owns a {@link Worker} with its own stateful Silero VAD instance.
 * Every native object is released explicitly; nothing relies on finalizers.
 */
public final class SherpaOnnxEngine implements AutoCloseable {

    public static final int SAMPLE_RATE = 16_000;
    private static final int VAD_WINDOW = 512;

    private final OfflineRecognizer recognizer;
    private final @Nullable VadModelConfig vadConfig;
    private final int paddingSamples;
    private final boolean trimSilence;
    private final PluginConfig.InputGain inputGain;
    private final String description;

    private SherpaOnnxEngine(OfflineRecognizer recognizer, @Nullable VadModelConfig vadConfig, int paddingSamples,
                             boolean trimSilence, PluginConfig.InputGain inputGain, String description) {
        this.recognizer = recognizer;
        this.vadConfig = vadConfig;
        this.paddingSamples = paddingSamples;
        this.trimSilence = trimSilence;
        this.inputGain = inputGain;
        this.description = description;
    }

    /** Loads the model. Slow (about a second) and allocates native memory: call from a background thread. */
    public static SherpaOnnxEngine load(ModelManager.ResolvedModel model, PluginConfig.SpeechToText settings) {
        var builder = OfflineModelConfig.builder()
                .setTokens(model.tokens().toString())
                .setNumThreads(settings.cpuThreads())
                .setDebug(false)
                .setProvider("cpu")
                .setModelType(model.modelType());
        if (model.modelType().equals("whisper")) {
            builder.setWhisper(OfflineWhisperModelConfig.builder()
                    .setEncoder(model.encoder().toString())
                    .setDecoder(model.decoder().toString())
                    .setLanguage("en")
                    .setTask("transcribe")
                    .build());
        } else {
            builder.setTransducer(OfflineTransducerModelConfig.builder()
                    .setEncoder(model.encoder().toString())
                    .setDecoder(model.decoder().toString())
                    .setJoiner(Objects.requireNonNull(model.joiner(), "Transducer model requires a joiner").toString())
                    .build());
        }
        OfflineModelConfig modelConfig = builder.build();
        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search")
                .build();
        OfflineRecognizer recognizer = new OfflineRecognizer(config);

        VadModelConfig vadConfig = null;
        int padding = 0;
        if (model.vad() != null) {
            PluginConfig.Vad v = settings.vad();
            SileroVadModelConfig silero = SileroVadModelConfig.builder()
                    .setModel(model.vad().toString())
                    .setThreshold(v.threshold())
                    .setMinSilenceDuration(v.minSilence().toMillis() / 1000f)
                    .setMinSpeechDuration(v.minSpeech().toMillis() / 1000f)
                    .setWindowSize(VAD_WINDOW)
                    // Utterances are already capped by audio.max-utterance; never let the VAD split them.
                    .setMaxSpeechDuration(120f)
                    .build();
            vadConfig = VadModelConfig.builder()
                    .setSileroVadModelConfig(silero)
                    .setSampleRate(SAMPLE_RATE)
                    .setNumThreads(1)
                    .setProvider("cpu")
                    .setDebug(false)
                    .build();
            try {
                new Vad(vadConfig).release(); // fail fast on a bad model
            } catch (RuntimeException e) {
                recognizer.release();
                throw e;
            }
            padding = (int) (v.padding().toMillis() * SAMPLE_RATE / 1000);
        }
        return new SherpaOnnxEngine(recognizer, vadConfig, padding, settings.vad().trimSilence(), settings.inputGain(), model.description());
    }

    public String description() {
        return description;
    }

    /** Creates per-thread decoding state. The worker must be closed by the thread that uses it. */
    public Worker newWorker() {
        return new Worker(vadConfig == null ? null : new Vad(vadConfig));
    }

    /**
     * @param text          transcript, or {@code null} when the VAD found no speech (recogniser skipped)
     * @param speechSamples samples actually sent to the recogniser
     */
    public record Result(@Nullable String text, int speechSamples) {
    }

    public final class Worker implements AutoCloseable {

        private final @Nullable Vad vad;

        private Worker(@Nullable Vad vad) {
            this.vad = vad;
        }

        /** Transcribes a batch in one native call. Results are in input order. */
        public List<Result> transcribe(List<AudioSegment> batch) {
            Result[] results = new Result[batch.size()];
            List<OfflineStream> streams = new ArrayList<>(batch.size());
            int[] index = new int[batch.size()];
            int[] lengths = new int[batch.size()];
            try {
                for (int i = 0; i < batch.size(); i++) {
                    AudioSegment segment = batch.get(i);
                    float[] samples = inputGain.enabled()
                            ? AudioLevels.toFloatWithGain(segment.pcm(), SAMPLE_RATE, inputGain.targetDbfs(), inputGain.maxGainDb())
                            : AudioLevels.toFloat(segment.pcm());
                    if (vad != null) {
                        samples = speechOnly(vad, samples);
                        if (samples == null) {
                            results[i] = new Result(null, 0);
                            continue;
                        }
                    }
                    OfflineStream stream = recognizer.createStream();
                    index[streams.size()] = i;
                    lengths[streams.size()] = samples.length;
                    streams.add(stream);
                    stream.acceptWaveform(samples, SAMPLE_RATE);
                }
                if (streams.size() == 1) {
                    recognizer.decode(streams.getFirst());
                } else if (!streams.isEmpty()) {
                    recognizer.decode(streams.toArray(OfflineStream[]::new));
                }
                for (int k = 0; k < streams.size(); k++) {
                    String text = recognizer.getResult(streams.get(k)).getText();
                    results[index[k]] = new Result(text == null ? "" : text.strip(), lengths[k]);
                }
            } finally {
                for (OfflineStream stream : streams) {
                    stream.release();
                }
            }
            return Arrays.asList(results);
        }

        /**
         * Rejects audio with no detected speech. By default the recogniser receives the entire captured utterance:
         * VAD boundaries can miss quiet words even with padding. Trimming is an explicit performance option.
         */
        private float @Nullable [] speechOnly(Vad vad, float[] samples) {
            vad.reset();
            vad.clear();
            for (int offset = 0; offset < samples.length; offset += VAD_WINDOW) {
                // sherpa-onnx aggregates speech state per acceptWaveform call, so use exactly one VAD window.
                // Flush does not process its partial input buffer: zero-fill the final window for detection only.
                vad.acceptWaveform(Arrays.copyOfRange(samples, offset, offset + VAD_WINDOW));
            }
            vad.flush();
            int first = -1;
            int last = -1;
            while (!vad.empty()) {
                SpeechSegment speech = vad.front();
                int start = speech.getStart();
                int stop = start + speech.getSamples().length;
                first = first < 0 ? start : Math.min(first, start);
                last = Math.max(last, stop);
                vad.pop();
            }
            if (first < 0) {
                return null;
            }
            if (!trimSilence) {
                return samples;
            }
            int from = Math.max(0, first - paddingSamples);
            int to = Math.min(samples.length, last + paddingSamples);
            return from == 0 && to == samples.length ? samples : Arrays.copyOfRange(samples, from, to);
        }

        @Override
        public void close() {
            if (vad != null) {
                vad.release();
            }
        }
    }

    /** Frees the model. Only call once no worker can be inside {@link Worker#transcribe}. */
    @Override
    public void close() {
        recognizer.release();
    }
}
