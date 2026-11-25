package dev.voicewarden.core.ipc;

import dev.voicewarden.core.audio.FinalUtterance;
import dev.voicewarden.core.audio.LiveAudioChunk;
import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EngineClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EngineClient.class);
    private static final double LATENCY_SMOOTHING = 0.2;
    public static final Duration CONTROL_SEND_TIMEOUT = Duration.ofSeconds(2);

    private final IpcConnection connection;
    private final EngineResultListener listener;
    private final String engineVersion;
    private final String engineProfile;
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final AtomicBoolean disconnectReported = new AtomicBoolean();
    private final AtomicReference<Throwable> writeFailure = new AtomicReference<>();
    private final Thread reader;
    private volatile long lastPongMs;
    private volatile long lastPongNanos;
    private volatile double averageLatencyMs = -1;

    private EngineClient(IpcConnection connection, IpcMessage.AuthOk authOk, EngineResultListener listener) {
        this.connection = connection;
        this.listener = listener;
        this.engineVersion = authOk.engineVersion();
        this.engineProfile = authOk.profile();
        this.lastPongMs = System.currentTimeMillis();
        this.lastPongNanos = System.nanoTime();
        this.reader = new Thread(this::readLoop, "voicewarden-engine-client-reader");
        this.reader.setDaemon(true);
    }

    public static EngineClient connect(
            IpcEndpoint endpoint,
            IpcAuthToken token,
            Duration handshakeTimeout,
            EngineResultListener listener) throws IOException {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        Objects.requireNonNull(listener, "listener");
        IpcConnection connection = new IpcConnection(IpcTransport.connect(endpoint), endpoint);
        try {
            connection.send(new IpcMessage.AuthRequest(token));
            IpcMessage reply = connection.receive(handshakeTimeout);
            if (reply instanceof IpcMessage.AuthFail fail) {
                throw new IpcAuthException("Engine rejected authentication: " + fail.reason());
            }
            if (!(reply instanceof IpcMessage.AuthOk ok)) {
                throw new IpcProtocolException("Expected AUTH_OK from engine, got " + reply.type());
            }
            EngineClient client = new EngineClient(connection, ok, listener);
            client.reader.start();
            return client;
        } catch (EOFException e) {
            connection.close();
            throw new IpcAuthException("Engine closed the connection during authentication");
        } catch (IOException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    public String engineVersion() {
        return engineVersion;
    }

    public String engineProfile() {
        return engineProfile;
    }

    public IpcEndpoint endpoint() {
        return connection.endpoint();
    }

    public boolean isConnected() {
        return connected.get() && connection.isOpen();
    }

    public long lastPongMs() {
        return lastPongMs;
    }

    long lastPongNanos() {
        return lastPongNanos;
    }

    public long millisSinceLastPong() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastPongNanos);
    }

    public double averageLatencyMs() {
        return averageLatencyMs;
    }

    public boolean sendLiveChunk(LiveAudioChunk chunk) {
        return send(new IpcMessage.AudioLiveChunk(
                chunk.playerId(), chunk.utteranceId(), chunk.chunkIndex(), chunk.endOfUtterance(), chunk.pcm16k()));
    }

    public boolean sendFinalUtterance(FinalUtterance utterance, boolean requestDoubleCheck) {
        return send(new IpcMessage.AudioFinal(
                utterance.playerId(), utterance.utteranceId(), requestDoubleCheck, utterance.pcm16k()));
    }

    public boolean sendVerifyRequest(UUID playerId, long utteranceId, String primaryTranscript, short[] pcm16) {
        return send(new IpcMessage.VerifyRequest(playerId, utteranceId, primaryTranscript, pcm16));
    }

    public boolean sendDropUtterance(UUID playerId, long utteranceId) {
        return send(new IpcMessage.DropUtterance(playerId, utteranceId));
    }

    public boolean ping() {
        return send(new IpcMessage.Ping(System.nanoTime()), CONTROL_SEND_TIMEOUT);
    }

    public boolean requestStatus() {
        return send(new IpcMessage.EngineStatusRequest(), CONTROL_SEND_TIMEOUT);
    }

    public boolean sendShutdown() {
        return send(new IpcMessage.Shutdown(), CONTROL_SEND_TIMEOUT);
    }

    public boolean send(IpcMessage message) {
        return send(message, null);
    }

    public boolean send(IpcMessage message, Duration timeout) {
        if (!isConnected()) {
            return false;
        }
        try {
            if (timeout == null) {
                connection.send(message);
            } else {
                connection.send(message, timeout);
            }
            return true;
        } catch (IpcProtocolException e) {
            LOG.warn("Dropped {} that cannot be framed: {}", message.type(), e.getMessage());
            return false;
        } catch (IOException e) {
            writeFailure.compareAndSet(null, e);
            connected.set(false);
            connection.close();
            return false;
        }
    }

    @Override
    public void close() {
        connected.set(false);
        connection.close();
        if (Thread.currentThread() != reader && reader.isAlive()) {
            try {
                reader.join(CONTROL_SEND_TIMEOUT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!reader.isAlive()) {
            reportDisconnect(writeFailure.get());
        }
    }

    private void readLoop() {
        Throwable cause = null;
        try {
            while (connected.get()) {
                dispatch(connection.receive());
            }
        } catch (EOFException e) {
            cause = null;
        } catch (IOException e) {
            cause = connected.get() ? e : null;
        } finally {
            connected.set(false);
            connection.close();
            Throwable reported = cause != null ? cause : writeFailure.get();
            reportDisconnect(reported);
        }
    }

    private void dispatch(IpcMessage message) throws IpcProtocolException {
        try {
            switch (message) {
                case IpcMessage.TranscriptResult result -> listener.onTranscript(result);
                case IpcMessage.Pong pong -> handlePong(pong);
                case IpcMessage.EngineStatusResponse status -> listener.onStatus(status);
                default -> throw new IpcProtocolException("Engine sent unexpected " + message.type() + " frame");
            }
        } catch (RuntimeException e) {
            LOG.warn("Engine result listener failed on {}", message.type(), e);
        }
    }

    private void handlePong(IpcMessage.Pong pong) {
        long now = System.nanoTime();
        lastPongMs = System.currentTimeMillis();
        lastPongNanos = now;
        double rttMs = Math.max(0, now - pong.nonce()) / 1_000_000.0;
        double previous = averageLatencyMs;
        averageLatencyMs = previous < 0 ? rttMs : previous + LATENCY_SMOOTHING * (rttMs - previous);
        listener.onPong(pong.nonce(), rttMs);
    }

    private void reportDisconnect(Throwable cause) {
        if (disconnectReported.compareAndSet(false, true)) {
            try {
                listener.onDisconnected(cause);
            } catch (RuntimeException e) {
                LOG.warn("Engine result listener failed on disconnect", e);
            }
        }
    }
}
