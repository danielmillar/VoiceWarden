package dev.danielmillar.voicewarden.mute;

import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.UserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    private Database database;

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
        database = Fakes.database(folder);
        timer = new ScheduledThreadPoolExecutor(1);
        timer.setRemoveOnCancelPolicy(true);
    }

    @AfterEach void cleanup() throws Exception {
        timer.shutdownNow();
        assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
        database.close();
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
        database.close();
        database = Fakes.database(folder);
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
        assertTrue(service.unmute(alice, "staff").join());
        assertFalse(service.isMuted(alice));
        assertFalse(service.unmute(alice, "staff").join());
        assertEquals(3, luckPermsCalls.get());
    }

    @Test void disabledLuckPermsIsNeverTouchedForMuteUnmuteOrExpiry() {
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "luckPerms", false));
        hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> { throw new AssertionError("LuckPerms touched: " + m); }));
        MuteService service = service();
        service.mute(alice, "Alice", Duration.ofHours(1), "reason", "staff", false).join();
        assertTrue(service.unmute(alice, "staff").join());
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
        assertTrue(reloaded.unmute(alice, "staff").join());
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
        assertTrue(service.unmute(alice, "staff").join());
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
        var removal = pending.unmute(alice, "staff");
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
        var unmute = service.unmute(alice, "staff");
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

    @Test void unmuteRemovesTheStoredMute() {
        MuteService service = service();
        service.start(timer);
        service.mute(alice, "Alice", null, "reason", "staff", false).join();
        assertTrue(service.unmute(alice, "staff").join());
        service.close();
        MuteService reloaded = service();
        reloaded.start(timer);
        assertFalse(reloaded.isMuted(alice));
        reloaded.close();
    }

    @Test void historyIsNewestFirstPagedAndSurvivesRestart() throws Exception {
        MuteService service = service();
        service.mute(alice, "Alice", Duration.ofHours(1), "first", "staff", false).join();
        assertTrue(service.unmute(alice, "mod").join());
        service.mute(alice, "Alice", Duration.ZERO, "short", "VoiceWarden", true).join();
        assertFalse(service.isMuted(alice)); // expires on the direct io executor
        assertFalse(service.pardon(alice, "Alice", "mod", "wrong call").join());
        service.mute(bob, "Bob", null, "other player", "staff", false).join();
        database.close();
        database = Fakes.database(folder);
        MuteService reloaded = service();

        var first = reloaded.history(alice, 1, 2).join();
        assertEquals(java.util.List.of(MuteEvent.Type.PARDON, MuteEvent.Type.EXPIRE), types(first));
        assertEquals("wrong call", first.events().getFirst().reason());
        assertTrue(first.more());
        var second = reloaded.history(alice, 2, 2).join();
        assertEquals(java.util.List.of(MuteEvent.Type.MUTE, MuteEvent.Type.UNMUTE), types(second));
        assertEquals(Duration.ZERO, second.events().getFirst().duration());
        assertEquals(second.events().getFirst().expiresAt(), first.events().getLast().at()); // dated when it ran out
        assertEquals("mod", second.events().getLast().actor());
        var third = reloaded.history(alice, 3, 2).join();
        assertEquals(java.util.List.of(MuteEvent.Type.MUTE), types(third));
        assertEquals(Duration.ofHours(1), third.events().getFirst().duration());
        assertFalse(third.more());
        var beyond = reloaded.history(alice, 9, 2).join();
        assertTrue(beyond.events().isEmpty());
        assertEquals(3, beyond.pages());
        assertEquals(3, beyond.newer());
        assertEquals(1, second.newer());
        assertNull(reloaded.history(bob, 1, 10).join().events().getFirst().duration());
    }

    private static java.util.List<MuteEvent.Type> types(MuteEvent.Page page) {
        return page.events().stream().map(MuteEvent::type).toList();
    }

    @Test void partialScopeMirrorsNoSpeakDenialAndReloadRemovesTheOldOne() {
        var applied = new java.util.ArrayList<VoiceMute>();
        var removed = new java.util.ArrayList<VoiceMute>();
        VoiceMuteMirror mirror = (player, previous, next, duration) -> {
            removed.addAll(previous);
            applied.add(next);
            return CompletableFuture.completedFuture(null);
        };
        MuteService service = new MuteService(() -> config, () -> messages, mirror, new OnlinePlayers(), Runnable::run,
                Logger.getLogger("MuteServiceTest"), database);
        service.mute(alice, "Alice", null, "reason", "staff", false).join();
        assertEquals("voicechat.speak", applied.getLast().speakPermission());
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "scope",
                new MuteScope(true, true, MuteScope.Groups.ALL, java.util.List.of("staff-appeal"))));
        service.reconcileMirrors();
        assertNull(applied.getLast());
        assertEquals("voicechat.speak", removed.getLast().speakPermission());
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "disableListening", true));
        service.mute(bob, "Bob", null, "reason", "staff", false).join();
        assertEquals(new VoiceMute("", "voicechat.listen", ""), applied.getLast());
        assertTrue(service.isMuted(alice));
    }

    private MuteService service() { return service(Runnable::run); }
    private MuteService service(java.util.concurrent.Executor io) {
        return new MuteService(() -> config, () -> messages, hook, new OnlinePlayers(), io, Logger.getLogger("MuteServiceTest"), database);
    }
}
