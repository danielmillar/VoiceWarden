package dev.voicewarden.engine;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.core.ipc.IpcMessage;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FairTranscriptionScheduler implements AutoCloseable {

    public static final int DEFAULT_MAX_QUEUED_PER_PLAYER_LANE = 32;
    public static final Duration WORKER_JOIN_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOG = LoggerFactory.getLogger(FairTranscriptionScheduler.class);

    @FunctionalInterface
    public interface ResultSink {
        void publish(IpcMessage.TranscriptResult result);
    }

    @FunctionalInterface
    public interface SessionResultSink {
        void publish(long sessionId, IpcMessage.TranscriptResult result);
    }

    private final SpeechRecognizerEngine engine;
    private final SessionResultSink sink;
    private final int maxQueuedPerLane;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Map<UUID, PlayerQueue> queues = new HashMap<>();
    private final ArrayDeque<UUID> finalRotation = new ArrayDeque<>();
    private final Map<UUID, InFlight> inFlight = new HashMap<>();
    private final List<Thread> workers = new ArrayList<>();
    private final Map<UUID, LastLive> lastLive = new ConcurrentHashMap<>();
    private final AtomicLong processedLive = new AtomicLong();
    private final AtomicLong processedFinal = new AtomicLong();
    private final AtomicLong processedVerify = new AtomicLong();
    private final AtomicLong droppedOverload = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private long sequence;
    private int queued;
    private boolean closed;

    private FairTranscriptionScheduler(SpeechRecognizerEngine engine, SessionResultSink sink, int maxQueuedPerLane) {
        this.engine = engine;
        this.sink = sink;
        this.maxQueuedPerLane = maxQueuedPerLane;
    }

    public static FairTranscriptionScheduler start(
            SpeechRecognizerEngine engine,
            ResultSink sink,
            int workerCount,
            int maxQueuedPerLane) {
        Objects.requireNonNull(sink, "sink");
        return startWithSessions(engine, (sessionId, result) -> sink.publish(result), workerCount, maxQueuedPerLane);
    }

    public static FairTranscriptionScheduler startWithSessions(
            SpeechRecognizerEngine engine,
            SessionResultSink sink,
            int workerCount,
            int maxQueuedPerLane) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(sink, "sink");
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be at least 1, got " + workerCount);
        }
        if (maxQueuedPerLane < 1) {
            throw new IllegalArgumentException("maxQueuedPerLane must be at least 1, got " + maxQueuedPerLane);
        }
        FairTranscriptionScheduler scheduler = new FairTranscriptionScheduler(engine, sink, maxQueuedPerLane);
        for (int i = 0; i < workerCount; i++) {
            scheduler.workers.add(Thread.ofPlatform()
                    .name("voicewarden-transcriber-" + i)
                    .daemon()
                    .start(scheduler::workLoop));
        }
        return scheduler;
    }

    public boolean submitLive(IpcMessage.AudioLiveChunk chunk) {
        return submitLive(chunk, 0L);
    }

    public boolean submitLive(IpcMessage.AudioLiveChunk chunk, long sessionId) {
        return submit(new TranscriptionJob.Live(chunk.playerId(), chunk.utteranceId(), chunk.chunkIndex(),
                chunk.endOfUtterance(), chunk.pcm16(), System.nanoTime()), sessionId);
    }

    public boolean submitFinal(IpcMessage.AudioFinal audio) {
        return submitFinal(audio, 0L);
    }

    public boolean submitFinal(IpcMessage.AudioFinal audio, long sessionId) {
        return submit(new TranscriptionJob.Final(audio.playerId(), audio.utteranceId(), audio.requestDoubleCheck(),
                audio.pcm16(), System.nanoTime()), sessionId);
    }

    public boolean submitVerify(IpcMessage.VerifyRequest request) {
        return submitVerify(request, 0L);
    }

    public boolean submitVerify(IpcMessage.VerifyRequest request, long sessionId) {
        return submit(new TranscriptionJob.Verify(request.playerId(), request.utteranceId(),
                request.primaryTranscript(), request.pcm16(), System.nanoTime()), sessionId);
    }

    public boolean submit(TranscriptionJob job) {
        return submit(job, 0L);
    }

    public boolean submit(TranscriptionJob job, long sessionId) {
        Objects.requireNonNull(job, "job");
        TranscriptionJob shedJob = null;
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            PlayerQueue player = queues.computeIfAbsent(job.playerId(), id -> new PlayerQueue());
            ArrayDeque<Queued> lane = player.lane(job.lane());
            if (lane.size() >= maxQueuedPerLane) {
                Queued shed = lane.pollFirst();
                queued--;
                droppedOverload.incrementAndGet();
                shedJob = shed.job();
                LOG.debug("Shed {} for player {} utterance {}: lane is full", shedJob.getClass().getSimpleName(),
                        shedJob.playerId(), shedJob.utteranceId());
            }
            lane.addLast(new Queued(job, sequence++, sessionId));
            queued++;
            if (job.lane() == TranscriptionJob.Lane.FINAL && !player.inRotation) {
                player.inRotation = true;
                finalRotation.addLast(job.playerId());
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (shedJob instanceof TranscriptionJob.Live shedLive && shedLive.endOfUtterance()) {
            lastLive.computeIfPresent(shedLive.playerId(),
                    (id, last) -> last.utteranceId() == shedLive.utteranceId() ? null : last);
            engine.dropUtterance(shedLive.playerId(), shedLive.utteranceId());
        }
        return true;
    }

    public void dropUtterance(UUID playerId, long utteranceId) {
        boolean runningLive = false;
        lock.lock();
        try {
            PlayerQueue player = queues.get(playerId);
            if (player != null) {
                queued -= player.removeUtterance(utteranceId);
                removeIfIdle(playerId, player);
            }
            InFlight running = inFlight.get(playerId);
            if (running != null && running.job.utteranceId() == utteranceId) {
                running.cancelled = true;
                runningLive = running.job instanceof TranscriptionJob.Live;
            }
        } finally {
            lock.unlock();
        }
        if (!runningLive) {
            lastLive.computeIfPresent(playerId, (id, last) -> last.utteranceId() == utteranceId ? null : last);
            engine.dropUtterance(playerId, utteranceId);
        }
    }

    public void purgeAll() {
        purgeAll(Duration.ofSeconds(2));
    }

    public void purgeAll(Duration drainTimeout) {
        long deadline = System.nanoTime() + Math.max(0L, drainTimeout.toNanos());
        lock.lock();
        try {
            queues.clear();
            finalRotation.clear();
            queued = 0;
            inFlight.values().forEach(running -> running.cancelled = true);
            while (!inFlight.isEmpty() && !closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    changed.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
        lastLive.clear();
        engine.clearAllStreams();
    }

    public SchedulerStats stats() {
        int queuedNow;
        lock.lock();
        try {
            queuedNow = queued;
        } finally {
            lock.unlock();
        }
        return new SchedulerStats(processedLive.get(), processedFinal.get(), processedVerify.get(),
                droppedOverload.get(), failed.get(), queuedNow);
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            queues.clear();
            finalRotation.clear();
            queued = 0;
            inFlight.values().forEach(running -> running.cancelled = true);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        long deadline = System.nanoTime() + WORKER_JOIN_TIMEOUT.toNanos();
        for (Thread worker : workers) {
            if (worker == Thread.currentThread()) {
                continue;
            }
            try {
                long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                worker.join(remaining);
                if (worker.isAlive()) {
                    LOG.warn("Transcription worker {} is still decoding after {} ms", worker.getName(),
                            WORKER_JOIN_TIMEOUT.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void workLoop() {
        while (true) {
            InFlight running;
            try {
                running = take();
            } catch (InterruptedException e) {
                return;
            }
            if (running == null) {
                return;
            }
            try {
                IpcMessage.TranscriptResult result = execute(running.job);
                if (running.cancelled) {
                    if (running.job instanceof TranscriptionJob.Live live) {
                        lastLive.computeIfPresent(live.playerId(),
                                (id, last) -> last.utteranceId() == live.utteranceId() ? null : last);
                        engine.dropUtterance(live.playerId(), live.utteranceId());
                    }
                } else if (result != null) {
                    sink.publish(running.sessionId, result);
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not publish the transcript for player {} utterance {}", running.job.playerId(),
                        running.job.utteranceId(), e);
            } finally {
                lock.lock();
                try {
                    inFlight.remove(running.job.playerId());
                    PlayerQueue player = queues.get(running.job.playerId());
                    if (player != null) {
                        removeIfIdle(running.job.playerId(), player);
                    }
                    changed.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private InFlight take() throws InterruptedException {
        lock.lock();
        try {
            while (!closed) {
                Queued next = selectPriority();
                if (next == null) {
                    next = selectFinal();
                }
                if (next != null) {
                    queued--;
                    InFlight running = new InFlight(next.job(), next.sessionId());
                    inFlight.put(next.job().playerId(), running);
                    return running;
                }
                changed.await();
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private Queued selectPriority() {
        UUID chosen = null;
        Queued best = null;
        for (Map.Entry<UUID, PlayerQueue> entry : queues.entrySet()) {
            if (inFlight.containsKey(entry.getKey())) {
                continue;
            }
            Queued head = entry.getValue().priority.peekFirst();
            if (head != null && (best == null || head.sequence() < best.sequence())) {
                best = head;
                chosen = entry.getKey();
            }
        }
        if (best != null) {
            queues.get(chosen).priority.pollFirst();
        }
        return best;
    }

    private Queued selectFinal() {
        Iterator<UUID> it = finalRotation.iterator();
        while (it.hasNext()) {
            UUID playerId = it.next();
            PlayerQueue player = queues.get(playerId);
            if (player == null || player.finals.isEmpty()) {
                it.remove();
                if (player != null) {
                    player.inRotation = false;
                    removeIfIdle(playerId, player);
                }
                continue;
            }
            if (inFlight.containsKey(playerId)) {
                continue;
            }
            it.remove();
            Queued job = player.finals.pollFirst();
            if (player.finals.isEmpty()) {
                player.inRotation = false;
            } else {
                finalRotation.addLast(playerId);
            }
            return job;
        }
        return null;
    }

    private void removeIfIdle(UUID playerId, PlayerQueue player) {
        if (player.isEmpty() && !player.inRotation && !inFlight.containsKey(playerId)) {
            queues.remove(playerId);
        }
    }

    private IpcMessage.TranscriptResult execute(TranscriptionJob job) {
        try {
            return switch (job) {
                case TranscriptionJob.Live live -> executeLive(live);
                case TranscriptionJob.Final fin -> executeFinal(fin);
                case TranscriptionJob.Verify verify -> executeVerify(verify);
            };
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            LOG.warn("Transcription of {} for player {} utterance {} failed", job.getClass().getSimpleName(),
                    job.playerId(), job.utteranceId(), e);
            return failureResult(job);
        }
    }

    private IpcMessage.TranscriptResult executeLive(TranscriptionJob.Live live) {
        RecognitionOutput out = engine.feedLiveChunk(live.playerId(), live.utteranceId(), live.pcm16k(),
                live.endOfUtterance());
        processedLive.incrementAndGet();
        LastLive previous = live.endOfUtterance()
                ? lastLive.remove(live.playerId())
                : lastLive.put(live.playerId(), new LastLive(live.utteranceId(), out.text()));
        boolean unchanged = previous != null && previous.utteranceId() == live.utteranceId()
                && previous.text().equals(out.text());
        if (!engine.supportsStreaming() || !live.endOfUtterance() && (out.isBlank() || unchanged)) {
            return null;
        }
        return new IpcMessage.TranscriptResult(live.playerId(), live.utteranceId(), false, true, false,
                ConfirmationState.UNCHECKED, out.text(), out.language(), latencyMs(live));
    }

    private IpcMessage.TranscriptResult executeFinal(TranscriptionJob.Final fin) {
        RecognitionOutput out = engine.transcribeFinal(fin.playerId(), fin.utteranceId(), fin.pcm16k(),
                fin.requestDoubleCheck());
        processedFinal.incrementAndGet();
        return new IpcMessage.TranscriptResult(fin.playerId(), fin.utteranceId(), true, false, false,
                out.confirmation(), out.text(), out.language(), latencyMs(fin));
    }

    private IpcMessage.TranscriptResult executeVerify(TranscriptionJob.Verify verify) {
        VerificationOutput out = engine.verifyUtterance(verify.playerId(), verify.utteranceId(),
                verify.primaryTranscript(), verify.pcm16k());
        processedVerify.incrementAndGet();
        return new IpcMessage.TranscriptResult(verify.playerId(), verify.utteranceId(), true, false, true,
                out.state(), out.verifierText(), out.language(), latencyMs(verify));
    }

    private static IpcMessage.TranscriptResult failureResult(TranscriptionJob job) {
        return switch (job) {
            case TranscriptionJob.Live live -> null;
            case TranscriptionJob.Final fin -> new IpcMessage.TranscriptResult(fin.playerId(), fin.utteranceId(),
                    true, false, false, ConfirmationState.UNCHECKED, "", "", latencyMs(fin));
            case TranscriptionJob.Verify verify -> new IpcMessage.TranscriptResult(verify.playerId(),
                    verify.utteranceId(), true, false, true, ConfirmationState.UNCHECKED, "", "", latencyMs(verify));
        };
    }

    private static int latencyMs(TranscriptionJob job) {
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.enqueuedNanos());
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, ms));
    }

    private record Queued(TranscriptionJob job, long sequence, long sessionId) {
    }

    private record LastLive(long utteranceId, String text) {
    }

    private static final class InFlight {
        final TranscriptionJob job;
        final long sessionId;
        volatile boolean cancelled;

        InFlight(TranscriptionJob job, long sessionId) {
            this.job = job;
            this.sessionId = sessionId;
        }
    }

    private static final class PlayerQueue {
        final ArrayDeque<Queued> priority = new ArrayDeque<>();
        final ArrayDeque<Queued> finals = new ArrayDeque<>();
        boolean inRotation;

        ArrayDeque<Queued> lane(TranscriptionJob.Lane lane) {
            return lane == TranscriptionJob.Lane.FINAL ? finals : priority;
        }

        boolean isEmpty() {
            return priority.isEmpty() && finals.isEmpty();
        }

        int removeUtterance(long utteranceId) {
            int before = priority.size() + finals.size();
            priority.removeIf(q -> q.job().utteranceId() == utteranceId);
            finals.removeIf(q -> q.job().utteranceId() == utteranceId);
            return before - priority.size() - finals.size();
        }
    }
}
