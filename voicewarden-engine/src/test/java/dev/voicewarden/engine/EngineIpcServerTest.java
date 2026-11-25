package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.core.audio.FinalUtterance;
import dev.voicewarden.core.audio.LiveAudioChunk;
import dev.voicewarden.core.ipc.EngineClient;
import dev.voicewarden.core.ipc.EngineResultListener;
import dev.voicewarden.core.ipc.IpcAuthToken;
import dev.voicewarden.core.ipc.IpcEndpoint;
import dev.voicewarden.core.ipc.IpcMessage;
import dev.voicewarden.core.ipc.IpcServerChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EngineIpcServerTest {

    private static final UUID PLAYER = new UUID(7, 7);
    private static final Duration HANDSHAKE = Duration.ofSeconds(5);

    private final IpcAuthToken token = IpcAuthToken.generate();
    private final Recorder recorder = new Recorder();
    private EngineIpcServer server;
    private CompletableFuture<Integer> running;
    private EngineClient client;
    private Path socketDir;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (socketDir != null) {
            ArchiveExtractor.deleteRecursively(socketDir);
        }
    }

    private IpcEndpoint start(IpcEndpoint endpoint) throws IOException {
        IpcServerChannel channel = IpcServerChannel.bind(endpoint, token, VoiceWardenEngineMain.ENGINE_VERSION, "ENGLISH");
        server = new EngineIpcServer(channel, "ENGLISH", 2, 16, Duration.ofSeconds(30));
        running = CompletableFuture.supplyAsync(server::run);
        return channel.endpoint();
    }

    private void connect(IpcEndpoint endpoint) throws IOException {
        client = EngineClient.connect(endpoint, token, HANDSHAKE, recorder);
    }

    private IpcMessage.TranscriptResult nextResult() throws InterruptedException {
        IpcMessage.TranscriptResult result = recorder.transcripts.poll(5, TimeUnit.SECONDS);
        assertNotNull(result, "no transcript arrived");
        return result;
    }

    private IpcMessage.EngineStatusResponse nextStatus() throws InterruptedException {
        IpcMessage.EngineStatusResponse status = recorder.statuses.poll(5, TimeUnit.SECONDS);
        assertNotNull(status, "no status arrived");
        return status;
    }

    @Test
    void servesTheFullProtocolOverTcp() throws Exception {
        IpcEndpoint endpoint = start(new IpcEndpoint.Tcp(0));
        FakeSpeechEngine engine = new FakeSpeechEngine();
        connect(endpoint);
        assertEquals(VoiceWardenEngineMain.ENGINE_VERSION, client.engineVersion());

        assertTrue(client.ping());
        assertNotNull(recorder.pongs.poll(5, TimeUnit.SECONDS), "PING must be answered before models load");
        assertTrue(client.requestStatus());
        IpcMessage.EngineStatusResponse loading = nextStatus();
        assertFalse(loading.ready());
        assertEquals("ENGLISH", loading.profile());

        assertTrue(client.sendLiveChunk(new LiveAudioChunk(PLAYER, 1, 0, new short[50], false)));
        assertTrue(client.requestStatus());
        nextStatus();
        assertEquals(1, server.droppedBeforeReady());

        assertTrue(server.attachEngine(engine));
        assertTrue(server.isReady());

        assertTrue(client.sendLiveChunk(new LiveAudioChunk(PLAYER, 2, 0, new short[160], false)));
        IpcMessage.TranscriptResult live = nextResult();
        assertTrue(live.isLive());
        assertFalse(live.isFinal());
        assertEquals("live 160", live.text());
        assertEquals(2, live.utteranceId());

        assertTrue(client.sendFinalUtterance(new FinalUtterance(PLAYER, 2, new short[320], 20), true));
        IpcMessage.TranscriptResult fin = nextResult();
        assertTrue(fin.isFinal());
        assertEquals(ConfirmationState.CONFIRMED, fin.confirmationState());
        assertEquals("final 320", fin.text());

        assertTrue(client.sendVerifyRequest(PLAYER, 2, "you idiot", new short[320]));
        IpcMessage.TranscriptResult verified = nextResult();
        assertTrue(verified.isVerificationResult());
        assertEquals(ConfirmationState.CONFIRMED, verified.confirmationState());

        assertTrue(client.sendDropUtterance(PLAYER, 3));
        assertTrue(client.ping());
        assertNotNull(recorder.pongs.poll(5, TimeUnit.SECONDS));
        assertTrue(engine.drops.contains("7:3"));

        assertTrue(client.requestStatus());
        IpcMessage.EngineStatusResponse status = nextStatus();
        assertTrue(status.ready());
        assertEquals(1, status.processedLive());
        assertEquals(1, status.processedFinal());
        assertEquals(1, status.processedVerify());
        assertEquals(0, status.queuedJobs());

        assertTrue(client.sendShutdown());
        assertEquals(EngineIpcServer.EXIT_OK, running.get(5, TimeUnit.SECONDS));
        server.close();
        assertTrue(engine.closed.get(), "shutdown must release the speech engine");
    }

    @Test
    void servesOverUnixSocketAndAcceptsReconnects() throws Exception {
        Path base = Path.of("/tmp");
        socketDir = Files.createTempDirectory(Files.isDirectory(base) ? base : Path.of(System.getProperty("java.io.tmpdir")),
                "vweng");
        IpcEndpoint endpoint = start(new IpcEndpoint.Unix(socketDir.resolve("engine.sock")));
        server.attachEngine(new FakeSpeechEngine());
        connect(endpoint);
        assertTrue(client.sendFinalUtterance(new FinalUtterance(PLAYER, 1, new short[64], 20), false));
        assertEquals("final 64", nextResult().text());
        client.close();
        assertTrue(recorder.disconnects.poll(5, TimeUnit.SECONDS) != null);

        connect(endpoint);
        assertTrue(client.sendFinalUtterance(new FinalUtterance(PLAYER, 2, new short[65], 20), false));
        assertEquals("final 65", nextResult().text());
        assertFalse(running.isDone());
    }

    @Test
    void startupFailureEndsTheServerWithFailureCode() throws Exception {
        IpcEndpoint endpoint = start(new IpcEndpoint.Tcp(0));
        connect(endpoint);
        server.failStartup(new IllegalStateException("model missing"));
        assertEquals(EngineIpcServer.EXIT_STARTUP_FAILED, running.get(5, TimeUnit.SECONDS));
    }

    @Test
    void engineAttachedAfterCloseIsReleasedImmediately() throws Exception {
        start(new IpcEndpoint.Tcp(0));
        server.close();
        FakeSpeechEngine late = new FakeSpeechEngine();
        assertFalse(server.attachEngine(late));
        assertTrue(late.closed.get());
        assertEquals(EngineIpcServer.EXIT_OK, running.get(5, TimeUnit.SECONDS));
    }

    private static final class Recorder implements EngineResultListener {
        final BlockingQueue<IpcMessage.TranscriptResult> transcripts = new LinkedBlockingQueue<>();
        final BlockingQueue<IpcMessage.EngineStatusResponse> statuses = new LinkedBlockingQueue<>();
        final BlockingQueue<Long> pongs = new LinkedBlockingQueue<>();
        final BlockingQueue<String> disconnects = new LinkedBlockingQueue<>();

        @Override
        public void onTranscript(IpcMessage.TranscriptResult result) {
            transcripts.add(result);
        }

        @Override
        public void onPong(long nonce, double roundTripMs) {
            pongs.add(nonce);
        }

        @Override
        public void onStatus(IpcMessage.EngineStatusResponse status) {
            statuses.add(status);
        }

        @Override
        public void onDisconnected(Throwable cause) {
            disconnects.add(String.valueOf(cause));
        }
    }
}
