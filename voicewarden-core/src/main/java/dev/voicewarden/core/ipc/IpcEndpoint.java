package dev.voicewarden.core.ipc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public sealed interface IpcEndpoint permits IpcEndpoint.Unix, IpcEndpoint.Tcp {

    String ARG_SOCKET = "--ipc-socket";
    String ARG_PORT = "--ipc-port";

    SocketAddress address();

    ProtocolFamily family();

    List<String> toEngineArgs();

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static IpcEndpoint platformDefault(Path runtimeDir) {
        if (isWindows()) {
            return Tcp.ephemeral();
        }
        return new Unix(runtimeDir.resolve("engine.sock"));
    }

    static IpcEndpoint fromEngineArgs(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (ARG_SOCKET.equals(args[i])) {
                return new Unix(Path.of(args[i + 1]));
            }
            if (ARG_PORT.equals(args[i])) {
                try {
                    return new Tcp(Integer.parseInt(args[i + 1]));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(ARG_PORT + " must be a number, got " + args[i + 1], e);
                }
            }
        }
        throw new IllegalArgumentException("Engine arguments must include " + ARG_SOCKET + " <path> or " + ARG_PORT + " <port>");
    }

    record Unix(Path socketPath) implements IpcEndpoint {

        public static final int MAX_PATH_BYTES = 103;

        public Unix {
            Objects.requireNonNull(socketPath, "socketPath");
            socketPath = socketPath.toAbsolutePath().normalize();
            int length = socketPath.toString().getBytes(StandardCharsets.UTF_8).length;
            if (length > MAX_PATH_BYTES) {
                throw new IllegalArgumentException("Unix socket path is " + length + " bytes, over the " + MAX_PATH_BYTES
                        + " byte OS limit; choose a shorter runtime directory: " + socketPath);
            }
        }

        @Override
        public SocketAddress address() {
            return UnixDomainSocketAddress.of(socketPath);
        }

        @Override
        public ProtocolFamily family() {
            return StandardProtocolFamily.UNIX;
        }

        @Override
        public List<String> toEngineArgs() {
            return List.of(ARG_SOCKET, socketPath.toString());
        }
    }

    record Tcp(int port) implements IpcEndpoint {

        public Tcp {
            if (port < 0 || port > 65_535) {
                throw new IllegalArgumentException("TCP port must be 0..65535, got " + port);
            }
        }

        public static Tcp ephemeral() {
            try (ServerSocketChannel probe = ServerSocketChannel.open(StandardProtocolFamily.INET)) {
                probe.bind(new InetSocketAddress(loopback(), 0));
                return new Tcp(((InetSocketAddress) probe.getLocalAddress()).getPort());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not reserve a loopback TCP port for the engine", e);
            }
        }

        public static InetAddress loopback() {
            try {
                return InetAddress.getByAddress("127.0.0.1", new byte[] {127, 0, 0, 1});
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SocketAddress address() {
            return new InetSocketAddress(loopback(), port);
        }

        @Override
        public ProtocolFamily family() {
            return StandardProtocolFamily.INET;
        }

        @Override
        public List<String> toEngineArgs() {
            return List.of(ARG_PORT, Integer.toString(port));
        }
    }
}
