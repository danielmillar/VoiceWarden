package dev.voicewarden.core.audio;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class PlayerAudioSession implements AutoCloseable {

    public static final long NO_UTTERANCE = 0L;
    public static final int PACKET_TIMEOUT_FLOOR_MS = 200;
    public static final int POST_ROLL_MS = 160;
    private static final int POST_ROLL_SAMPLES_16K = POST_ROLL_MS * AudioFormat.SAMPLES_PER_MS_16K;

    private final UUID playerId;
    private final OpusAudioDecoder decoder;
    private final AudioEventSink sink;
    private final LongSupplier utteranceIds;
    private final PipelineCounters counters;
    private final FirDecimator3To1 decimator = new FirDecimator3To1();
    private final short[] decodeBuffer = new short[AudioFormat.MAX_FRAME_SAMPLES_48K];
    private final short[] decimatedBuffer = new short[AudioFormat.MAX_FRAME_SAMPLES_16K];
    private final AcousticAbuseDetector abuse;

    private AudioConfig config;
    private VoiceActivityDetector vad;
    private short[] utterance;
    private int utteranceLength;
    private boolean utteranceOpen;
    private int voicedStart;
    private int lastActiveEnd;
    private int liveEmitted;
    private long currentUtteranceId = NO_UTTERANCE;
    private int liveChunkIndex;
    private long droppedUtteranceId = NO_UTTERANCE;
    private boolean discardUntilSilence;
    private long lastFrameMs;

    public PlayerAudioSession(UUID playerId, AudioConfig config, OpusAudioDecoder decoder, AudioEventSink sink) {
        this(playerId, config, decoder, sink, new AtomicLong()::incrementAndGet, new PipelineCounters());
    }

    PlayerAudioSession(
            UUID playerId,
            AudioConfig config,
            OpusAudioDecoder decoder,
            AudioEventSink sink,
            LongSupplier utteranceIds,
            PipelineCounters counters) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.config = Objects.requireNonNull(config, "config");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.utteranceIds = utteranceIds;
        this.counters = counters;
        this.vad = new VoiceActivityDetector(config);
        this.abuse = new AcousticAbuseDetector(playerId, config.micSpam());
    }

    public void onFrame(PooledOpusFrame frame) {
        lastFrameMs = frame.timestampMs();
        switch (frame.kind()) {
            case OPUS -> processOpus(frame.opusData(), 0, frame.opusLength());
            case PCM_48K -> processPcm48k(frame.pcm48kData(), 0, frame.pcmLength());
            case SPEAKING_STOPPED -> speakingStopped();
        }
    }

    public void processOpus(byte[] packet, int offset, int length) {
        int samples = decoder.decode(packet, offset, length, decodeBuffer);
        if (samples == OpusAudioDecoder.DECODE_FAILED) {
            counters.decodeErrors.increment();
            samples = decoder.conceal(decodeBuffer);
        }
        if (samples > 0) {
            processPcm48k(decodeBuffer, 0, samples);
        }
    }

    public void processPcm48k(short[] pcm48k, int offset, int length) {
        int position = offset;
        int remaining = length;
        while (remaining > 0) {
            int run = Math.min(remaining, AudioFormat.MAX_FRAME_SAMPLES_48K);
            int produced = decimator.decimate(pcm48k, position, run, decimatedBuffer, 0);
            if (produced > 0) {
                processPcm16k(decimatedBuffer, 0, produced);
            }
            position += run;
            remaining -= run;
        }
    }

    public void processPcm16k(short[] pcm16k, int offset, int length) {
        Optional<AcousticAbuseEvent> abused = abuse.process(pcm16k, offset, length, lastFrameMs);
        if (abused.isPresent()) {
            sink.onAcousticAbuse(abused.get());
        }
        switch (vad.process(pcm16k, offset, length)) {
            case SILENCE -> {
            }
            case SPEECH_START -> startUtterance();
            case SPEECH -> {
                if (!discardUntilSilence) {
                    append(pcm16k, offset, length, vad.lastFrameActive());
                }
            }
            case SPEECH_END -> {
                finishUtterance(false);
                discardUntilSilence = false;
            }
        }
    }

    public void speakingStopped() {
        if (!discardUntilSilence) {
            finishUtterance(false);
        }
        discardUntilSilence = false;
        utteranceOpen = false;
        abuse.reset();
        vad.reset();
        decimator.reset();
        decoder.reset();
    }

    public boolean checkIdle(long nowMs) {
        if (!utteranceOpen && !vad.inSpeech()) {
            return false;
        }
        if (nowMs - lastFrameMs < Math.max(config.silenceThresholdMs(), PACKET_TIMEOUT_FLOOR_MS)) {
            return false;
        }
        speakingStopped();
        return true;
    }

    public void dropUtterance(long utteranceId) {
        droppedUtteranceId = utteranceId;
        if (utteranceId == NO_UTTERANCE || utteranceId != currentUtteranceId) {
            return;
        }
        if (!utteranceOpen && !vad.inSpeech()) {
            return;
        }
        discardUntilSilence = true;
        if (utteranceOpen) {
            utteranceOpen = false;
            resetUtteranceBuffer();
            counters.utterancesDropped.increment();
        }
    }

    public void updateConfig(AudioConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig");
        speakingStopped();
        if (utterance != null && utterance.length != newConfig.maxUtteranceSamples16k()) {
            utterance = null;
        }
        config = newConfig;
        vad = new VoiceActivityDetector(newConfig);
        abuse.reconfigure(newConfig.micSpam());
    }

    public UUID playerId() {
        return playerId;
    }

    public long currentUtteranceId() {
        return currentUtteranceId;
    }

    public long droppedUtteranceId() {
        return droppedUtteranceId;
    }

    public int liveChunkIndex() {
        return liveChunkIndex;
    }

    public boolean utteranceOpen() {
        return utteranceOpen;
    }

    public AudioConfig config() {
        return config;
    }

    @Override
    public void close() {
        speakingStopped();
        decoder.close();
    }

    private void startUtterance() {
        openUtterance();
        int preRoll = vad.copyPreRoll(utterance, 0);
        utteranceLength = preRoll;
        voicedStart = Math.max(0, preRoll - vad.onsetLength());
        lastActiveEnd = preRoll;
        emitLiveChunks();
        if (utteranceLength == utterance.length) {
            finishUtterance(true);
        }
    }

    private void openUtterance() {
        int capacity = config.maxUtteranceSamples16k();
        if (utterance == null || utterance.length != capacity) {
            utterance = new short[capacity];
        }
        utteranceOpen = true;
        currentUtteranceId = utteranceIds.getAsLong();
        resetUtteranceBuffer();
    }

    private void append(short[] pcm, int offset, int length, boolean active) {
        int position = offset;
        int remaining = length;
        while (remaining > 0) {
            if (!utteranceOpen) {
                if (!active) {
                    return;
                }
                openUtterance();
            }
            int run = Math.min(remaining, utterance.length - utteranceLength);
            System.arraycopy(pcm, position, utterance, utteranceLength, run);
            utteranceLength += run;
            if (active) {
                lastActiveEnd = utteranceLength;
            }
            emitLiveChunks();
            position += run;
            remaining -= run;
            if (utteranceLength == utterance.length) {
                finishUtterance(true);
            }
        }
    }

    private void emitLiveChunks() {
        if (!config.liveDetection()) {
            return;
        }
        int chunk = config.liveChunkSamples16k();
        while (utteranceLength - liveEmitted >= chunk) {
            short[] pcm = Arrays.copyOfRange(utterance, liveEmitted, liveEmitted + chunk);
            liveEmitted += chunk;
            sink.onLiveChunk(new LiveAudioChunk(playerId, currentUtteranceId, liveChunkIndex++, pcm, false));
        }
    }

    private void finishUtterance(boolean capped) {
        if (!utteranceOpen) {
            return;
        }
        utteranceOpen = false;
        if (config.liveDetection()) {
            short[] tail = Arrays.copyOfRange(utterance, liveEmitted, utteranceLength);
            sink.onLiveChunk(new LiveAudioChunk(playerId, currentUtteranceId, liveChunkIndex++, tail, true));
        }
        int voicedEnd = capped ? utteranceLength : lastActiveEnd;
        int voiced = voicedEnd - voicedStart;
        if (voicedEnd > 0 && voiced >= config.minAudioSamples16k()) {
            int end = capped ? utteranceLength : Math.min(utteranceLength, lastActiveEnd + POST_ROLL_SAMPLES_16K);
            short[] pcm = Arrays.copyOf(utterance, end);
            sink.onFinalUtterance(new FinalUtterance(playerId, currentUtteranceId, pcm, AudioFormat.durationMs16k(end)));
        } else {
            counters.utterancesDiscardedTooShort.increment();
        }
        resetUtteranceBuffer();
    }

    private void resetUtteranceBuffer() {
        utteranceLength = 0;
        voicedStart = 0;
        lastActiveEnd = 0;
        liveEmitted = 0;
        liveChunkIndex = 0;
    }
}
