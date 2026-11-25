package dev.voicewarden.engine;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import dev.voicewarden.api.ConfirmationState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SherpaOnnxSpeechEngine implements SpeechRecognizerEngine {

    static final int FEATURE_DIM = 80;
    static final int ONLINE_LEAD_PADDING_SAMPLES = SAMPLE_RATE * 3 / 10;
    static final int TAIL_PADDING_SAMPLES = SAMPLE_RATE * 6 / 5;
    static final int OFFLINE_LEAD_PADDING_SAMPLES = SAMPLE_RATE * 3 / 20;
    static final int OFFLINE_TAIL_PADDING_SAMPLES = SAMPLE_RATE * 2 / 5;

    private static final Logger LOG = LoggerFactory.getLogger(SherpaOnnxSpeechEngine.class);
    private static final String DECODING_METHOD = "greedy_search";
    private static final float[] ONLINE_LEAD_PADDING = new float[ONLINE_LEAD_PADDING_SAMPLES];
    private static final float[] TAIL_PADDING = new float[TAIL_PADDING_SAMPLES];

    private final EngineProfile profile;
    private final OnlineRecognizer online;
    private final OfflineRecognizer offline;
    private final OfflineRecognizer verifier;
    private final DoubleCheckEvaluator evaluator;
    private final SpeechGate gate;
    private final Map<UUID, LiveSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;

    private SherpaOnnxSpeechEngine(
            EngineProfile profile,
            OnlineRecognizer online,
            OfflineRecognizer offline,
            OfflineRecognizer verifier,
            DoubleCheckEvaluator evaluator,
            SpeechGate gate) {
        this.profile = profile;
        this.online = online;
        this.offline = offline;
        this.verifier = verifier;
        this.evaluator = evaluator;
        this.gate = gate;
    }

    public static SherpaOnnxSpeechEngine create(
            EngineProfile profile,
            Path primaryDir,
            Path verifierDir,
            int threads,
            DoubleCheckEvaluator evaluator) {
        return create(profile, primaryDir, verifierDir, threads, evaluator, SpeechGate.OPEN);
    }

    public static SherpaOnnxSpeechEngine create(
            EngineProfile profile,
            Path primaryDir,
            Path verifierDir,
            int threads,
            DoubleCheckEvaluator evaluator,
            SpeechGate gate) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(primaryDir, "primaryDir");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(gate, "gate");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        ModelArtifact primary = profile.primary();
        ModelFiles primaryFiles = ModelFiles.discover(primary.kind(), primaryDir);
        ModelFiles verifierFiles = null;
        ModelArtifact verifierArtifact = profile.verifier().orElse(null);
        if (verifierDir != null) {
            if (verifierArtifact == null) {
                throw new IllegalArgumentException("Profile " + profile + " has no Double Check verifier model");
            }
            verifierFiles = ModelFiles.discover(verifierArtifact.kind(), verifierDir);
        }
        OnlineRecognizer online = null;
        OfflineRecognizer offline = null;
        OfflineRecognizer verifier = null;
        try {
            if (primary.kind().streaming()) {
                online = new OnlineRecognizer(onlineConfig(primaryFiles, primary.modelType(), threads));
            } else {
                offline = new OfflineRecognizer(offlineConfig(primaryFiles, primary.modelType(), threads));
            }
            if (verifierFiles != null) {
                verifier = new OfflineRecognizer(offlineConfig(verifierFiles, verifierArtifact.modelType(), threads));
            }
        } catch (RuntimeException | Error e) {
            releaseAll(online, offline, verifier);
            throw e;
        }
        LOG.info("Speech engine ready: profile {}, primary {}, verifier {}, {} threads, speech gate {}", profile,
                primary.id(), verifier == null ? "none" : verifierArtifact.id(), threads, gate.describe());
        return new SherpaOnnxSpeechEngine(profile, online, offline, verifier, evaluator, gate);
    }

    static OnlineRecognizerConfig onlineConfig(ModelFiles files, String modelType, int threads) {
        if (!(files instanceof ModelFiles.Transducer t)) {
            throw new IllegalArgumentException("Streaming models must be transducers, got " + files);
        }
        OnlineModelConfig.Builder model = OnlineModelConfig.builder()
                .setTransducer(OnlineTransducerModelConfig.builder()
                        .setEncoder(t.encoder().toString())
                        .setDecoder(t.decoder().toString())
                        .setJoiner(t.joiner().toString())
                        .build())
                .setTokens(t.tokens().toString())
                .setNumThreads(threads)
                .setDebug(false)
                .setProvider("cpu");
        if (!modelType.isEmpty()) {
            model.setModelType(modelType);
        }
        return OnlineRecognizerConfig.builder()
                .setFeatureConfig(featureConfig())
                .setOnlineModelConfig(model.build())
                .setDecodingMethod(DECODING_METHOD)
                .setEnableEndpoint(false)
                .build();
    }

    static OfflineRecognizerConfig offlineConfig(ModelFiles files, String modelType, int threads) {
        OfflineModelConfig.Builder model = OfflineModelConfig.builder()
                .setNumThreads(threads)
                .setDebug(false)
                .setProvider("cpu");
        switch (files) {
            case ModelFiles.Transducer t -> model.setTokens(t.tokens().toString())
                    .setTransducer(OfflineTransducerModelConfig.builder()
                            .setEncoder(t.encoder().toString())
                            .setDecoder(t.decoder().toString())
                            .setJoiner(t.joiner().toString())
                            .build());
            case ModelFiles.Whisper w -> model.setTokens(w.tokens().toString())
                    .setWhisper(OfflineWhisperModelConfig.builder()
                            .setEncoder(w.encoder().toString())
                            .setDecoder(w.decoder().toString())
                            .setLanguage("")
                            .setTask("transcribe")
                            .build());
            case ModelFiles.MoonshineV1 m -> model.setTokens(m.tokens().toString())
                    .setMoonshine(OfflineMoonshineModelConfig.builder()
                            .setPreprocessor(m.preprocessor().toString())
                            .setEncoder(m.encoder().toString())
                            .setUncachedDecoder(m.uncachedDecoder().toString())
                            .setCachedDecoder(m.cachedDecoder().toString())
                            .build());
            case ModelFiles.MoonshineV2 m -> model.setTokens(m.tokens().toString())
                    .setMoonshine(OfflineMoonshineModelConfig.builder()
                            .setEncoder(m.encoder().toString())
                            .setMergedDecoder(m.mergedDecoder().toString())
                            .build());
            case ModelFiles.SileroVad v -> throw new IllegalArgumentException(
                    "A Silero VAD model cannot be used for speech recognition: " + v.model());
        }
        if (!modelType.isEmpty()) {
            model.setModelType(modelType);
        }
        return OfflineRecognizerConfig.builder()
                .setFeatureConfig(featureConfig())
                .setOfflineModelConfig(model.build())
                .setDecodingMethod(DECODING_METHOD)
                .build();
    }

    private static FeatureConfig featureConfig() {
        return FeatureConfig.builder().setSampleRate(SAMPLE_RATE).setFeatureDim(FEATURE_DIM).build();
    }

    public EngineProfile profile() {
        return profile;
    }

    @Override
    public boolean supportsStreaming() {
        return online != null;
    }

    @Override
    public boolean hasDoubleCheckVerifier() {
        return verifier != null;
    }

    @Override
    public int activeStreams() {
        return sessions.size();
    }

    @Override
    public RecognitionOutput feedLiveChunk(UUID playerId, long utteranceId, short[] pcm16k, boolean endOfUtterance) {
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            if (online == null) {
                return RecognitionOutput.unchecked("", profile.defaultLanguage());
            }
            while (true) {
                LiveSession current = sessions.get(playerId);
                if (current != null && utteranceId < current.utteranceId) {
                    return RecognitionOutput.unchecked("", profile.defaultLanguage());
                }
                LiveSession session = sessions.compute(playerId, (id, existing) -> {
                    if (existing != null && existing.utteranceId == utteranceId) {
                        return existing;
                    }
                    if (existing != null) {
                        existing.release();
                    }
                    return new LiveSession(utteranceId, createOnlineStream());
                });
                synchronized (session) {
                    if (session.released) {
                        continue;
                    }
                    session.touch();
                    if (pcm16k.length > 0) {
                        session.stream.acceptWaveform(SpeechRecognizerEngine.toFloat(pcm16k), SAMPLE_RATE);
                    }
                    if (endOfUtterance) {
                        session.stream.acceptWaveform(TAIL_PADDING, SAMPLE_RATE);
                        session.stream.inputFinished();
                    }
                    decodeReady(session.stream);
                    String text = online.getResult(session.stream).getText().trim();
                    if (endOfUtterance) {
                        sessions.remove(playerId, session);
                        session.release();
                    }
                    return RecognitionOutput.unchecked(text, profile.defaultLanguage());
                }
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public RecognitionOutput transcribeFinal(UUID playerId, long utteranceId, short[] pcm16k, boolean requestDoubleCheck) {
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            float[] samples = SpeechRecognizerEngine.toFloat(pcm16k);
            return gated(gate, samples, speech -> {
                Decoded primary = online != null ? decodeOnline(speech) : decodeOffline(offline, speech);
                if (!requestDoubleCheck || verifier == null || primary.text().isBlank()) {
                    return RecognitionOutput.unchecked(primary.text(), primary.language());
                }
                Decoded check = decodeOffline(verifier, speech);
                ConfirmationState state = evaluator.evaluate(primary.text(), check.text());
                return new RecognitionOutput(primary.text(), primary.language(), state, check.text());
            }, () -> RecognitionOutput.unchecked("", profile.defaultLanguage()));
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public VerificationOutput verifyUtterance(UUID playerId, long utteranceId, String primaryTranscript, short[] pcm16k) {
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            if (verifier == null) {
                return VerificationOutput.unchecked(profile.defaultLanguage());
            }
            return gated(gate, SpeechRecognizerEngine.toFloat(pcm16k), speech -> {
                Decoded check = decodeOffline(verifier, speech);
                return new VerificationOutput(evaluator.evaluate(primaryTranscript, check.text()), check.text(),
                        check.language());
            }, () -> new VerificationOutput(evaluator.evaluate(primaryTranscript, ""), "", profile.defaultLanguage()));
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void dropUtterance(UUID playerId, long utteranceId) {
        lifecycle.readLock().lock();
        try {
            if (closed) {
                return;
            }
            LiveSession session = sessions.get(playerId);
            if (session != null && session.utteranceId == utteranceId && sessions.remove(playerId, session)) {
                session.release();
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void clearAllStreams() {
        lifecycle.readLock().lock();
        try {
            if (closed) {
                return;
            }
            for (Map.Entry<UUID, LiveSession> entry : sessions.entrySet()) {
                LiveSession session = entry.getValue();
                if (sessions.remove(entry.getKey(), session)) {
                    session.release();
                }
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public int evictIdleStreams(Duration maxIdle) {
        lifecycle.readLock().lock();
        try {
            if (closed) {
                return 0;
            }
            long cutoff = System.nanoTime() - maxIdle.toNanos();
            int evicted = 0;
            for (Map.Entry<UUID, LiveSession> entry : sessions.entrySet()) {
                LiveSession session = entry.getValue();
                if (session.lastUsedNanos - cutoff < 0 && sessions.remove(entry.getKey(), session)) {
                    session.release();
                    evicted++;
                }
            }
            return evicted;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            sessions.values().forEach(LiveSession::release);
            sessions.clear();
            releaseAll(online, offline, verifier);
            gate.close();
            LOG.info("Speech engine released native recognizers");
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    static <T> T gated(SpeechGate gate, float[] samples, Function<float[], T> decode, Supplier<T> noSpeech) {
        boolean speech;
        try {
            speech = gate.hasSpeech(samples);
        } catch (RuntimeException e) {
            LOG.warn("Speech gate {} failed; transcribing the utterance anyway: {}", gate.describe(), e.toString());
            speech = true;
        }
        return speech ? decode.apply(samples) : noSpeech.get();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Speech engine is closed");
        }
    }

    private void decodeReady(OnlineStream stream) {
        while (online.isReady(stream)) {
            online.decode(stream);
        }
    }

    private OnlineStream createOnlineStream() {
        OnlineStream stream = online.createStream();
        String language = profile.defaultLanguage();
        if (!language.isEmpty() && !"auto".equals(language)) {
            stream.setOption("language", language);
        }
        stream.acceptWaveform(ONLINE_LEAD_PADDING, SAMPLE_RATE);
        return stream;
    }

    private Decoded decodeOnline(float[] samples) {
        if (samples.length == 0) {
            return new Decoded("", profile.defaultLanguage());
        }
        OnlineStream stream = createOnlineStream();
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            stream.acceptWaveform(TAIL_PADDING, SAMPLE_RATE);
            stream.inputFinished();
            decodeReady(stream);
            return new Decoded(online.getResult(stream).getText().trim(), profile.defaultLanguage());
        } finally {
            stream.release();
        }
    }

    private Decoded decodeOffline(OfflineRecognizer recognizer, float[] samples) {
        if (samples.length == 0) {
            return new Decoded("", profile.defaultLanguage());
        }
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(padOffline(samples), SAMPLE_RATE);
            recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);
            return new Decoded(result.getText().trim(), language(result.getLang()));
        } finally {
            stream.release();
        }
    }

    static float[] padOffline(float[] samples) {
        float[] padded = new float[OFFLINE_LEAD_PADDING_SAMPLES + samples.length + OFFLINE_TAIL_PADDING_SAMPLES];
        System.arraycopy(samples, 0, padded, OFFLINE_LEAD_PADDING_SAMPLES, samples.length);
        return padded;
    }

    private String language(String reported) {
        if (reported == null) {
            return profile.defaultLanguage();
        }
        String cleaned = reported.replace("<|", "").replace("|>", "").trim().toLowerCase(Locale.ROOT);
        return cleaned.isEmpty() ? profile.defaultLanguage() : cleaned;
    }

    private static void releaseAll(OnlineRecognizer online, OfflineRecognizer offline, OfflineRecognizer verifier) {
        if (online != null) {
            online.release();
        }
        if (offline != null) {
            offline.release();
        }
        if (verifier != null) {
            verifier.release();
        }
    }

    private record Decoded(String text, String language) {
    }

    private static final class LiveSession {
        final long utteranceId;
        final OnlineStream stream;
        volatile long lastUsedNanos;
        volatile boolean released;

        LiveSession(long utteranceId, OnlineStream stream) {
            this.utteranceId = utteranceId;
            this.stream = stream;
            this.lastUsedNanos = System.nanoTime();
        }

        void touch() {
            lastUsedNanos = System.nanoTime();
        }

        synchronized void release() {
            if (!released) {
                released = true;
                stream.release();
            }
        }
    }
}
