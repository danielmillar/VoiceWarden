package dev.voicewarden.core.ipc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class IpcServerChannel implements AutoCloseable {

    public static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(5);

    private final ServerSocketChannel server;
    private final IpcEndpoint endpoint;
    private final IpcAuthToken token;
    private final Duration authTimeout;
    private final String engineVersion;
    private final String profile;
    private final AtomicReference<IpcConnection> authenticating = new AtomicReference<>();
    private volatile boolean closed;
    private volatile Consumer<String> rejectionSink = reason -> { };

    private IpcServerChannel(
            ServerSocketChannel server,
            IpcEndpoint endpoint,
            IpcAuthToken token,
            Duration authTimeout,
            String engineVersion,
            String profile) {
        this.server = server;
        this.endpoint = endpoint;
        this.token = token;
        this.authTimeout = authTimeout;
        this.engineVersion = engineVersion;
        this.profile = profile;
    }

    public static IpcServerChannel bind(IpcEndpoint endpoint, IpcAuthToken token, String engineVersion, String profile)
            throws IOException {
        return bind(endpoint, token, DEFAULT_AUTH_TIMEOUT, engineVersion, profile);
    }

    public static IpcServerChannel bind(
            IpcEndpoint endpoint,
            IpcAuthToken token,
            Duration authTimeout,
            String engineVersion,
            String profile) throws IOException {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(authTimeout, "authTimeout");
        Objects.requireNonNull(engineVersion, "engineVersion");
        Objects.requireNonNull(profile, "profile");
        ServerSocketChannel server = IpcTransport.bind(endpoint);
        IpcEndpoint bound = endpoint;
        if (endpoint instanceof IpcEndpoint.Tcp) {
            bound = new IpcEndpoint.Tcp(((InetSocketAddress) server.getLocalAddress()).getPort());
        }
        return new IpcServerChannel(server, bound, token, authTimeout, engineVersion, profile);
    }

    public IpcEndpoint endpoint() {
        return endpoint;
    }

    public IpcServerChannel onRejection(Consumer<String> sink) {
        this.rejectionSink = Objects.requireNonNull(sink, "sink");
        return this;
    }

    public IpcConnection accept() throws IOException {
        while (true) {
            SocketChannel channel = server.accept();
            IpcTransport.configureAccepted(channel, endpoint);
            IpcConnection connection = new IpcConnection(channel, endpoint);
            authenticating.set(connection);
            if (closed) {
                authenticating.compareAndSet(connection, null);
                connection.close();
                throw new ClosedChannelException();
            }
            String rejection;
            try {
                rejection = handshake(connection);
            } finally {
                authenticating.compareAndSet(connection, null);
            }
            if (rejection == null) {
                if (closed) {
                    connection.close();
                    throw new ClosedChannelException();
                }
                return connection;
            }
            connection.close();
            if (closed) {
                throw new ClosedChannelException();
            }
            rejectionSink.accept(rejection);
        }
    }

    public boolean isOpen() {
        return !closed && server.isOpen();
    }

    @Override
    public void close() {
        closed = true;
        IpcConnection active = authenticating.getAndSet(null);
        if (active != null) {
            active.close();
        }
        try {
            server.close();
        } catch (IOException ignored) {
            return;
        } finally {
            IpcTransport.deleteSocketFile(endpoint);
        }
    }

    private String handshake(IpcConnection connection) {
        IpcMessage first;
        try {
            first = connection.receive(authTimeout);
        } catch (IOException e) {
            return "handshake failed before AUTH_REQ: " + e.getMessage();
        }
        String reason;
        if (!(first instanceof IpcMessage.AuthRequest request)) {
            reason = "first frame must be AUTH_REQ, got " + first.type();
        } else if (!token.matches(request.token())) {
            reason = "invalid auth token";
        } else {
            try {
                connection.send(new IpcMessage.AuthOk(engineVersion, profile), authTimeout);
                return null;
            } catch (IOException e) {
                return "could not send AUTH_OK: " + e.getMessage();
            }
        }
        try {
            connection.send(new IpcMessage.AuthFail(reason), authTimeout);
        } catch (IOException ignored) {
            return reason;
        }
        return reason;
    }
}
