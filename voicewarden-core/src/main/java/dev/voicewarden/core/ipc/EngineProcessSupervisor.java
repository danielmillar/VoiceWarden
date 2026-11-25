package dev.voicewarden.core.ipc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EngineProcessSupervisor implements AutoCloseable {

    public enum State {
        NEW,
        STARTING,
        RUNNING,
        BACKOFF,
        FAILED,
        CLOSED
    }

    private static final Logger LOG = LoggerFactory.getLogger(EngineProcessSupervisor.class);
    private static final long READINESS_POLL_MS = 100;
    private static final long WINDOWS_PRIORITY_TIMEOUT_MS = 10_000;

    private final EngineProcessConfig config;
    private final IpcAuthToken token;
    private final EngineResultListener results;
    private final EngineSupervisorListener events;
    private final EngineLogSink logs;
    private final Optional<Path> nice;
    private final Object lock = new Object();
    private final Deque<Long> restartTimesNanos = new ArrayDeque<>();
    private final AtomicInteger restarts = new AtomicInteger();

    private State state = State.NEW;
    private boolean closed;
    private Process process;
    private EngineClient client;
    private Thread monitor;

    public EngineProcessSupervisor(
            EngineProcessConfig config,
            IpcAuthToken token,
            EngineResultListener results,
            EngineSupervisorListener events,
            EngineLogSink logs) {
        this.config = Objects.requireNonNull(config, "config");
        this.token = Objects.requireNonNull(token, "token");
        this.results = Objects.requireNonNull(results, "results");
        this.events = Objects.requireNonNull(events, "events");
        this.logs = Objects.requireNonNull(logs, "logs");
        this.nice = config.resolveNice();
    }

    public List<String> command() {
        return config.command(nice);
    }

    public void start() {
        synchronized (lock) {
            if (state != State.NEW) {
                throw new IllegalStateException("Engine supervisor was already started (state " + state + ")");
            }
            state = State.STARTING;
            monitor = new Thread(this::superviseLoop, "voicewarden-engine-supervisor");
            monitor.setDaemon(true);
            monitor.start();
        }
        if (config.lowerPriority() && nice.isEmpty() && !IpcEndpoint.isWindows()) {
            LOG.warn("nice is not available; the VoiceWarden engine will run at normal CPU priority");
        }
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public boolean isReady() {
        EngineClient current = currentClient();
        return state() == State.RUNNING && current != null && current.isConnected();
    }

    public boolean awaitReady(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (state != State.RUNNING && state != State.FAILED && state != State.CLOSED) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    return false;
                }
                lock.wait(remainingMs);
            }
            return state == State.RUNNING;
        }
    }

    public Optional<EngineClient> client() {
        return Optional.ofNullable(currentClient());
    }

    public OptionalLong pid() {
        synchronized (lock) {
            return process == null ? OptionalLong.empty() : OptionalLong.of(process.pid());
        }
    }

    public int restartCount() {
        return restarts.get();
    }

    @Override
    public void close() {
        Process current;
        EngineClient currentClient;
        Thread monitorThread;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            current = process;
            currentClient = client;
            monitorThread = monitor;
            lock.notifyAll();
        }
        boolean shutdownSent = currentClient != null && currentClient.sendShutdown();
        if (current != null) {
            stopProcess(current, shutdownSent && waitForExit(current, config.shutdownGrace()));
        }
        if (currentClient != null) {
            currentClient.close();
        }
        if (monitorThread != null) {
            monitorThread.interrupt();
            try {
                monitorThread.join(config.shutdownGrace().toMillis() + 1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        setState(State.CLOSED);
    }

    private void superviseLoop() {
        try {
            while (!isClosed()) {
                String reason = runOnce();
                if (isClosed() || !scheduleRestart(reason)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.error("VoiceWarden engine supervisor crashed", e);
            setState(State.FAILED);
            events.onGaveUp("supervisor error: " + e);
        }
    }

    private String runOnce() throws InterruptedException {
        setState(State.STARTING);
        Process child;
        try {
            child = launch();
        } catch (IOException e) {
            LOG.error("Could not launch the VoiceWarden engine: {}", e.getMessage());
            return "launch failed: " + e.getMessage();
        }
        if (child == null) {
            return "supervisor closed";
        }
        long pid = child.pid();
        String reason;
        try {
            EngineClient connected = awaitConnection(child);
            synchronized (lock) {
                if (closed) {
                    connected.close();
                    return "supervisor closed";
                }
                client = connected;
                state = State.RUNNING;
                lock.notifyAll();
            }
            LOG.info("VoiceWarden engine {} ready (pid {}, profile {})",
                    connected.engineVersion(), pid, connected.engineProfile());
            events.onEngineReady(connected, pid);
            reason = watch(child, connected);
        } catch (EngineFailure failure) {
            reason = failure.getMessage();
        } finally {
            EngineClient previous;
            synchronized (lock) {
                previous = client;
                client = null;
            }
            if (previous != null) {
                previous.close();
            }
            if (!isClosed()) {
                stopProcess(child, !child.isAlive());
            }
        }
        if (!isClosed()) {
            LOG.warn("VoiceWarden engine (pid {}) lost: {}", pid, reason);
            events.onEngineLost(pid, reason);
        }
        return reason;
    }

    private Process launch() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command());
        builder.environment().put(IpcAuthToken.ENV_VAR, token.toHex());
        if (config.workingDirectory() != null) {
            builder.directory(config.workingDirectory().toFile());
        }
        Process child;
        synchronized (lock) {
            if (closed) {
                return null;
            }
            child = builder.start();
            process = child;
        }
        pump(child, child.getInputStream(), EngineLogSink.Stream.STDOUT);
        pump(child, child.getErrorStream(), EngineLogSink.Stream.STDERR);
        LOG.info("Launched VoiceWarden engine (pid {}) on {}", child.pid(), config.endpoint());
        if (config.lowerPriority() && IpcEndpoint.isWindows()) {
            lowerWindowsPriority(child.pid());
        }
        return child;
    }

    private EngineClient awaitConnection(Process child) throws InterruptedException, EngineFailure {
        long deadline = System.nanoTime() + config.startupTimeout().toNanos();
        IOException last = null;
        while (!isClosed()) {
            if (!child.isAlive()) {
                throw new EngineFailure("engine exited with code " + child.exitValue() + " during startup");
            }
            if (System.nanoTime() > deadline) {
                throw new EngineFailure("engine did not accept an IPC connection within "
                        + config.startupTimeout().toMillis() + " ms"
                        + (last == null ? "" : " (last error: " + last.getMessage() + ")"));
            }
            try {
                return EngineClient.connect(config.endpoint(), token, config.handshakeTimeout(), results);
            } catch (IpcAuthException e) {
                throw new EngineFailure("engine authentication failed: " + e.getMessage());
            } catch (IOException e) {
                last = e;
            }
            child.waitFor(READINESS_POLL_MS, TimeUnit.MILLISECONDS);
        }
        throw new EngineFailure("supervisor closed");
    }

    private String watch(Process child, EngineClient connected) throws InterruptedException {
        long intervalNanos = Math.max(1L, config.heartbeatInterval().toNanos());
        int maxMissed = config.maxMissedHeartbeats();
        long lastSeenPongNanos = connected.lastPongNanos();
        int missed = 0;
        while (!isClosed()) {
            if (!connected.isConnected()) {
                return "IPC connection to the engine was lost";
            }
            if (!connected.ping()) {
                return "IPC connection to the engine was lost";
            }
            if (child.waitFor(intervalNanos, TimeUnit.NANOSECONDS)) {
                return "engine exited with code " + child.exitValue();
            }
            if (isClosed()) {
                break;
            }
            if (!connected.isConnected()) {
                return "IPC connection to the engine was lost";
            }
            long currentPongNanos = connected.lastPongNanos();
            if (currentPongNanos != lastSeenPongNanos) {
                lastSeenPongNanos = currentPongNanos;
                missed = 0;
            } else if (++missed >= maxMissed) {
                return "engine stopped responding (" + missed + " missed heartbeats, " + connected.millisSinceLastPong() + " ms)";
            }
        }
        return "supervisor closed";
    }

    private boolean scheduleRestart(String reason) throws InterruptedException {
        long now = System.nanoTime();
        long windowNanos = config.restartWindow().toNanos();
        int recent;
        String gaveUp = null;
        synchronized (lock) {
            while (!restartTimesNanos.isEmpty() && now - restartTimesNanos.peekFirst() > windowNanos) {
                restartTimesNanos.removeFirst();
            }
            recent = restartTimesNanos.size();
            if (recent >= config.maxRestartsInWindow()) {
                state = State.FAILED;
                gaveUp = "engine failed " + (recent + 1) + " times within " + config.restartWindow().toSeconds()
                        + " s; giving up (last failure: " + reason + ")";
            } else {
                restartTimesNanos.addLast(now);
                recent++;
                state = State.BACKOFF;
            }
            lock.notifyAll();
        }
        if (gaveUp != null) {
            LOG.error("VoiceWarden engine supervisor {}", gaveUp);
            events.onGaveUp(gaveUp);
            return false;
        }
        restarts.incrementAndGet();
        Duration delay = backoff(recent);
        LOG.warn("Restarting VoiceWarden engine in {} ms (restart {} of {} allowed per {} s)",
                delay.toMillis(), recent, config.maxRestartsInWindow(), config.restartWindow().toSeconds());
        events.onRestartScheduled(recent, delay);
        long wakeAt = System.nanoTime() + delay.toNanos();
        synchronized (lock) {
            while (!closed) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(wakeAt - System.nanoTime());
                if (remainingMs <= 0) {
                    break;
                }
                lock.wait(remainingMs);
            }
            return !closed;
        }
    }

    private Duration backoff(int restartsInWindow) {
        int shift = Math.min(restartsInWindow - 1, 20);
        Duration delay = config.initialBackoff().multipliedBy(1L << shift);
        return delay.compareTo(config.maxBackoff()) > 0 ? config.maxBackoff() : delay;
    }

    private void stopProcess(Process child, boolean exited) {
        if (exited || !child.isAlive()) {
            return;
        }
        child.destroy();
        if (!waitForExit(child, config.shutdownGrace())) {
            LOG.warn("VoiceWarden engine (pid {}) ignored SIGTERM; killing it", child.pid());
            child.destroyForcibly();
            waitForExit(child, config.shutdownGrace());
        }
    }

    private static boolean waitForExit(Process child, Duration timeout) {
        try {
            return child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !child.isAlive();
        }
    }

    private void pump(Process child, InputStream stream, EngineLogSink.Stream kind) {
        long pid = child.pid();
        Thread pumpThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        logs.engineOutput(kind, pid, line);
                    } catch (RuntimeException e) {
                        LOG.warn("Engine log sink failed", e);
                    }
                }
            } catch (IOException ignored) {
                return;
            }
        }, "voicewarden-engine-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-" + pid);
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private void lowerWindowsPriority(long pid) {
        ProcessBuilder builder = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-Process -Id " + pid + ").PriorityClass = 'BelowNormal'");
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process helper = null;
        try {
            helper = builder.start();
            if (!helper.waitFor(WINDOWS_PRIORITY_TIMEOUT_MS, TimeUnit.MILLISECONDS) || helper.exitValue() != 0) {
                helper.destroyForcibly();
                LOG.warn("Could not lower VoiceWarden engine priority; it runs at normal CPU priority");
            }
        } catch (IOException e) {
            LOG.warn("Could not lower VoiceWarden engine priority: {}", e.getMessage());
        } catch (InterruptedException e) {
            if (helper != null) {
                helper.destroyForcibly();
            }
            Thread.currentThread().interrupt();
        }
    }

    private EngineClient currentClient() {
        synchronized (lock) {
            return client;
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private void setState(State next) {
        synchronized (lock) {
            if (state == State.CLOSED || (closed && next != State.CLOSED)) {
                return;
            }
            state = next;
            lock.notifyAll();
        }
    }

    private static final class EngineFailure extends Exception {
        EngineFailure(String message) {
            super(message, null, false, false);
        }
    }
}
