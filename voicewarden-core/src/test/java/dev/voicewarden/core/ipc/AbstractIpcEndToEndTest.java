package dev.voicewarden.core.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.core.audio.FinalUtterance;
import dev.voicewarden.core.audio.LiveAudioChunk;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
abstract class AbstractIpcEndToEndTest {

    static final Duration HANDSHAKE = Duration.ofSeconds(5);

    final IpcAuthToken token = IpcAuthToken.generate();
    EchoEngineServer server;

    abstract IpcEndpoint newEndpoint() throws IOException;

    @AfterEach
    void stopServer() throws InterruptedException {
        if (server != null) {
            server.close();
        }
    }

    EchoEngineServer startServer(Duration authTimeout) throws IOException {
        server = new EchoEngineServer(newEndpoint(), token, authTimeout, "ENGLISH");
        return server;
    }

    @Test
    void authenticatesAndServesEveryRequestType() throws Exception {
        startServer(HANDSHAKE);
        RecordingResultListener listener = new RecordingResultListener();
        UUID player = UUID.randomUUID();
        try (EngineClient client = EngineClient.connect(server.endpoint(), token, HANDSHAKE, listener)) {
            assertTrue(client.isConnected());
            assertEquals(EchoEngineServer.VERSION, client.engineVersion());
            assertEquals("ENGLISH", client.engineProfile());

            short[] chunkPcm = IpcFrameCodecTest.ramp(3_200);
            assertTrue(client.sendLiveChunk(new LiveAudioChunk(player, 1L, 0, chunkPcm, false)));
            IpcMessage.TranscriptResult live = listener.nextTranscript();
            assertTrue(live.isLive());
            assertEquals("live 3200", live.text());
            assertEquals(new IpcMessage.AudioLiveChunk(player, 1L, 0, false, chunkPcm), server.received.take());

            short[] finalPcm = IpcFrameCodecTest.ramp(48_000);
            assertTrue(client.sendFinalUtterance(new FinalUtterance(player, 1L, finalPcm, 3_000), true));
            IpcMessage.TranscriptResult fin = listener.nextTranscript();
            assertTrue(fin.isFinal());
            assertEquals("final 48000", fin.text());
            assertEquals(new IpcMessage.AudioFinal(player, 1L, true, finalPcm), server.received.take());

            assertTrue(client.sendVerifyRequest(player, 1L, "naïve héllo", finalPcm));
            IpcMessage.TranscriptResult verified = listener.nextTranscript();
            assertTrue(verified.isVerificationResult());
            assertEquals(ConfirmationState.CONFIRMED, verified.confirmationState());
            assertEquals("naïve héllo", verified.text());
            server.received.take();

            assertTrue(client.sendDropUtterance(player, 1L));
            assertEquals(new IpcMessage.DropUtterance(player, 1L), server.received.poll(5, TimeUnit.SECONDS));

            assertTrue(client.requestStatus());
            IpcMessage.EngineStatusResponse status = listener.statuses.poll(5, TimeUnit.SECONDS);
            assertNotNull(status);
            assertTrue(status.ready());
            server.received.take();

            long before = client.lastPongMs();
            assertTrue(client.ping());
            assertNotNull(listener.pongs.poll(5, TimeUnit.SECONDS));
            assertTrue(client.averageLatencyMs() >= 0);
            assertTrue(client.lastPongMs() >= before);
            server.received.take();

            assertTrue(client.sendShutdown());
            assertInstanceOf(IpcMessage.Shutdown.class, server.received.poll(5, TimeUnit.SECONDS));
            assertTrue(listener.disconnected.await(5, TimeUnit.SECONDS));
            assertFalse(client.isConnected());
            assertFalse(client.ping());
        }
    }

    @Test
    void rejectsWrongToken() throws Exception {
        startServer(HANDSHAKE);
        IpcAuthException e = assertThrows(IpcAuthException.class,
                () -> EngineClient.connect(server.endpoint(), IpcAuthToken.generate(), HANDSHAKE, EngineResultListener.NONE));
        assertTrue(e.getMessage().contains("invalid auth token"));
        assertEquals("invalid auth token", server.rejections.poll(5, TimeUnit.SECONDS));

        try (EngineClient client = EngineClient.connect(server.endpoint(), token, HANDSHAKE, EngineResultListener.NONE)) {
            assertTrue(client.isConnected());
        }
    }

    @Test
    void rejectsConnectionThatSkipsAuthentication() throws Exception {
        startServer(HANDSHAKE);
        try (SocketChannel raw = IpcTransport.connect(server.endpoint())) {
            IpcFrameCodec.writeFrame(raw, new IpcMessage.Ping(1L));
            IpcMessage reply = IpcFrameCodec.readFrame(raw);
            assertInstanceOf(IpcMessage.AuthFail.class, reply);
            assertThrows(EOFException.class, () -> IpcFrameCodec.readFrame(raw));
        }
        assertTrue(server.rejections.poll(5, TimeUnit.SECONDS).contains("AUTH_REQ"));
    }

    @Test
    void silentConnectorTimesOutWithoutBlockingOthers() throws Exception {
        startServer(Duration.ofMillis(300));
        try (SocketChannel silent = IpcTransport.connect(server.endpoint())) {
            String rejection = server.rejections.poll(5, TimeUnit.SECONDS);
            assertNotNull(rejection);
            assertTrue(rejection.contains("handshake failed"), rejection);
            try (EngineClient client = EngineClient.connect(server.endpoint(), token, HANDSHAKE, EngineResultListener.NONE)) {
                assertTrue(client.ping());
            }
        }
    }

    @Test
    void clientReportsDisconnectWhenServerGoesAway() throws Exception {
        startServer(HANDSHAKE);
        RecordingResultListener listener = new RecordingResultListener();
        try (EngineClient client = EngineClient.connect(server.endpoint(), token, HANDSHAKE, listener)) {
            server.close();
            assertTrue(listener.disconnected.await(5, TimeUnit.SECONDS));
            assertFalse(client.isConnected());
            assertFalse(client.sendDropUtterance(UUID.randomUUID(), 1L));
        }
    }
}
