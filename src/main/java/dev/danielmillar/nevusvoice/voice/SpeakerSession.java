package dev.danielmillar.nevusvoice.voice;

import dev.danielmillar.nevusvoice.audio.AudioLevels;
import dev.danielmillar.nevusvoice.audio.AudioSegment;
import dev.danielmillar.nevusvoice.audio.Decimator;
import dev.danielmillar.nevusvoice.audio.PcmBuffer;
import dev.danielmillar.nevusvoice.config.PluginConfig;
import dev.danielmillar.nevusvoice.moderation.SpeakerLocation;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-player audio state. Simple Voice Chat's packet thread only appends Opus frames to a lock-free inbox
 * ({@link #offer}); a decode-pool thread drains it ({@link #drain}). At most one drain runs at a time per session
 * (guarded by {@link #scheduled}), so all decoder/buffer state below is confined to one thread at a time and needs no
 * locks. Control markers (flush, release, close) travel through the same inbox to preserve ordering.
 */
final class SpeakerSession {

    private static final byte[] FLUSH = new byte[0];
    private static final byte[] RELEASE = new byte[0];
    private static final byte[] CLOSE = new byte[0];
    /** Frames handled per drain before yielding the thread to other speakers (50 frames = 1 s of audio). */
    private static final int MAX_FRAMES_PER_DRAIN = 50;
    private static final int RATE = Decimator.OUTPUT_RATE;
    private static final int LEVEL_WINDOW = RATE / 50; // 20 ms

    final UUID id;
    final String name;
    private final AudioIngestService owner;

    // ── shared between packet thread, decode thread and timer ───────────────
    /** Opus frames ({@code byte[]}), control markers (empty {@code byte[]}) and {@link Burst} starts, in arrival order. */
    private final ConcurrentLinkedQueue<Object> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inboxSize = new AtomicInteger();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    final AtomicBoolean flushRequested = new AtomicBoolean();
    final AtomicBoolean releaseRequested = new AtomicBoolean();
    volatile boolean bypass;
    volatile long lastPacketNanos;
    volatile boolean segmentOpen;
    volatile boolean decoderAllocated;
    private volatile boolean closed;
    // Packet-thread confined: an explicit terminator starts a fresh burst even within the silence timeout.
    private boolean burstEnded = true;

    // ── decode-thread confined ───────────────────────────────────────────────
    private @Nullable OpusDecoder decoder;
    private final Decimator decimator = new Decimator();
    private final PcmBuffer buffer = new PcmBuffer(RATE);
    private long segmentStartMillis;
    private @Nullable SpeakerLocation segmentLocation;
    private boolean segmentWhispering;
    private boolean segmentSplit;
    private @Nullable Burst pendingBurst;

    /** Start of a talk burst, captured on the packet thread so metadata stays attached to the right audio. */
    private record Burst(@Nullable SpeakerLocation location, boolean whispering, long startMillis) {
    }

    /** A timer decision is valid only while no newer packet has arrived. */
    private record SilenceFlush(long lastPacketNanos) {
    }

    SpeakerSession(UUID id, String name, boolean bypass, AudioIngestService owner) {
        this.id = id;
        this.name = name;
        this.bypass = bypass;
        this.owner = owner;
    }

    /**
     * Called on Simple Voice Chat's packet thread for every microphone packet. Must stay O(1) and never block.
     *
     * @param location only invoked at the start of a talk burst
     */
    void offer(byte[] opus, boolean whispering, LocationSupplier location, long nowNanos, PluginConfig.Audio settings) {
        if (closed) {
            return;
        }
        if (opus.length == 0) {
            // Explicit end-of-transmission packet: the player released push-to-talk / stopped talking.
            // Reserve one slot for the terminator even when the audio backlog is full.
            if (inboxSize.get() > settings.maxBufferedFrames()) {
                owner.metrics().packetsDropped.increment();
                return;
            }
            enqueue(FLUSH);
            burstEnded = true;
            return;
        }
        boolean burstStart = burstEnded || nowNanos - lastPacketNanos > settings.silenceTimeout().toNanos();
        lastPacketNanos = nowNanos;
        if (inboxSize.get() >= settings.maxBufferedFrames()) {
            owner.metrics().packetsDropped.increment();
            return;
        }
        if (burstStart) {
            // Metadata only (one per talk burst), so it does not count against the audio backlog cap.
            inbox.offer(new Burst(location.get(), whispering, System.currentTimeMillis()));
        }
        burstEnded = false;
        // Copy: other plugins may replace the packet's array after we return.
        enqueue(opus.clone());
    }

    /** Stop capture: finish all queued audio unconditionally. */
    void requestFlush() {
        enqueue(FLUSH);
    }

    /** Timer thread: discard a stale silence decision if the speaker has resumed. */
    void requestSilenceFlush(long lastPacketNanos) {
        if (flushRequested.compareAndSet(false, true)) {
            enqueue(new SilenceFlush(lastPacketNanos));
        }
    }

    /** Timer thread: free the native decoder of an idle speaker. */
    void requestRelease() {
        if (releaseRequested.compareAndSet(false, true)) {
            enqueue(RELEASE);
        }
    }

    /** Disconnect: finish any pending utterance (it may contain the last thing they said) and free resources. */
    void close() {
        enqueue(CLOSE);
    }

    private void enqueue(Object item) {
        inboxSize.incrementAndGet();
        inbox.offer(item);
        schedule();
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) {
            try {
                owner.decodeExecutor().execute(this::drain);
            } catch (RejectedExecutionException e) {
                scheduled.set(false); // shutting down
            }
        }
    }

    // ── decode thread ────────────────────────────────────────────────────────

    private void drain() {
        int handled = 0;
        try {
            Object item;
            while ((handled < MAX_FRAMES_PER_DRAIN || owner.decodeExecutor().isShutdown())
                    && (item = inbox.poll()) != null) {
                if (item instanceof Burst burst) {
                    finishSegment(); // a new burst means the previous utterance is over, even if no flush arrived yet
                    pendingBurst = burst;
                    continue;
                }
                inboxSize.decrementAndGet();
                handled++;
                if (item instanceof SilenceFlush silence) {
                    if (silence.lastPacketNanos() == lastPacketNanos) {
                        finishSegment();
                    } else {
                        flushRequested.set(false);
                    }
                } else if (item instanceof byte[] bytes) {
                    if (bytes.length == 0) {
                        handleMarker(bytes);
                    } else if (!closed) {
                        decodeFrame(bytes);
                    }
                }
            }
        } catch (Throwable t) {
            owner.onSessionError(this, t);
            resetAfterError();
        } finally {
            scheduled.set(false);
            if (!inbox.isEmpty()) {
                schedule();
            }
        }
    }

    private void handleMarker(byte[] marker) {
        if (marker == RELEASE) {
            releaseRequested.set(false);
            if (!segmentOpen) {
                releaseDecoder();
            }
        } else if (marker == CLOSE) {
            finishSegment();
            releaseDecoder();
            closed = true;
            inbox.clear();
        } else {
            finishSegment();
        }
    }

    private void decodeFrame(byte[] opus) {
        OpusDecoder d = decoder;
        if (d == null || d.isClosed()) {
            d = owner.createDecoder();
            if (d == null) {
                return;
            }
            decoder = d;
            decoderAllocated = true;
        }
        short[] pcm;
        try {
            pcm = d.decode(opus);
        } catch (RuntimeException e) {
            owner.metrics().decodeErrors.increment();
            return;
        }
        if (pcm == null || pcm.length == 0) {
            return;
        }
        if (!segmentOpen) {
            startSegment();
        }
        decimator.process(pcm, pcm.length, buffer);
        int max = (int) (owner.settings().maxUtterance().toMillis() * RATE / 1000);
        if (buffer.size() >= max) {
            splitAtQuietestPoint();
        }
    }

    private void startSegment() {
        Burst burst = pendingBurst;
        pendingBurst = null;
        segmentOpen = true;
        segmentStartMillis = burst != null ? burst.startMillis() : System.currentTimeMillis();
        segmentLocation = burst != null ? burst.location() : null;
        segmentWhispering = burst != null && burst.whispering();
        segmentSplit = false;
        buffer.clear();
        decimator.reset();
    }

    private void finishSegment() {
        flushRequested.set(false);
        if (!segmentOpen) {
            return;
        }
        segmentOpen = false;
        emit(0, buffer.size(), segmentStartMillis);
        buffer.clear();
        decimator.reset();
        OpusDecoder d = decoder;
        if (d != null && !d.isClosed()) {
            d.resetState();
        }
    }

    /**
     * Long continuous speech (e.g. held push-to-talk): emit what we have so moderation isn't delayed, cutting at the
     * quietest 20 ms in the last two seconds so a word is unlikely to be split in half.
     */
    private void splitAtQuietestPoint() {
        int size = buffer.size();
        short[] pcm = buffer.array();
        int minimum = (int) (owner.settings().minUtterance().toMillis() * RATE / 1000);
        int searchFrom = Math.max(Math.max(LEVEL_WINDOW, size - 2 * RATE), minimum - LEVEL_WINDOW / 2);
        int cut = size;
        double quietest = Double.MAX_VALUE;
        for (int start = searchFrom; start + LEVEL_WINDOW <= size; start += LEVEL_WINDOW / 2) {
            double level = AudioLevels.meanSquare(pcm, start, start + LEVEL_WINDOW);
            if (level < quietest) {
                quietest = level;
                cut = start + LEVEL_WINDOW / 2;
            }
        }
        emit(0, cut, segmentStartMillis);
        segmentSplit = true;
        segmentStartMillis += cut * 1000L / RATE;
        buffer.discardBefore(cut);
    }

    private void emit(int from, int to, long startMillis) {
        PluginConfig.Audio settings = owner.settings();
        int samples = to - from;
        if (samples <= 0) {
            return;
        }
        if (!segmentSplit && samples * 1000L / RATE < settings.minUtterance().toMillis()) {
            owner.metrics().utterancesTooShort.increment();
            return;
        }
        short[] pcm = buffer.array();
        double loudest = AudioLevels.loudestFrameMeanSquare(pcm, from, to, LEVEL_WINDOW);
        if (AudioLevels.toDbfs(loudest) < settings.minLevelDbfs()) {
            owner.metrics().utterancesTooQuiet.increment();
            return;
        }
        owner.metrics().utterances.increment();
        owner.emit(new AudioSegment(id, name, buffer.copy(from, to), RATE, Instant.ofEpochMilli(startMillis),
                System.nanoTime(), segmentLocation, segmentWhispering));
    }

    private void releaseDecoder() {
        OpusDecoder d = decoder;
        decoder = null;
        decoderAllocated = false;
        if (d != null && !d.isClosed()) {
            d.close();
        }
        buffer.clearAndTrim();
    }

    private void resetAfterError() {
        segmentOpen = false;
        flushRequested.set(false);
        buffer.clear();
        decimator.reset();
        try {
            releaseDecoder();
        } catch (Throwable ignored) {
            decoder = null;
        }
    }

    @FunctionalInterface
    interface LocationSupplier {
        @Nullable SpeakerLocation get();
    }
}
