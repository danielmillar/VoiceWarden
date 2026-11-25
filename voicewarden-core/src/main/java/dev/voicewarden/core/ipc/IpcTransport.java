package dev.voicewarden.core.ipc;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class IpcTransport {

    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");

    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voicewarden-ipc-deadlines");
        t.setDaemon(true);
        return t;
    });

    private IpcTransport() {
    }

    public static SocketChannel connect(IpcEndpoint endpoint) throws IOException {
        SocketChannel channel = SocketChannel.open(endpoint.family());
        try {
            if (endpoint instanceof IpcEndpoint.Tcp) {
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            }
            channel.connect(endpoint.address());
            return channel;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    public static ServerSocketChannel bind(IpcEndpoint endpoint) throws IOException {
        if (endpoint instanceof IpcEndpoint.Unix unix) {
            prepareSocketPath(unix);
        }
        ServerSocketChannel server = ServerSocketChannel.open(endpoint.family());
        try {
            if (endpoint instanceof IpcEndpoint.Tcp) {
                server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            }
            server.bind(endpoint.address());
            if (endpoint instanceof IpcEndpoint.Unix unix) {
                restrictToOwner(unix.socketPath(), OWNER_ONLY_FILE);
            }
            return server;
        } catch (IOException | RuntimeException e) {
            server.close();
            throw e;
        }
    }

    public static void configureAccepted(SocketChannel channel, IpcEndpoint endpoint) throws IOException {
        if (endpoint instanceof IpcEndpoint.Tcp) {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
    }

    public static void deleteSocketFile(IpcEndpoint endpoint) {
        if (endpoint instanceof IpcEndpoint.Unix unix) {
            try {
                Files.deleteIfExists(unix.socketPath());
            } catch (IOException ignored) {
                return;
            }
        }
    }

    static Deadline deadline(Channel channel, Duration timeout) {
        AtomicBoolean settled = new AtomicBoolean();
        AtomicBoolean fired = new AtomicBoolean();
        ScheduledFuture<?> task = DEADLINES.schedule(() -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            fired.set(true);
            try {
                channel.close();
            } catch (IOException ignored) {
                return;
            }
        }, Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        return new Deadline(task, settled, fired);
    }

    record Deadline(ScheduledFuture<?> task, AtomicBoolean settled, AtomicBoolean fired) implements AutoCloseable {

        boolean expired() {
            return fired.get();
        }

        @Override
        public void close() {
            settled.compareAndSet(false, true);
            task.cancel(false);
        }
    }

    private static void prepareSocketPath(IpcEndpoint.Unix unix) throws IOException {
        Path path = unix.socketPath();
        Path parent = path.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
            restrictToOwner(parent, OWNER_ONLY_DIR);
        }
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path) || Files.isRegularFile(path)) {
            throw new IOException("Refusing to replace " + path + ": it is not a socket");
        }
        try (SocketChannel probe = SocketChannel.open(unix.family())) {
            probe.connect(unix.address());
        } catch (IOException staleSocket) {
            Files.deleteIfExists(path);
            return;
        }
        throw new IOException("Another process is already listening on " + path);
    }

    private static void restrictToOwner(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            return;
        }
    }
}
