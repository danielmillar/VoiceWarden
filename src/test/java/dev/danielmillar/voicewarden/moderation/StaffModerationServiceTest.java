package dev.danielmillar.voicewarden.moderation;

import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.mute.MuteEvent;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.mute.VoiceMute;
import dev.danielmillar.voicewarden.mute.VoiceMuteMirror;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class StaffModerationServiceTest {
    @TempDir Path folder;

    @Test void offlineBypassIsResolvedBeforeMuteAndReverseActionNotifiesOnce() throws Exception {
        PluginConfig config = Fakes.config(folder);
        var settings = Fakes.with(config, "mute", Fakes.with(Fakes.with(config.mute(), "luckPerms", false), "notifyPlayer", false));
        var hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> { throw new AssertionError(m); }));
        var mutes = new MuteService(() -> settings, () -> null, hook, new OnlinePlayers(), Runnable::run, Logger.getAnonymousLogger(), Fakes.database(folder));
        var bypass = new CompletableFuture<Boolean>();
        var events = new ArrayList<StaffAction>();
        var service = new StaffModerationService(mutes, id -> bypass, player -> CompletableFuture.completedFuture(null), events::add);
        var id = UUID.randomUUID();
        var denied = service.mute(id, "Offline", null, "reason", "Staff");
        assertFalse(denied.isDone());
        assertFalse(mutes.isMuted(id));
        bypass.complete(true);
        assertEquals(StaffModerationService.Result.BYPASSED, denied.join());
        assertFalse(mutes.isMuted(id));
        assertTrue(events.isEmpty());
        service = new StaffModerationService(mutes, ignored -> CompletableFuture.completedFuture(false), player -> CompletableFuture.completedFuture(null), events::add);
        assertEquals(StaffModerationService.Result.MUTED, service.mute(id, "Offline", null, "reason", "Staff").join());
        assertTrue(mutes.isMuted(id));
        assertEquals(StaffModerationService.Result.UNMUTED, service.unmute(id, "Offline", "Staff").join());
        assertFalse(mutes.isMuted(id));
        assertEquals(StaffModerationService.Result.NOT_MUTED, service.unmute(id, "Offline", "Staff").join());
        assertEquals(2, events.size());
        assertEquals(StaffAction.Type.MUTE, events.getFirst().type());
        assertEquals(StaffAction.Type.UNMUTE, events.getLast().type());
    }
    @Test void shutdownRejectsPendingOfflinePermissionDecision() throws Exception {
        var config = Fakes.config(folder);
        var settings = Fakes.with(config, "mute", Fakes.with(Fakes.with(config.mute(), "luckPerms", false), "notifyPlayer", false));
        var hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> { throw new AssertionError(m); }));
        var mutes = new MuteService(() -> settings, () -> null, hook, new OnlinePlayers(), Runnable::run, Logger.getAnonymousLogger(), Fakes.database(folder));
        var bypass = new CompletableFuture<Boolean>();
        var service = new StaffModerationService(mutes, id -> bypass, player -> CompletableFuture.completedFuture(null), action -> fail("No staff action expected"));
        var id = UUID.randomUUID();
        var pending = service.mute(id, "Offline", null, "reason", "Staff");
        service.close();
        bypass.complete(false);
        assertTrue(pending.isCompletedExceptionally());
        assertFalse(mutes.isMuted(id));
        assertTrue(service.unmute(id, "Offline", "Staff").isCompletedExceptionally());
    }

    @Test void pardonLiftsMuteClearsOffencesAndRecordsOnePardon() throws Exception {
        var config = Fakes.config(folder);
        var settings = Fakes.with(config, "mute", Fakes.with(Fakes.with(config.mute(), "luckPerms", false), "notifyPlayer", false));
        var database = Fakes.database(folder);
        var mutes = new MuteService(() -> settings, () -> null, VoiceMuteMirror.NONE, new OnlinePlayers(), Runnable::run, Logger.getAnonymousLogger(), database);
        var offenses = new OffenseTracker(database, Logger.getAnonymousLogger());
        var events = new ArrayList<StaffAction>();
        var service = new StaffModerationService(mutes, id -> CompletableFuture.completedFuture(false), offenses::reset, events::add);
        var id = UUID.randomUUID();
        offenses.recordNext(id, 0);
        offenses.recordNext(id, 0);
        mutes.mute(id, "Steve", Duration.ofHours(1), "Automatic: slurs", "VoiceWarden", true).join();

        assertEquals(StaffModerationService.Result.PARDONED, service.pardon(id, "Steve", "Mod", "misheard").join());
        assertFalse(mutes.isMuted(id));
        assertEquals(0, offenses.lookup(id, 0).join().count());
        var history = mutes.history(id, 1, 10).join().events();
        assertEquals(List.of(MuteEvent.Type.PARDON, MuteEvent.Type.MUTE), history.stream().map(MuteEvent::type).toList());
        assertEquals("Mod", history.getFirst().actor());
        assertEquals("misheard", history.getFirst().reason());
        assertEquals(List.of(StaffAction.Type.PARDON), events.stream().map(StaffAction::type).toList());

        offenses.recordNext(id, 0);
        assertEquals(StaffModerationService.Result.CLEARED, service.pardon(id, "Steve", "Mod", "").join());
        assertEquals(MuteEvent.Type.PARDON, mutes.history(id, 1, 10).join().events().getFirst().type());
        database.close();
        var reopened = Fakes.database(folder);
        var reloaded = new OffenseTracker(reopened, Logger.getAnonymousLogger());
        reloaded.load();
        assertEquals(0, reloaded.lookup(id, 0).join().count());
        reopened.close();
    }

    @Test void failedMirrorCleanupLeavesMuteAndOffencesInPlace() throws Exception {
        var config = Fakes.config(folder);
        var settings = Fakes.with(config, "mute", Fakes.with(config.mute(), "notifyPlayer", false));
        var database = Fakes.database(folder);
        var failing = new VoiceMuteMirror() {
            @Override public CompletableFuture<Void> replaceVoiceMute(UUID player, List<VoiceMute> known, VoiceMute next, Duration duration) {
                return next == null ? CompletableFuture.failedFuture(new IllegalStateException("storage unavailable"))
                        : CompletableFuture.completedFuture(null);
            }
        };
        var mutes = new MuteService(() -> settings, () -> null, failing, new OnlinePlayers(), Runnable::run, Logger.getAnonymousLogger(), database);
        var offenses = new OffenseTracker(database, Logger.getAnonymousLogger());
        var events = new ArrayList<StaffAction>();
        var service = new StaffModerationService(mutes, id -> CompletableFuture.completedFuture(false), offenses::reset, events::add);
        var id = UUID.randomUUID();
        offenses.recordNext(id, 0);
        mutes.mute(id, "Steve", null, "reason", "Mod", false).join();

        assertThrows(java.util.concurrent.CompletionException.class, () -> service.pardon(id, "Steve", "Mod", "").join());
        assertTrue(mutes.isMuted(id));
        assertEquals(1, offenses.lookup(id, 0).join().count());
        assertTrue(events.isEmpty());
        assertEquals(List.of(MuteEvent.Type.MUTE), mutes.history(id, 1, 10).join().events().stream().map(MuteEvent::type).toList());
        database.close();
    }
}
