package dev.voicewarden.core.audio;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ShardedAudioPipeline implements AutoCloseable {

    public static final String THREAD_NAME_PREFIX = "voicewarden-audio-worker-";
    static final long IDLE_POLL_MS = 5L;
    static final long IDLE_SWEEP_INTERVAL_MS = 50L;
    static final long SESSION_IDLE_EVICT_MS = 300_000L;
    static final long CLOSE_JOIN_MS = 2_000L;

    private final Worker[] workers;
    private final OpusFramePool pool;
    private final PipelineCounters counters = new PipelineCounters();
    private final AtomicInteger activeSessions = new AtomicInteger();
    private final AtomicLong utteranceIds = new AtomicLong();
    private final Supplier<OpusAudioDecoder> decoderFactory;
    private final AudioEventSink sink;
    private final int maxOpusBytes;
    private final int maxPcmSamples = AudioFormat.MAX_FRAME_SAMPLES_48K;
    private volatile AudioConfig config;
    private volatile boolean closed;

    public ShardedAudioPipeline(AudioConfig config, AudioEventSink sink) {
        this(config, sink, ConcentusOpusAudioDecoder::new);
    }

    public ShardedAudioPipeline(AudioConfig config, AudioEventSink sink, Supplier<OpusAudioDecoder> decoderFactory) {
        this.config = Objects.requireNonNull(config, "config");
        this.decoderFactory = Objects.requireNonNull(decoderFactory, "decoderFactory");
        this.sink = new CountingSink(Objects.requireNonNull(sink, "sink"), counters);
        this.maxOpusBytes = config.maxOpusFrameBytes();
        this.pool = new OpusFramePool(config.framePoolCapacity(), maxOpusBytes);
        this.workers = new Worker[config.workerThreads()];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Worker(i, config.workerQueueCapacity());
        }
        for (Worker worker : workers) {
            worker.thread.start();
        }
    }

    public boolean submitOpus(UUID playerId, byte[] opusBytes, int offset, int length) {
        if (closed) {
            return false;
        }
        if (playerId == null || opusBytes == null || length <= 0 || length > maxOpusBytes
                || offset < 0 || offset > opusBytes.length - length) {
            counters.framesRejectedInvalid.increment();
            return false;
        }
        PooledOpusFrame frame = pool.acquire();
        if (frame == null) {
            return false;
        }
        frame.fillOpus(playerId, opusBytes, offset, length, System.currentTimeMillis());
        return enqueue(frame);
    }

    public boolean submitPcm48k(UUID playerId, short[] pcm48k, int offset, int length) {
        if (closed) {
            return false;
        }
        if (playerId == null || pcm48k == null || length <= 0 || length > maxPcmSamples
                || offset < 0 || offset > pcm48k.length - length) {
            counters.framesRejectedInvalid.increment();
            return false;
        }
        PooledOpusFrame frame = pool.acquire();
        if (frame == null) {
            return false;
        }
        frame.fillPcm48k(playerId, pcm48k, offset, length, System.currentTimeMillis());
        return enqueue(frame);
    }

    public void markSpeakingStopped(UUID playerId) {
        if (closed || playerId == null) {
            return;
        }
        PooledOpusFrame frame = pool.acquire();
        if (frame == null) {
            workerFor(playerId).control(worker -> worker.speakingStopped(playerId));
            return;
        }
        frame.fillSpeakingStopped(playerId, System.currentTimeMillis());
        enqueue(frame);
    }

    public void dropUtterance(UUID playerId, long utteranceId) {
        if (closed || playerId == null) {
            return;
        }
        workerFor(playerId).control(worker -> worker.dropUtterance(playerId, utteranceId));
    }

    public void removePlayer(UUID playerId) {
        if (closed || playerId == null) {
            return;
        }
        workerFor(playerId).control(worker -> worker.removePlayer(playerId));
    }

    public void updateConfig(AudioConfig newConfig) {
        config = Objects.requireNonNull(newConfig, "newConfig");
    }

    public AudioConfig config() {
        return config;
    }

    public int workerCount() {
        return workers.length;
    }

    public int shardOf(UUID playerId) {
        return Math.floorMod(playerId.hashCode(), workers.length);
    }

    public AudioPipelineStats stats() {
        int queued = 0;
        for (Worker worker : workers) {
            queued += worker.queue.size();
        }
        return new AudioPipelineStats(
                counters.framesSubmitted.sum(),
                counters.framesProcessed.sum(),
                counters.framesDroppedQueueFull.sum(),
                pool.exhaustedCount(),
                counters.liveChunksEmitted.sum(),
                counters.finalUtterancesEmitted.sum(),
                activeSessions.get(),
                counters.framesRejectedInvalid.sum(),
                counters.utterancesDiscardedTooShort.sum(),
                counters.utterancesDropped.sum(),
                counters.decodeErrors.sum(),
                counters.processingErrors.sum(),
                queued,
                counters.acousticAbuseEmitted.sum());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Worker worker : workers) {
            try {
                worker.thread.join(CLOSE_JOIN_MS);
                if (worker.thread.isAlive()) {
                    worker.thread.interrupt();
                    worker.thread.join(CLOSE_JOIN_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    Thread workerThread(int index) {
        return workers[index].thread;
    }

    private Worker workerFor(UUID playerId) {
        return workers[shardOf(playerId)];
    }

    private boolean enqueue(PooledOpusFrame frame) {
        ArrayBlockingQueue<PooledOpusFrame> queue = workerFor(frame.playerId()).queue;
        counters.framesSubmitted.increment();
        while (!queue.offer(frame)) {
            PooledOpusFrame oldest = queue.poll();
            if (oldest != null) {
                counters.framesDroppedQueueFull.increment();
                pool.release(oldest);
            }
        }
        return true;
    }

    private final class Worker implements Runnable {

        private final ArrayBlockingQueue<PooledOpusFrame> queue;
        private final ConcurrentLinkedQueue<Consumer<Worker>> controls = new ConcurrentLinkedQueue<>();
        private final Map<UUID, PlayerAudioSession> sessions = new HashMap<>();
        private final Map<UUID, Long> lastActivityMs = new HashMap<>();
        private final Thread thread;
        private AudioConfig appliedConfig;
        private long lastSweepMs;

        Worker(int index, int queueCapacity) {
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.thread = new Thread(this, THREAD_NAME_PREFIX + index);
            this.thread.setDaemon(true);
        }

        void control(Consumer<Worker> command) {
            controls.offer(command);
        }

        @Override
        public void run() {
            appliedConfig = config;
            boolean interrupted = false;
            try {
                while (!closed) {
                    runControls();
                    applyConfigIfChanged();
                    PooledOpusFrame frame = queue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        process(frame);
                    }
                    sweepIdle();
                }
            } catch (InterruptedException e) {
                interrupted = true;
            } finally {
                Thread.interrupted();
                shutdown();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void runControls() {
            Consumer<Worker> command;
            while ((command = controls.poll()) != null) {
                try {
                    command.accept(this);
                } catch (RuntimeException e) {
                    counters.processingErrors.increment();
                }
            }
        }

        private void applyConfigIfChanged() {
            AudioConfig current = config;
            if (current == appliedConfig) {
                return;
            }
            appliedConfig = current;
            for (PlayerAudioSession session : sessions.values()) {
                guarded(() -> session.updateConfig(current));
            }
        }

        private void process(PooledOpusFrame frame) {
            try {
                PlayerAudioSession session = sessions.get(frame.playerId());
                if (session == null) {
                    if (frame.kind() == FrameKind.SPEAKING_STOPPED) {
                        return;
                    }
                    session = createSession(frame.playerId());
                }
                session.onFrame(frame);
                lastActivityMs.put(frame.playerId(), frame.timestampMs());
            } catch (RuntimeException e) {
                counters.processingErrors.increment();
            } finally {
                counters.framesProcessed.increment();
                pool.release(frame);
            }
        }

        private PlayerAudioSession createSession(UUID playerId) {
            PlayerAudioSession session = new PlayerAudioSession(
                    playerId, appliedConfig, decoderFactory.get(), sink, utteranceIds::incrementAndGet, counters);
            sessions.put(playerId, session);
            lastActivityMs.put(playerId, System.currentTimeMillis());
            activeSessions.incrementAndGet();
            return session;
        }

        private void sweepIdle() {
            long now = System.currentTimeMillis();
            if (now - lastSweepMs < IDLE_SWEEP_INTERVAL_MS) {
                return;
            }
            lastSweepMs = now;
            PooledOpusFrame head = queue.peek();
            long sweepNow = head != null ? Math.min(now, head.timestampMs()) : now;
            Iterator<Map.Entry<UUID, PlayerAudioSession>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, PlayerAudioSession> entry = it.next();
                PlayerAudioSession session = entry.getValue();
                guarded(() -> session.checkIdle(sweepNow));
                long lastSeen = lastActivityMs.getOrDefault(entry.getKey(), sweepNow);
                if (!session.utteranceOpen() && sweepNow - lastSeen >= SESSION_IDLE_EVICT_MS) {
                    it.remove();
                    lastActivityMs.remove(entry.getKey());
                    activeSessions.decrementAndGet();
                    guarded(session::close);
                }
            }
        }

        void speakingStopped(UUID playerId) {
            drainQueued();
            PlayerAudioSession session = sessions.get(playerId);
            if (session != null) {
                session.speakingStopped();
            }
        }

        void dropUtterance(UUID playerId, long utteranceId) {
            PlayerAudioSession session = sessions.get(playerId);
            if (session != null) {
                session.dropUtterance(utteranceId);
            }
        }

        void removePlayer(UUID playerId) {
            drainQueued();
            lastActivityMs.remove(playerId);
            PlayerAudioSession session = sessions.remove(playerId);
            if (session != null) {
                activeSessions.decrementAndGet();
                guarded(session::close);
            }
        }

        private void drainQueued() {
            int pending = queue.size();
            PooledOpusFrame frame;
            while (pending-- > 0 && (frame = queue.poll()) != null) {
                process(frame);
            }
        }

        private void guarded(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException e) {
                counters.processingErrors.increment();
            }
        }

        private void shutdown() {
            for (PlayerAudioSession session : sessions.values()) {
                guarded(session::close);
            }
            activeSessions.addAndGet(-sessions.size());
            sessions.clear();
            lastActivityMs.clear();
            PooledOpusFrame frame;
            while ((frame = queue.poll()) != null) {
                pool.release(frame);
            }
        }
    }

    private record CountingSink(AudioEventSink delegate, PipelineCounters counters) implements AudioEventSink {

        @Override
        public void onLiveChunk(LiveAudioChunk chunk) {
            counters.liveChunksEmitted.increment();
            try {
                delegate.onLiveChunk(chunk);
            } catch (RuntimeException e) {
                counters.processingErrors.increment();
            }
        }

        @Override
        public void onFinalUtterance(FinalUtterance utterance) {
            counters.finalUtterancesEmitted.increment();
            try {
                delegate.onFinalUtterance(utterance);
            } catch (RuntimeException e) {
                counters.processingErrors.increment();
            }
        }

        @Override
        public void onAcousticAbuse(AcousticAbuseEvent event) {
            counters.acousticAbuseEmitted.increment();
            try {
                delegate.onAcousticAbuse(event);
            } catch (RuntimeException e) {
                counters.processingErrors.increment();
            }
        }
    }
}
