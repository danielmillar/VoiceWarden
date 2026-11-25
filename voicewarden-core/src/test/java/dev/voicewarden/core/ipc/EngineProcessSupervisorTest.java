package dev.voicewarden.core.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.core.audio.FinalUtterance;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

@Timeout(90)
class EngineProcessSupervisorTest {

    private static final Duration READY = Duration.ofSeconds(30);

    private final IpcAuthToken token = IpcAuthToken.generate();
    private final RecordingResultListener results = new RecordingResultListener();
    private final RecordingEvents events = new RecordingEvents();
    private final BlockingQueue<String> logLines = new LinkedBlockingQueue<>();
    private Path dir;
    private EngineProcessSupervisor supervisor;

    @BeforeEach
    void createDir() throws IOException {
        dir = TestSocketDirs.create();
    }

    @AfterEach
    void cleanUp() throws IOException {
        if (supervisor != null) {
            supervisor.close();
        }
        TestSocketDirs.delete(dir);
    }

    @Test
    void restartsCrashedChildAndResumesServing() throws Exception {
        assumeFalse(IpcEndpoint.isWindows());
        supervisor = start(config(new IpcEndpoint.Unix(dir.resolve("engine.sock"))).build());
        assertTrue(supervisor.awaitReady(READY), "engine never became ready: " + logLines);
        long firstPid = supervisor.pid().orElseThrow();
        assertEquals(firstPid, events.ready.poll(5, TimeUnit.SECONDS));
        assertTranscriptRoundTrip(supervisor.client().orElseThrow());
        awaitLog("fake-engine listening pid=" + firstPid);
        awaitLog("fake-engine stderr ready");

        assertTrue(supervisor.client().orElseThrow().sendDropUtterance(FakeEngineMain.CRASH, 0L));

        String lost = events.lost.poll(20, TimeUnit.SECONDS);
        assertNotNull(lost, "crash was never detected");
        assertTrue(lost.contains("exited with code " + FakeEngineMain.CRASH_EXIT_CODE), lost);
        Long secondPid = events.ready.poll(30, TimeUnit.SECONDS);
        assertNotNull(secondPid, "engine was never restarted: " + logLines);
        assertNotEquals(firstPid, secondPid);
        assertEquals(1, supervisor.restartCount());
        assertTrue(supervisor.isReady());
        assertTranscriptRoundTrip(supervisor.client().orElseThrow());
        assertTrue(supervisor.client().orElseThrow().ping());
    }

    @Test
    void restartsHungChildAfterMissedHeartbeats() throws Exception {
        supervisor = start(config(IpcEndpoint.Tcp.ephemeral()).build());
        assertTrue(supervisor.awaitReady(READY), "engine never became ready: " + logLines);
        long firstPid = supervisor.pid().orElseThrow();
        events.ready.poll(5, TimeUnit.SECONDS);

        assertTrue(supervisor.client().orElseThrow().sendDropUtterance(FakeEngineMain.HANG, 0L));

        String lost = events.lost.poll(20, TimeUnit.SECONDS);
        assertNotNull(lost, "hang was never detected");
        assertTrue(lost.contains("missed heartbeats"), lost);
        Long secondPid = events.ready.poll(30, TimeUnit.SECONDS);
        assertNotNull(secondPid, "engine was never restarted: " + logLines);
        assertNotEquals(firstPid, secondPid);
        awaitCondition(() -> ProcessHandle.of(firstPid).map(p -> !p.isAlive()).orElse(true), "hung engine still alive");
        assertTranscriptRoundTrip(supervisor.client().orElseThrow());
    }

    @Test
    void restartsFrozenChildEvenWhileAudioWriterIsBlocked() throws Exception {
        supervisor = start(config(IpcEndpoint.Tcp.ephemeral()).build());
        assertTrue(supervisor.awaitReady(READY), "engine never became ready: " + logLines);
        long firstPid = supervisor.pid().orElseThrow();
        events.ready.poll(5, TimeUnit.SECONDS);
        EngineClient frozen = supervisor.client().orElseThrow();
        assertTrue(frozen.sendDropUtterance(FakeEngineMain.FREEZE, 0L));
        awaitLog("fake-engine frozen");
        Thread flooder = floodUntilBlocked(frozen);

        String lost = events.lost.poll(30, TimeUnit.SECONDS);
        assertNotNull(lost, "frozen engine was never detected");
        Long secondPid = events.ready.poll(30, TimeUnit.SECONDS);
        assertNotNull(secondPid, "engine was never restarted: " + logLines);
        assertNotEquals(firstPid, secondPid);
        flooder.join(10_000);
        assertFalse(flooder.isAlive(), "blocked audio writer was never released");
        assertTranscriptRoundTrip(supervisor.client().orElseThrow());
    }

    @Test
    void closeReturnsPromptlyWhileAudioWriterIsBlocked() throws Exception {
        supervisor = start(config(IpcEndpoint.Tcp.ephemeral()).heartbeatInterval(Duration.ofSeconds(30)).build());
        assertTrue(supervisor.awaitReady(READY), "engine never became ready: " + logLines);
        long pid = supervisor.pid().orElseThrow();
        EngineClient frozen = supervisor.client().orElseThrow();
        assertTrue(frozen.sendDropUtterance(FakeEngineMain.FREEZE, 0L));
        awaitLog("fake-engine frozen");
        Thread flooder = floodUntilBlocked(frozen);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> supervisor.close());

        assertEquals(EngineProcessSupervisor.State.CLOSED, supervisor.state());
        awaitCondition(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "frozen engine still alive");
        flooder.join(10_000);
        assertFalse(flooder.isAlive(), "blocked audio writer was never released");
    }

    @Test
    void givesUpAfterTooManyRestartsInWindow() throws Exception {
        assumeFalse(IpcEndpoint.isWindows());
        supervisor = start(config(new IpcEndpoint.Unix(dir.resolve("engine.sock")))
                .extraEngineArgs(List.of(FakeEngineMain.EXIT_ON_START))
                .maxRestartsInWindow(2)
                .build());
        assertTrue(events.gaveUp.await(60, TimeUnit.SECONDS), "supervisor never gave up: " + logLines);
        assertFalse(supervisor.awaitReady(Duration.ofMillis(10)));
        assertEquals(EngineProcessSupervisor.State.FAILED, supervisor.state());
        assertEquals(2, supervisor.restartCount());
        assertEquals(List.of(Duration.ofMillis(50), Duration.ofMillis(100)), List.copyOf(events.delays));
        awaitLog("fake-engine refusing to start");
    }

    @Test
    void closeShutsDownOnlyItsOwnChild() throws Exception {
        assumeFalse(IpcEndpoint.isWindows());
        supervisor = start(config(new IpcEndpoint.Unix(dir.resolve("engine.sock"))).build());
        assertTrue(supervisor.awaitReady(READY), "engine never became ready: " + logLines);
        long pid = supervisor.pid().orElseThrow();
        assertEquals(pid, ProcessHandle.current().children()
                .filter(child -> child.pid() == pid).findFirst().orElseThrow().pid());

        supervisor.close();

        assertEquals(EngineProcessSupervisor.State.CLOSED, supervisor.state());
        awaitCondition(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "engine still alive after close");
        awaitLog("fake-engine shutting down");
        assertTrue(events.lost.isEmpty(), "graceful shutdown must not be reported as a loss");
        assertEquals(0, supervisor.restartCount());
    }

    @Test
    void runsChildAtLoweredPriorityWhenNiceExists() throws Exception {
        EngineProcessConfig config = config(IpcEndpoint.Tcp.ephemeral()).build();
        assumeFalse(config.resolveNice().isEmpty());
        supervisor = start(config);
        assertTrue(supervisor.awaitReady(READY), "engine never became ready: " + logLines);
        Path stat = Path.of("/proc", Long.toString(supervisor.pid().orElseThrow()), "stat");
        assumeTrue(Files.exists(stat));
        String content = Files.readString(stat);
        String[] fieldsAfterCommand = content.substring(content.lastIndexOf(')') + 2).split(" ");
        assertEquals("10", fieldsAfterCommand[16]);
    }

    private Thread floodUntilBlocked(EngineClient client) throws InterruptedException {
        AtomicLong sent = new AtomicLong();
        Thread flooder = new Thread(() -> {
            FinalUtterance big = new FinalUtterance(UUID.randomUUID(), 1L, new short[160_000], 10_000);
            while (client.sendFinalUtterance(big, false)) {
                sent.incrementAndGet();
            }
        }, "test-audio-flooder");
        flooder.setDaemon(true);
        flooder.start();
        long previous = -1;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (sent.get() != previous) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("audio writer never blocked on the frozen engine");
            }
            previous = sent.get();
            TimeUnit.MILLISECONDS.sleep(300);
        }
        assertTrue(flooder.isAlive(), "audio writer stopped instead of blocking");
        return flooder;
    }

    private void assertTranscriptRoundTrip(EngineClient client) throws InterruptedException {
        UUID player = UUID.randomUUID();
        assertTrue(client.sendFinalUtterance(new FinalUtterance(player, 9L, new short[1_600], 100), false));
        IpcMessage.TranscriptResult result = results.nextTranscript();
        assertEquals(player, result.playerId());
        assertEquals("final 1600", result.text());
        assertEquals(ConfirmationState.UNCHECKED, result.confirmationState());
    }

    private EngineProcessSupervisor start(EngineProcessConfig config) {
        EngineProcessSupervisor created = new EngineProcessSupervisor(
                config, token, results, events, (stream, pid, line) -> logLines.add(stream + " " + line));
        assertFalse(String.join(" ", created.command()).contains(token.toHex()));
        created.start();
        return created;
    }

    private EngineProcessConfig.Builder config(IpcEndpoint endpoint) throws URISyntaxException {
        return EngineProcessConfig.builder(new EngineLaunchTarget.MainClass(testClasspath(), FakeEngineMain.class.getName()),
                        endpoint)
                .jvmArgs(List.of("-Xmx64m", "-XX:TieredStopAtLevel=1", "-Xshare:auto"))
                .profile("ENGLISH")
                .modelsDir(dir.resolve("models"))
                .threads(1)
                .startupTimeout(Duration.ofSeconds(30))
                .handshakeTimeout(Duration.ofSeconds(5))
                .heartbeatInterval(Duration.ofMillis(200))
                .maxMissedHeartbeats(3)
                .initialBackoff(Duration.ofMillis(50))
                .maxBackoff(Duration.ofSeconds(1))
                .restartWindow(Duration.ofMinutes(1))
                .shutdownGrace(Duration.ofSeconds(5));
    }

    private static String testClasspath() throws URISyntaxException {
        List<Class<?>> anchors = List.of(
                FakeEngineMain.class, IpcMessage.class, ConfirmationState.class, LoggerFactory.class);
        return Stream.of(anchors.toArray(Class<?>[]::new))
                .map(type -> type.getProtectionDomain().getCodeSource().getLocation())
                .map(url -> {
                    try {
                        return Path.of(url.toURI()).toString();
                    } catch (URISyntaxException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .distinct()
                .collect(Collectors.joining(File.pathSeparator));
    }

    private void awaitLog(String fragment) throws InterruptedException {
        awaitCondition(() -> logLines.stream().anyMatch(line -> line.contains(fragment)),
                "engine never logged '" + fragment + "'");
    }

    private void awaitCondition(BooleanSupplier condition, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(failure + "; engine output: " + logLines);
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
    }

    private static final class RecordingEvents implements EngineSupervisorListener {
        final BlockingQueue<Long> ready = new LinkedBlockingQueue<>();
        final BlockingQueue<String> lost = new LinkedBlockingQueue<>();
        final BlockingQueue<Duration> delays = new LinkedBlockingQueue<>();
        final CountDownLatch gaveUp = new CountDownLatch(1);

        @Override
        public void onEngineReady(EngineClient client, long pid) {
            ready.add(pid);
        }

        @Override
        public void onEngineLost(long pid, String reason) {
            lost.add(reason);
        }

        @Override
        public void onRestartScheduled(int restartsInWindow, Duration delay) {
            delays.add(delay);
        }

        @Override
        public void onGaveUp(String reason) {
            gaveUp.countDown();
        }
    }
}
