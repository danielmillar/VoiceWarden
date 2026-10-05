package dev.danielmillar.voicewarden.moderation;

import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
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
        var mutes = new MuteService(() -> settings, () -> null, hook, new OnlinePlayers(), Runnable::run, Logger.getAnonymousLogger(), folder);
        var bypass = new CompletableFuture<Boolean>();
        var events = new ArrayList<StaffAction>();
        var service = new StaffModerationService(mutes, id -> bypass, events::add);
        var id = UUID.randomUUID();
        var denied = service.mute(id, "Offline", null, "reason", "Staff");
        assertFalse(denied.isDone());
        assertFalse(mutes.isMuted(id));
        bypass.complete(true);
        assertEquals(StaffModerationService.Result.BYPASSED, denied.join());
        assertFalse(mutes.isMuted(id));
        assertTrue(events.isEmpty());
        service = new StaffModerationService(mutes, ignored -> CompletableFuture.completedFuture(false), events::add);
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
        var mutes = new MuteService(() -> settings, () -> null, hook, new OnlinePlayers(), Runnable::run, Logger.getAnonymousLogger(), folder);
        var bypass = new CompletableFuture<Boolean>();
        var service = new StaffModerationService(mutes, id -> bypass, action -> fail("No staff action expected"));
        var id = UUID.randomUUID();
        var pending = service.mute(id, "Offline", null, "reason", "Staff");
        service.close();
        bypass.complete(false);
        assertTrue(pending.isCompletedExceptionally());
        assertFalse(mutes.isMuted(id));
        assertTrue(service.unmute(id, "Offline", "Staff").isCompletedExceptionally());
    }

}
