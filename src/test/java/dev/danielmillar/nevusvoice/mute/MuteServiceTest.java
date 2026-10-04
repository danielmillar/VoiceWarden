package dev.danielmillar.nevusvoice.mute;

import dev.danielmillar.nevusvoice.config.Messages;
import dev.danielmillar.nevusvoice.config.PluginConfig;
import dev.danielmillar.nevusvoice.luckperms.LuckPermsHook;
import dev.danielmillar.nevusvoice.player.OnlinePlayers;
import dev.danielmillar.nevusvoice.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.UserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MuteServiceTest {
    @TempDir Path folder;
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);
    private final AtomicInteger luckPermsCalls = new AtomicInteger();
    private PluginConfig config;
    private Messages messages;
    private LuckPermsHook hook;
    private ScheduledThreadPoolExecutor timer;

    @BeforeEach void setup() throws Exception {
        config = Fakes.config(folder);
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "notifyPlayer", false));
        messages = Messages.load(folder.resolve("messages.yml"));
        UserManager users = Fakes.proxy(UserManager.class, (p, m, a) -> {
            if (m.getName().equals("modifyUser")) {
                // Contract fake: acknowledge the asynchronous write; node-builder internals belong to LuckPerms.
                luckPermsCalls.incrementAndGet();
                assertInstanceOf(UUID.class, a[0]);
                assertInstanceOf(java.util.function.Consumer.class, a[1]);
                return CompletableFuture.completedFuture(null);
            }
            throw new AssertionError(m);
        });
        hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> {
            if (m.getName().equals("getUserManager")) return users;
            throw new AssertionError(m);
        }));
        timer = new ScheduledThreadPoolExecutor(1);
        timer.setRemoveOnCancelPolicy(true);
    }

    @AfterEach void cleanup() throws Exception {
        timer.shutdownNow();
        assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test void expiryIsEffectiveImmediatelyAndPermanentMuteDoesNotExpire() {
        MuteService service = service();
        service.mute(alice, "Alice", Duration.ZERO, "reason", "staff", true).join();
        assertFalse(service.isMuted(alice));
        assertTrue(service.get(alice).isEmpty());
        service.mute(bob, "Bob", null, "permanent", "staff", false).join();
        assertTrue(service.isMuted(bob));
        assertEquals(MuteService.PERMANENT, service.get(bob).orElseThrow().expiresAt());
        assertNull(service.get(bob).orElseThrow().remaining(System.currentTimeMillis()));
        assertEquals(1, service.active().size());
    }

    @Test void expiredMuteNotificationIsHandedOffInsteadOfRunningOnPacketCaller() {
        Fakes.Executor io = new Fakes.Executor(false);
        MuteService service = service(io);
        AtomicInteger expired = new AtomicInteger();
        service.onExpiry(action -> expired.incrementAndGet());
        service.mute(alice, "Alice", Duration.ZERO, "reason", "staff", false).join();
        assertFalse(service.isMuted(alice));
        assertFalse(service.isMuted(alice));
        assertEquals(0, expired.get());
        assertEquals(1, io.pending());
        io.drain();
        assertEquals(1, expired.get());
    }

    @Test void persistenceRoundTripRetainsMuteFieldsAndExcludesExpiredMutes() throws Exception {
        MuteService original = service();
        original.mute(alice, "Alice", Duration.ofHours(1), "reason", "staff", true).join();
        original.mute(bob, "Bob", Duration.ZERO, "expired", "staff", false).join();
        var expected = original.get(alice).orElseThrow();
        original.close();
        assertTrue(Files.exists(folder.resolve("data/mutes.json")));
        MuteService reloaded = service();
        reloaded.start(timer);
        try {
            assertEquals(expected, reloaded.get(alice).orElseThrow());
            assertFalse(reloaded.isMuted(bob));
            assertEquals(1, reloaded.active().size());
        } finally { reloaded.close(); }
    }

    @Test void unmuteRemovesLocalStateAndMirrorsEvenWithoutLocalMute() {
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "luckPerms", true));
        MuteService service = service();
        service.mute(alice, "Alice", Duration.ofHours(1), "reason", "staff", false).join();
        assertTrue(service.isMuted(alice));
        assertTrue(service.unmute(alice).join());
        assertFalse(service.isMuted(alice));
        assertFalse(service.unmute(alice).join());
        assertEquals(3, luckPermsCalls.get());
    }

    @Test void disabledLuckPermsIsNeverTouchedForMuteUnmuteOrExpiry() {
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "luckPerms", false));
        hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> { throw new AssertionError("LuckPerms touched: " + m); }));
        MuteService service = service();
        service.mute(alice, "Alice", Duration.ofHours(1), "reason", "staff", false).join();
        assertTrue(service.unmute(alice).join());
        service.mute(alice, "Alice", Duration.ZERO, "reason", "staff", false).join();
        assertFalse(service.isMuted(alice));
        assertEquals(0, luckPermsCalls.get());
    }

    @Test void muteExpiryBoundaryAndRemainingDuration() {
        var mute = new MuteService.Mute(alice, "Alice", 100, 200, "reason", "staff", false);
        assertFalse(mute.expired(199));
        assertTrue(mute.expired(200));
        assertEquals(Duration.ofMillis(1), mute.remaining(199));
        assertEquals(Duration.ZERO, mute.remaining(201));
    }

    @Test void unmuteCleansStoredMirrorAfterConfigChangesAndRestart() throws Exception {
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "disableListening", true));
        MuteService original = service();
        original.mute(alice, "Alice", null, "reason", "staff", false).join();
        var known = original.get(alice).orElseThrow().mirrors();
        assertEquals(1, known.size());
        assertEquals(config.mute().speakPermission(), known.getFirst().speakPermission());
        assertEquals(config.mute().listenPermission(), known.getFirst().listenPermission());
        original.close();
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "luckPerms", false));
        MuteService reloaded = service();
        reloaded.start(timer);
        int before = luckPermsCalls.get();
        assertEquals(known, reloaded.get(alice).orElseThrow().mirrors());
        assertTrue(reloaded.unmute(alice).join());
        assertEquals(before + 1, luckPermsCalls.get());
        assertFalse(reloaded.isMuted(alice));
        reloaded.close();
    }

    @Test void replacementAndReloadRetainEveryAppliedIdentity() {
        MuteService service = service();
        service.mute(alice, "Alice", null, "first", "staff", false).join();
        var first = service.get(alice).orElseThrow().mirrors().getFirst();
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "serverContext", "other"));
        service.reconcileMirrors();
        var second = service.get(alice).orElseThrow().mirrors();
        assertEquals(2, second.size());
        assertEquals(first, second.getFirst());
        assertEquals("other", second.getLast().serverContext());
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "speakPermission", "custom.speak"));
        service.mute(alice, "Alice", Duration.ofMinutes(1), "replacement", "staff", false).join();
        assertEquals(3, service.get(alice).orElseThrow().mirrors().size());
        assertTrue(service.unmute(alice).join());
    }

    @Test void failedCleanupRetainsLocalMuteForRetry() {
        MuteService service = service();
        service.mute(alice, "Alice", null, "reason", "staff", false).join();
        var original = service.get(alice).orElseThrow();
        var failed = new CompletableFuture<Void>();
        UserManager users = Fakes.proxy(UserManager.class, (p, m, a) -> failed);
        hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> users));
        MuteService pending = service();
        pending.mute(alice, "Alice", null, "reason", "staff", false);
        var removal = pending.unmute(alice);
        failed.completeExceptionally(new IllegalStateException("storage unavailable"));
        assertThrows(java.util.concurrent.CompletionException.class, removal::join);
        assertTrue(pending.isMuted(alice));
        assertEquals(original.mirrors(), pending.get(alice).orElseThrow().mirrors());
    }

    @Test void finalSaveWaitsForPendingUnmuteStateChange() {
        var write = new CompletableFuture<Void>();
        UserManager users = Fakes.proxy(UserManager.class, (p, m, a) -> write);
        hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> users));
        MuteService service = service();
        service.mute(alice, "Alice", null, "reason", "staff", false);
        var unmute = service.unmute(alice);
        var flush = service.flushMirrors();
        assertFalse(flush.isDone());
        assertTrue(service.isMuted(alice));
        write.complete(null);
        flush.join();
        assertTrue(unmute.join());
        assertFalse(service.isMuted(alice));
    }

    @Test void queuedExpiryCannotRemoveReplacementMute() {
        var io = new Fakes.Executor(false);
        MuteService service = service(io);
        service.mute(alice, "Alice", Duration.ZERO, "expired", "staff", false).join();
        assertFalse(service.isMuted(alice));
        service.mute(alice, "Alice", null, "replacement", "staff", false).join();
        io.drain();
        assertTrue(service.isMuted(alice));
        assertEquals("replacement", service.get(alice).orElseThrow().reason());
    }

    @Test void unreadableMuteStateIsPreservedBeforeFreshSave() throws Exception {
        Path file = folder.resolve("data/mutes.json");
        Files.createDirectories(file.getParent());
        String original = "[unreadable original state";
        Files.writeString(file, original);
        MuteService service = service();
        service.start(timer);
        service.mute(alice, "Alice", null, "new", "staff", false).join();
        service.close();
        try (var files = Files.list(file.getParent())) {
            var preserved = files.filter(path -> path.getFileName().toString().startsWith("mutes.json.corrupt-")).toList();
            assertEquals(1, preserved.size());
            assertEquals(original, Files.readString(preserved.getFirst()));
        }
        MuteService reloaded = service();
        reloaded.start(timer);
        assertEquals("new", reloaded.get(alice).orElseThrow().reason());
        reloaded.close();
    }

    private MuteService service() { return service(Runnable::run); }
    private MuteService service(java.util.concurrent.Executor io) {
        return new MuteService(() -> config, () -> messages, hook, new OnlinePlayers(), io, Logger.getLogger("MuteServiceTest"), folder);
    }
}
