package dev.danielmillar.voicewarden.voice;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.danielmillar.voicewarden.audio.AudioSegment;
import dev.danielmillar.voicewarden.audio.Decimator;
import dev.danielmillar.voicewarden.audio.PcmBuffer;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.health.Metrics;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AudioIngestServiceTest {
    @TempDir Path folder;
    private PluginConfig config;
    private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final Metrics metrics = new Metrics();
    private final List<AudioSegment> segments = new ArrayList<>();
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger decoded = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private final List<short[]> frames = new ArrayList<>();
    private final List<byte[]> packets = new ArrayList<>();
    private AudioIngestService ingest;
    private ServerPlayer speaker;
    private int amplitude = 10_000;
    private double defaultMinLevelDbfs;

    @BeforeEach void setup() throws Exception {
        config = Fakes.config(folder);
        defaultMinLevelDbfs = config.audio().minLevelDbfs();
        config = Fakes.with(config, "audio", new PluginConfig.Audio(Duration.ofSeconds(1), Duration.ZERO,
                Duration.ofSeconds(2), -90, false, 1, Duration.ofSeconds(10), 25));
        speaker = speaker(id, false);
        setupIngest(new Fakes.Executor(true));
    }

    private void setupIngest(java.util.concurrent.ExecutorService executor) {
        ingest = new AudioIngestService(() -> config, metrics, executor, segments::add,
                key -> (Player) speaker.getPlayer(), p -> p.hasPermission(AudioIngestService.BYPASS_PERMISSION),
                Logger.getLogger("AudioIngestServiceTest"));
        ingest.setApi(Fakes.proxy(VoicechatApi.class, (p, m, a) -> {
            if (!m.getName().equals("createDecoder")) throw new AssertionError(m);
            created.incrementAndGet();
            boolean[] isClosed = {false};
            return Fakes.proxy(OpusDecoder.class, (decoder, method, args) -> switch (method.getName()) {
                case "decode" -> {
                    assertFalse(isClosed[0], "decoder used after close");
                    packets.add(((byte[]) args[0]).clone());
                    short[] frame = new short[960];
                    int index = decoded.getAndIncrement();
                    if (((byte[]) args[0])[0] != 0) {
                        for (int i = 0; i < frame.length; i++) {
                            frame[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * 1_000 * (index * 960 + i) / 48_000));
                        }
                    }
                    frames.add(frame);
                    yield frame;
                }
                case "isClosed" -> isClosed[0];
                case "resetState" -> null;
                case "close" -> { assertFalse(isClosed[0]); isClosed[0] = true; closed.incrementAndGet(); yield null; }
                default -> throw new AssertionError(method);
            });
        }));
        ingest.setAccepting(true);
    }

    private ServerPlayer speaker(UUID uuid, boolean bypass) {
        Player player = Fakes.proxy(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getName" -> "Alice";
            case "getUniqueId" -> uuid;
            case "hasPermission" -> bypass;
            default -> throw new AssertionError(m);
        });
        Position position = Fakes.proxy(Position.class, (p, m, a) -> switch (m.getName()) {
            case "getX" -> 1.0;
            case "getY" -> 64.0;
            case "getZ" -> 3.0;
            default -> throw new AssertionError(m);
        });
        ServerLevel level = Fakes.proxy(ServerLevel.class, (p, m, a) -> {
            if (m.getName().equals("getServerLevel")) return new Object();
            throw new AssertionError(m);
        });
        return Fakes.proxy(ServerPlayer.class, (p, m, a) -> switch (m.getName()) {
            case "getUuid" -> uuid;
            case "getPlayer" -> player;
            case "getPosition" -> position;
            case "getServerLevel" -> level;
            default -> throw new AssertionError(m);
        });
    }

    @Test void emptyPacketEndsUtteranceWithCorrectSamplesAndName() {
        send(5, (byte) 1);
        assertTrue(segments.isEmpty());
        end();
        assertEquals(1, segments.size());
        AudioSegment segment = segments.getFirst();
        assertEquals(5 * 320, segment.pcm().length);
        assertEquals(16_000, segment.sampleRate());
        assertEquals("Alice", segment.playerName());
        assertEquals(id, segment.playerId());
        assertEquals(64, segment.location().y());
        assertEquals(1, metrics.utterances.sum());
    }

    @Test void shortUtteranceIsDroppedAndCounted() {
        audio("minUtterance", Duration.ofMillis(100));
        send(4, (byte) 1);
        end();
        assertTrue(segments.isEmpty());
        assertEquals(1, metrics.utterancesTooShort.sum());
    }

    @Test void rapidPushToTalkBurstsRetainLocationAndWhisperMetadata() {
        send(5, (byte) 1);
        end();
        for (int i = 0; i < 5; i++) ingest.onMicrophonePacket(speaker, new byte[]{1}, true);
        end();
        assertEquals(2, segments.size());
        assertNotNull(segments.get(1).location());
        assertEquals(64, segments.get(1).location().y());
        assertTrue(segments.get(1).whispering());
    }

    @Test void staleTimerDecisionDoesNotSplitResumedSpeech() throws Exception {
        Fakes.Executor executor = new Fakes.Executor(false);
        setupIngest(executor);
        send(5, (byte) 1);
        executor.drain();
        session().requestSilenceFlush(session().lastPacketNanos);
        send(5, (byte) 1); // Arrives before the queued timer decision can be decoded.
        end();
        executor.drain();
        assertEquals(1, segments.size());
        assertEquals(10 * 320, segments.getFirst().pcm().length);
        assertFalse(session().flushRequested.get());
    }

    @Test void stoppingCaptureStillFlushesWhenATimerDecisionIsPending() throws Exception {
        Fakes.Executor executor = new Fakes.Executor(false);
        setupIngest(executor);
        send(5, (byte) 1);
        executor.drain();
        session().requestSilenceFlush(session().lastPacketNanos);
        send(5, (byte) 1);
        ingest.setAccepting(false);
        executor.drain();
        assertEquals(1, segments.size());
        assertEquals(10 * 320, segments.getFirst().pcm().length);
        assertFalse(session().segmentOpen);
    }

    @Test void quietUtteranceIsDroppedAndCounted() {
        audio("minLevelDbfs", -40.0);
        send(10, (byte) 0);
        end();
        assertTrue(segments.isEmpty());
        assertEquals(1, metrics.utterancesTooQuiet.sum());
    }

    @Test void defaultSilenceFloorAdmitsPhysicalWhispersWithoutAmplifyingEvidence() throws Exception {
        audio("minLevelDbfs", defaultMinLevelDbfs);
        amplitude = 50; // About -59 dBFS, below the old -50 dBFS gate.
        send(10, (byte) 1);
        end();
        assertEquals(1, segments.size());
        assertEquals(0, metrics.utterancesTooQuiet.sum());
        assertFalse(segments.getFirst().whispering()); // Physical whispering does not set SVC's whisper-key flag.
        for (short sample : segments.getFirst().pcm()) assertTrue(Math.abs((int) sample) <= 50);
    }

    @Test void audioBelowDefaultSilenceFloorIsStillRejected() throws Exception {
        audio("minLevelDbfs", defaultMinLevelDbfs);
        amplitude = 5;
        send(10, (byte) 1);
        end();
        assertTrue(segments.isEmpty());
        assertEquals(1, metrics.utterancesTooQuiet.sum());
    }

    @Test void longSpeechSplitsWithoutLosingOrDuplicatingAnySamples() {
        send(250, (byte) 1);
        end();
        assertTrue(segments.size() > 1);
        assertTrue(segments.stream().allMatch(s -> s.pcm().length <= 32_000));
        short[] actual = new short[segments.stream().mapToInt(s -> s.pcm().length).sum()];
        int offset = 0;
        for (AudioSegment segment : segments) {
            System.arraycopy(segment.pcm(), 0, actual, offset, segment.pcm().length);
            offset += segment.pcm().length;
        }
        PcmBuffer expected = new PcmBuffer(1);
        Decimator decimator = new Decimator();
        frames.forEach(frame -> decimator.process(frame, frame.length, expected));
        assertEquals(250 * 320, actual.length);
        assertArrayEquals(expected.copy(0, expected.size()), actual);
        for (int i = 1; i < segments.size(); i++) {
            assertEquals(segments.get(i - 1).spokenAt().plus(segments.get(i - 1).duration()), segments.get(i).spokenAt());
        }
    }

    @Test void minimumUtteranceDoesNotDiscardPiecesOfLongContinuousSpeech() {
        audio("minUtterance", Duration.ofMillis(300));
        send(250, (byte) 1);
        end();
        assertEquals(250 * 320, segments.stream().mapToInt(s -> s.pcm().length).sum());
        assertEquals(0, metrics.utterancesTooShort.sum());
    }

    @Test void shortTailOfAlreadySplitUtteranceIsPreserved() {
        audio("minUtterance", Duration.ofMillis(300));
        send(99, (byte) 1);
        send(1, (byte) 0); // Force the quietest cut near the end of the two-second segment.
        send(3, (byte) 1);
        end();
        assertEquals(103 * 320, segments.stream().mapToInt(s -> s.pcm().length).sum());
        assertEquals(0, metrics.utterancesTooShort.sum());
    }

    @Test void silenceSweepFlushesPendingSpeechWithoutSleeping() throws Exception {
        send(5, (byte) 1);
        session().lastPacketNanos = System.nanoTime() - Duration.ofSeconds(2).toNanos();
        sweep();
        assertEquals(1, segments.size());
        assertFalse(session().segmentOpen);
        sweep();
        assertEquals(1, segments.size());
    }

    @Test void bypassPlayerIsIgnoredWithoutAllocatingDecoder() {
        speaker = speaker(id, true);
        send(5, (byte) 1);
        end();
        assertEquals(0, created.get());
        assertTrue(segments.isEmpty());
    }

    @Test void acceptingFalseIgnoresPacketsAndFlushesExistingUtterance() {
        send(3, (byte) 1);
        ingest.setAccepting(false);
        send(5, (byte) 1);
        end();
        assertEquals(3, decoded.get());
        assertEquals(3, metrics.packets.sum());
        assertEquals(960, segments.getFirst().pcm().length);
    }

    @Test void disconnectFlushesQueuedFramesAndClosesDecoder() {
        Fakes.Executor executor = new Fakes.Executor(false);
        setupIngest(executor);
        send(7, (byte) 1);
        ingest.onDisconnect(id);
        assertEquals(0, created.get());
        executor.drain();
        assertEquals(7 * 320, segments.getFirst().pcm().length);
        assertEquals(1, closed.get());
    }

    @Test void inboxCapDropsExcessFramesAndSchedulesOnlyOneDrain() {
        Fakes.Executor executor = new Fakes.Executor(false);
        setupIngest(executor);
        send(100, (byte) 1);
        assertEquals(75, metrics.packetsDropped.sum());
        assertEquals(1, executor.pending());
        assertEquals(0, created.get());
        end();
        executor.drain();
        assertEquals(25 * 320, segments.getFirst().pcm().length);
    }

    @Test void repeatedEmptyPacketsCannotGrowInboxWithoutBound() throws Exception {
        Fakes.Executor executor = new Fakes.Executor(false);
        setupIngest(executor);
        send(1, (byte) 1);
        for (int i = 0; i < 10_000; i++) end();
        Field inbox = SpeakerSession.class.getDeclaredField("inbox");
        inbox.setAccessible(true);
        // Counted items are capped at max+1 (one reserved terminator); each uncounted burst-start marker precedes a
        // counted audio frame, so the raw queue is bounded too.
        assertTrue(((java.util.Queue<?>) inbox.get(session())).size() <= 2 * config.audio().maxBufferedFrames() + 1);
        executor.drain();
        assertEquals(1, segments.size());
    }

    @Test void decoderIsLazyReleasedAfterIdleTimeoutAndRecreated() throws Exception {
        assertEquals(0, created.get());
        end();
        assertEquals(0, created.get());
        send(5, (byte) 1);
        end();
        assertEquals(1, created.get());
        assertEquals(0, closed.get());
        session().lastPacketNanos = System.nanoTime() - Duration.ofSeconds(20).toNanos();
        sweep();
        assertEquals(1, closed.get());
        assertFalse(session().decoderAllocated);
        send(2, (byte) 1);
        end();
        assertEquals(2, created.get());
        ingest.close();
        assertEquals(2, closed.get());
    }

    @Test void packetArrayIsCopiedBeforeDeferredDecode() {
        Fakes.Executor executor = new Fakes.Executor(false);
        setupIngest(executor);
        byte[] packet = {1, 2, 3};
        ingest.onMicrophonePacket(speaker, packet, false);
        Arrays.fill(packet, (byte) 0);
        end();
        executor.drain();
        assertArrayEquals(new byte[]{1, 2, 3}, packets.getFirst());
    }

    @Test void hundredsOfSpeakersQueueWorkWithoutInlineDecode() {
        Fakes.Executor executor = new Fakes.Executor(false);
        setupIngest(executor);
        for (int i = 1; i <= 256; i++) {
            ServerPlayer player = speaker(new UUID(0, i), false);
            ingest.onMicrophonePacket(player, new byte[]{1}, false);
            ingest.onMicrophonePacket(player, new byte[0], false);
        }
        assertEquals(256, executor.pending());
        assertEquals(0, created.get());
        executor.drain();
        assertEquals(256, segments.size());
        assertEquals(256, created.get());
        ingest.close();
        executor.drain();
        assertEquals(256, closed.get());
    }

    @Test void busyDecodeThreadDoesNotHoldUpPacketOrDisconnectCaller() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                blocked.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            setupIngest(executor);
            send(5, (byte) 1);
            end();
            ingest.onDisconnect(id);
            assertEquals(0, created.get());
            release.countDown();
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals(1, segments.size());
            assertEquals(1, closed.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test void ignoredWhispersDoNotAllocateOrCountPackets() {
        audio("ignoreWhispers", true);
        ingest.onMicrophonePacket(speaker, new byte[]{1}, true);
        assertEquals(0, created.get());
        assertEquals(0, metrics.packets.sum());
    }

    @Test void executorShutdownStillDrainsCloseAfterMoreThanFiftyFrames() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                blocked.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            audio("maxBufferedFrames", 100);
            setupIngest(executor);
            send(75, (byte) 1);
            ingest.onDisconnect(id);
            executor.shutdown();
            release.countDown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, closed.get(), "CLOSE marker must survive drain yielding during shutdown");
            assertEquals(75 * 320, segments.stream().mapToInt(s -> s.pcm().length).sum());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private void audio(String field, Object value) { config = Fakes.with(config, "audio", Fakes.with(config.audio(), field, value)); }
    private void send(int count, byte value) {
        for (int i = 0; i < count; i++) ingest.onMicrophonePacket(speaker, new byte[]{value}, false);
    }
    private void end() { ingest.onMicrophonePacket(speaker, new byte[0], false); }
    private void sweep() throws Exception {
        var method = AudioIngestService.class.getDeclaredMethod("sweep");
        method.setAccessible(true);
        method.invoke(ingest);
    }
    @SuppressWarnings("unchecked")
    private SpeakerSession session() throws Exception {
        Field field = AudioIngestService.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return ((Map<UUID, SpeakerSession>) field.get(ingest)).get(id);
    }
}
