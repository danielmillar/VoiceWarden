package dev.voicewarden.core.ipc;

import dev.voicewarden.api.ConfirmationState;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

final class EchoEngineServer implements AutoCloseable {

    static final String VERSION = "test-engine-1";

    final IpcServerChannel server;
    final BlockingQueue<IpcMessage> received = new LinkedBlockingQueue<>();
    final BlockingQueue<String> rejections = new LinkedBlockingQueue<>();
    private final List<IpcConnection> connections = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    EchoEngineServer(IpcEndpoint endpoint, IpcAuthToken token, Duration authTimeout, String profile) throws IOException {
        server = IpcServerChannel.bind(endpoint, token, authTimeout, VERSION, profile).onRejection(rejections::add);
        acceptor = Thread.ofPlatform().daemon().name("echo-engine-acceptor").start(this::acceptLoop);
    }

    IpcEndpoint endpoint() {
        return server.endpoint();
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            try {
                IpcConnection connection = server.accept();
                connections.add(connection);
                Thread.ofPlatform().daemon().name("echo-engine-conn").start(() -> serve(connection));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(IpcConnection connection) {
        try (connection) {
            while (true) {
                IpcMessage message = connection.receive();
                received.add(message);
                IpcMessage reply = reply(message);
                if (reply != null) {
                    connection.send(reply);
                }
                if (message instanceof IpcMessage.Shutdown) {
                    return;
                }
            }
        } catch (IOException e) {
            return;
        }
    }

    static IpcMessage reply(IpcMessage message) {
        return switch (message) {
            case IpcMessage.Ping ping -> new IpcMessage.Pong(ping.nonce());
            case IpcMessage.AudioLiveChunk chunk -> new IpcMessage.TranscriptResult(chunk.playerId(), chunk.utteranceId(),
                    false, true, false, ConfirmationState.UNCHECKED, "live " + chunk.pcm16().length, "en", 5);
            case IpcMessage.AudioFinal fin -> new IpcMessage.TranscriptResult(fin.playerId(), fin.utteranceId(),
                    true, false, false, ConfirmationState.UNCHECKED, "final " + fin.pcm16().length, "en", 9);
            case IpcMessage.VerifyRequest verify -> new IpcMessage.TranscriptResult(verify.playerId(),
                    verify.utteranceId(), true, false, true, ConfirmationState.CONFIRMED,
                    verify.primaryTranscript(), "en", 11);
            case IpcMessage.EngineStatusRequest request ->
                    new IpcMessage.EngineStatusResponse(1_000L, 1L, 1L, 1L, 0L, 0, "ENGLISH", true);
            default -> null;
        };
    }

    @Override
    public void close() throws InterruptedException {
        server.close();
        connections.forEach(IpcConnection::close);
        acceptor.join(2_000);
    }
}
