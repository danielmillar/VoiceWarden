package dev.voicewarden.engine;

import dev.voicewarden.core.ipc.IpcConnection;
import dev.voicewarden.core.ipc.IpcMessage;
import dev.voicewarden.core.ipc.IpcServerChannel;
import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EngineIpcServer implements AutoCloseable {

    public static final int EXIT_OK = 0;
    public static final int EXIT_STARTUP_FAILED = 2;
    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_IDLE_STREAM_TIMEOUT = Duration.ofSeconds(60);

    private static final Logger LOG = LoggerFactory.getLogger(EngineIpcServer.class);

    private final IpcServerChannel channel;
    private final String profileName;
    private final int workers;
    private final int maxQueuedPerLane;
    private final Duration idleStreamTimeout;
    private final long startedNanos = System.nanoTime();
    private final AtomicLong sessionIds = new AtomicLong();
    private final AtomicReference<ActiveConnection> current = new AtomicReference<>();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong droppedBeforeReady = new AtomicLong();
    private final ScheduledExecutorService janitor;
    private volatile FairTranscriptionScheduler scheduler;
    private volatile SpeechRecognizerEngine engine;
    private volatile Throwable startupFailure;

    private record ActiveConnection(long sessionId, IpcConnection connection) {
    }

    public EngineIpcServer(
            IpcServerChannel channel,
            String profileName,
            int workers,
            int maxQueuedPerLane,
            Duration idleStreamTimeout) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.profileName = Objects.requireNonNull(profileName, "profileName");
        this.workers = workers;
        this.maxQueuedPerLane = maxQueuedPerLane;
        this.idleStreamTimeout = Objects.requireNonNull(idleStreamTimeout, "idleStreamTimeout");
        this.janitor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("voicewarden-engine-janitor").daemon().factory());
    }

    public synchronized boolean attachEngine(SpeechRecognizerEngine recognizer) {
        Objects.requireNonNull(recognizer, "recognizer");
        if (closed.get()) {
            recognizer.close();
            return false;
        }
        if (engine != null) {
            throw new IllegalStateException("An engine is already attached");
        }
        scheduler = FairTranscriptionScheduler.startWithSessions(recognizer, this::publish, workers, maxQueuedPerLane);
        engine = recognizer;
        long periodMs = Math.max(1_000, idleStreamTimeout.toMillis() / 2);
        janitor.scheduleWithFixedDelay(this::evictIdleStreams, periodMs, periodMs, TimeUnit.MILLISECONDS);
        LOG.info("Engine is ready (profile {}, streaming {}, Double Check {})", profileName,
                recognizer.supportsStreaming(), recognizer.hasDoubleCheckVerifier());
        return true;
    }

    public void failStartup(Throwable cause) {
        startupFailure = Objects.requireNonNull(cause, "cause");
        shutdownRequested.set(true);
        channel.close();
        ActiveConnection active = current.getAndSet(null);
        if (active != null) {
            active.connection().close();
        }
    }

    public boolean isReady() {
        return scheduler != null && !closed.get();
    }

    public long droppedBeforeReady() {
        return droppedBeforeReady.get();
    }

    public int run() {
        while (!shutdownRequested.get() && channel.isOpen()) {
            IpcConnection connection;
            try {
                connection = channel.accept();
            } catch (IOException e) {
                if (shutdownRequested.get() || !channel.isOpen()) {
                    break;
                }
                LOG.warn("Accepting an IPC connection failed: {}", e.getMessage());
                continue;
            }
            ActiveConnection session = new ActiveConnection(sessionIds.incrementAndGet(), connection);
            current.set(session);
            if (shutdownRequested.get() || closed.get()) {
                current.compareAndSet(session, null);
                connection.close();
                break;
            }
            LOG.info("Supervisor connected over {}", connection.endpoint());
            try {
                serve(session);
            } finally {
                current.compareAndSet(session, null);
                connection.close();
                FairTranscriptionScheduler active = scheduler;
                if (active != null) {
                    active.purgeAll();
                }
            }
        }
        return startupFailure != null ? EXIT_STARTUP_FAILED : EXIT_OK;
    }

    public IpcMessage.EngineStatusResponse status() {
        long uptimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        FairTranscriptionScheduler active = scheduler;
        if (active == null) {
            return new IpcMessage.EngineStatusResponse(uptimeMs, 0, 0, 0, 0, 0, profileName, false);
        }
        SchedulerStats stats = active.stats();
        return new IpcMessage.EngineStatusResponse(uptimeMs, stats.processedLive(), stats.processedFinal(),
                stats.processedVerify(), stats.droppedOverload(), stats.queuedJobs(), profileName, isReady());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        shutdownRequested.set(true);
        channel.close();
        ActiveConnection active = current.getAndSet(null);
        if (active != null) {
            active.connection().close();
        }
        janitor.shutdownNow();
        synchronized (this) {
            if (scheduler != null) {
                scheduler.close();
            }
            if (engine != null) {
                engine.close();
            }
        }
    }

    private void serve(ActiveConnection session) {
        IpcConnection connection = session.connection();
        while (!shutdownRequested.get()) {
            IpcMessage message;
            try {
                message = connection.receive();
            } catch (EOFException e) {
                LOG.info("Supervisor disconnected");
                return;
            } catch (IOException e) {
                if (!shutdownRequested.get()) {
                    LOG.warn("IPC connection failed: {}", e.getMessage());
                }
                return;
            }
            if (!handle(session, message)) {
                return;
            }
        }
    }

    private boolean handle(ActiveConnection session, IpcMessage message) {
        IpcConnection connection = session.connection();
        long sessionId = session.sessionId();
        FairTranscriptionScheduler active = scheduler;
        switch (message) {
            case IpcMessage.Ping ping -> reply(connection, new IpcMessage.Pong(ping.nonce()));
            case IpcMessage.EngineStatusRequest request -> reply(connection, status());
            case IpcMessage.AudioLiveChunk chunk -> accepted(active != null && active.submitLive(chunk, sessionId));
            case IpcMessage.AudioFinal audio -> accepted(active != null && active.submitFinal(audio, sessionId));
            case IpcMessage.VerifyRequest verify -> accepted(active != null && active.submitVerify(verify, sessionId));
            case IpcMessage.DropUtterance drop -> {
                if (active != null) {
                    active.dropUtterance(drop.playerId(), drop.utteranceId());
                }
            }
            case IpcMessage.Shutdown shutdown -> {
                LOG.info("Supervisor requested shutdown");
                shutdownRequested.set(true);
                channel.close();
                return false;
            }
            default -> LOG.warn("Ignoring unexpected {} frame from the supervisor", message.type());
        }
        return true;
    }

    private void accepted(boolean queued) {
        if (!queued) {
            long dropped = droppedBeforeReady.incrementAndGet();
            if (dropped == 1 || dropped % 1_000 == 0) {
                LOG.info("Dropped {} audio frame(s) that arrived before the speech models finished loading", dropped);
            }
        }
    }

    private void publish(long sessionId, IpcMessage.TranscriptResult result) {
        ActiveConnection active = current.get();
        if (active != null && active.sessionId() == sessionId) {
            reply(active.connection(), result);
        }
    }

    private void reply(IpcConnection connection, IpcMessage message) {
        try {
            connection.send(message, SEND_TIMEOUT);
        } catch (IOException e) {
            LOG.warn("Could not send {} to the supervisor: {}", message.type(), e.getMessage());
            connection.close();
        }
    }

    private void evictIdleStreams() {
        SpeechRecognizerEngine active = engine;
        if (active == null) {
            return;
        }
        try {
            int evicted = active.evictIdleStreams(idleStreamTimeout);
            if (evicted > 0) {
                LOG.debug("Released {} idle live stream(s)", evicted);
            }
        } catch (RuntimeException e) {
            LOG.warn("Releasing idle live streams failed", e);
        }
    }
}
