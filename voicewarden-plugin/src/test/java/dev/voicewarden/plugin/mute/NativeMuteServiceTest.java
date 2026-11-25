package dev.voicewarden.plugin.mute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.voicewarden.api.VoiceMuteRecord;
import dev.voicewarden.api.event.VoiceMuteEvent;
import dev.voicewarden.api.event.VoiceUnmuteEvent;
import dev.voicewarden.core.config.MutesConfig;
import dev.voicewarden.core.storage.InMemoryFallbackStore;
import dev.voicewarden.plugin.FakePlayers;
import dev.voicewarden.plugin.ManualScheduler;
import dev.voicewarden.plugin.MutableClock;
import dev.voicewarden.plugin.TestMessages;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class NativeMuteServiceTest {

    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakePlayers players = new FakePlayers();
    private final InMemoryFallbackStore storage = new InMemoryFallbackStore("server-1");
    private final List<Event> events = new ArrayList<>();
    private final RecordingMirror mirror = new RecordingMirror(false);

    static MutesConfig config(MutesConfig.Enforcement enforcement, boolean mirror, MutesConfig.Indicator indicator) {
        return new MutesConfig(enforcement, mirror,
                new MutesConfig.Block(true, true, MutesConfig.GroupBlock.ALL, List.of(), false), indicator, true, 30);
    }

    private NativeMuteService service(MutesConfig config, MuteMirror luckPerms) {
        StrikeCountCache strikes = new StrikeCountCache(
                (id, since) -> storage.countActiveStrikes(id, since, false), scheduler::runAsync, clock, Duration.ofMinutes(10), Duration.ofSeconds(30));
        return new NativeMuteService(config, storage, scheduler, players, TestMessages.renderer(), events::add,
                luckPerms, strikes, id -> true, clock, "server-1", Logger.getLogger("test"));
    }

    private NativeMuteService service() {
        return service(config(MutesConfig.Enforcement.NATIVE, false, MutesConfig.Indicator.ACTION_BAR), mirror);
    }

    private long unmuteEvents(boolean expired) {
        return events.stream().filter(e -> e instanceof VoiceUnmuteEvent u && u.isExpired() == expired).count();
    }

    @Test
    void mutingAnOnlinePlayerBlocksVoicePersistsAndFiresEvent() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service();

        VoiceMuteRecord record = mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "slurs", "Mod").join();

        assertTrue(mutes.isMuted(steve.getUniqueId()));
        assertTrue(mutes.isVoiceBlocked(steve.getUniqueId()));
        assertEquals("5m", mutes.getFormattedMuteRemaining(steve.getUniqueId()));
        assertEquals(record, storage.findActiveMute(steve.getUniqueId(), START).orElseThrow());
        assertEquals(1, events.stream().filter(VoiceMuteEvent.class::isInstance).count());
        verify(steve, atLeastOnce()).sendMessage(any(Component.class));
        verify(steve, atLeastOnce()).sendActionBar(any(Component.class));
    }

    @Test
    void permanentMuteReportsPermanentAndNoRemainingDuration() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service();
        mutes.mutePlayer(steve.getUniqueId(), "Steve", null, "spam", "Mod").join();
        clock.advance(Duration.ofDays(400));
        mutes.sweepExpired();
        assertTrue(mutes.isMuted(steve.getUniqueId()));
        assertEquals("Permanent", mutes.getFormattedMuteRemaining(steve.getUniqueId()));
        assertTrue(mutes.getRemainingMute(steve.getUniqueId()).isEmpty());
    }

    @Test
    void notMutedPlayerHasEmptyRemainingLabel() {
        assertEquals("", service().getFormattedMuteRemaining(UUID.randomUUID()));
    }

    @Test
    void offlineMuteAppliesWhenThePlayerJoins() {
        KnownPlayer alex = players.addOffline("Alex");
        NativeMuteService mutes = service();
        KnownPlayer resolved = mutes.resolveTarget("alex").orElseThrow();
        assertEquals(alex, resolved);

        mutes.mutePlayer(resolved.id(), resolved.name(), Duration.ofHours(1), "toxic", "Mod").join();
        assertTrue(mutes.isMuted(alex.id()));

        Player online = players.join("Alex");
        mutes.onJoin(online);
        verify(online, atLeastOnce()).sendMessage(any(Component.class));
        verify(online, atLeastOnce()).sendActionBar(any(Component.class));
    }

    @Test
    void muteStoredByAnotherServerIsLoadedOnJoin() {
        Player bob = players.join("Bob");
        storage.saveMute(new VoiceMuteRecord(bob.getUniqueId(), "Bob", "network", "Mod", START,
                START.plus(Duration.ofMinutes(10)), true, "server-2"));
        NativeMuteService mutes = service();
        assertFalse(mutes.isMuted(bob.getUniqueId()));

        mutes.onJoin(bob);

        assertTrue(mutes.isVoiceBlocked(bob.getUniqueId()));
    }

    @Test
    void loadActiveMutesWarmsTheCacheAndNameLookup() {
        UUID id = UUID.randomUUID();
        storage.saveMute(new VoiceMuteRecord(id, "Ghost", "r", "Mod", START, null, true, "server-1"));
        NativeMuteService mutes = service();
        assertEquals(1, mutes.loadActiveMutes().join());
        assertTrue(mutes.isMuted(id));
        assertEquals(id, mutes.resolveTarget("ghost").orElseThrow().id());
    }

    @Test
    void bedrockPrefixAndUuidResolveOfflinePlayers() {
        KnownPlayer bedrock = players.addOffline(".PocketSteve");
        NativeMuteService mutes = service();
        assertEquals(bedrock, mutes.resolveTarget("PocketSteve").orElseThrow());
        assertEquals(bedrock, mutes.resolveTarget(".PocketSteve").orElseThrow());
        assertEquals(bedrock.id(), mutes.resolveTarget(bedrock.id().toString()).orElseThrow().id());
        assertEquals(bedrock.id(), mutes.resolveTarget(bedrock.id().toString().replace("-", "")).orElseThrow().id());

        KnownPlayer java = players.addOffline("JavaAlex");
        assertEquals(java, mutes.resolveTarget(".JavaAlex").orElseThrow());

        Player online = players.join(".TabletTom");
        assertEquals(online.getUniqueId(), mutes.resolveTarget("TabletTom").orElseThrow().id());
        assertTrue(mutes.resolveTarget("Nobody").isEmpty());
    }

    @Test
    void unmutingAnOfflinePlayerDeactivatesStoredMute() {
        KnownPlayer alex = players.addOffline("Alex");
        NativeMuteService mutes = service();
        mutes.mutePlayer(alex.id(), "Alex", Duration.ofHours(1), "r", "Mod").join();

        assertTrue(mutes.unmutePlayer(alex.id(), "Mod").join());

        assertFalse(mutes.isMuted(alex.id()));
        assertTrue(storage.findActiveMute(alex.id(), START).isEmpty());
        assertEquals(1, unmuteEvents(false));
        assertFalse(mutes.unmutePlayer(alex.id(), "Mod").join());
    }

    @Test
    void expirySweepFiresExactlyOneExpiredUnmuteEvenIfAnUnmuteRaces() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service();
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();

        clock.advance(Duration.ofMinutes(5));
        assertFalse(mutes.isMuted(steve.getUniqueId()));
        mutes.sweepExpired();
        mutes.sweepExpired();
        mutes.unmutePlayer(steve.getUniqueId(), "Mod").join();

        assertEquals(1, unmuteEvents(true));
        assertEquals(0, unmuteEvents(false));
        assertTrue(storage.findActiveMute(steve.getUniqueId(), clock.instant()).isEmpty());
        assertTrue(mutes.getActiveMutes().isEmpty());
    }

    @Test
    void sweepRunsOnAStartedAsyncTimer() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service();
        mutes.start();
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofSeconds(30), "r", "Mod").join();
        clock.advance(Duration.ofSeconds(31));
        scheduler.runPending();
        assertEquals(1, unmuteEvents(true));
        mutes.stop();
        assertTrue(scheduler.pending().isEmpty());
    }

    @Test
    void speakAttemptNoticeHasAThirtySecondCooldown() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service();
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();
        int before = scheduler.entityTasksRun();

        mutes.onSpeakAttempt(steve.getUniqueId());
        mutes.onSpeakAttempt(steve.getUniqueId());
        clock.advance(Duration.ofSeconds(29));
        mutes.onSpeakAttempt(steve.getUniqueId());
        assertEquals(before + 1, scheduler.entityTasksRun());

        clock.advance(Duration.ofSeconds(1));
        mutes.onSpeakAttempt(steve.getUniqueId());
        assertEquals(before + 2, scheduler.entityTasksRun());
    }

    @Test
    void speakAttemptIsSilentWhenNotifyIsOff() {
        Player steve = players.join("Steve");
        MutesConfig quiet = new MutesConfig(MutesConfig.Enforcement.NATIVE, false,
                new MutesConfig.Block(true, true, MutesConfig.GroupBlock.ALL, List.of(), false), MutesConfig.Indicator.NONE, false, 30);
        NativeMuteService mutes = service(quiet, mirror);
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();
        int before = scheduler.entityTasksRun();
        mutes.onSpeakAttempt(steve.getUniqueId());
        assertEquals(before, scheduler.entityTasksRun());
    }

    @Test
    void bossBarIsShownRefreshedAndHiddenOnUnmute() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service(config(MutesConfig.Enforcement.NATIVE, false, MutesConfig.Indicator.BOSS_BAR), mirror);
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();
        verify(steve, times(1)).showBossBar(any(BossBar.class));

        mutes.refreshIndicators();
        verify(steve, times(2)).showBossBar(any(BossBar.class));

        mutes.unmutePlayer(steve.getUniqueId(), "Mod").join();
        verify(steve).hideBossBar(any(BossBar.class));
    }

    @Test
    void bossBarIsHiddenWhenTheMuteExpires() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service(config(MutesConfig.Enforcement.NATIVE, false, MutesConfig.Indicator.BOSS_BAR), mirror);
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(1), "r", "Mod").join();
        clock.advance(Duration.ofMinutes(2));
        mutes.sweepExpired();
        verify(steve).hideBossBar(any(BossBar.class));
    }

    @Test
    void actionBarRefreshesOnlyForOnlineMutedPlayers() {
        Player steve = players.join("Steve");
        Player quiet = players.join("Quiet");
        NativeMuteService mutes = service();
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();
        mutes.refreshIndicators();
        verify(steve, times(2)).sendActionBar(any(Component.class));
        verify(quiet, never()).sendActionBar(any(Component.class));
    }

    @Test
    void retiredPlayerEntitySchedulerIsSkipped() {
        Player steve = players.join("Steve");
        scheduler.retire(steve);
        NativeMuteService mutes = service();
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();
        verify(steve, never()).sendMessage(any(Component.class));
        assertTrue(mutes.isMuted(steve.getUniqueId()));
    }

    @Test
    void luckPermsEnforcementWithoutLuckPermsFallsBackToNativeBlocking() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service(config(MutesConfig.Enforcement.LUCKPERMS, false, MutesConfig.Indicator.NONE), MuteMirror.NONE);
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();
        assertTrue(mutes.isVoiceBlocked(steve.getUniqueId()));
    }

    @Test
    void luckPermsEnforcementMirrorsInsteadOfBlockingPackets() {
        Player steve = players.join("Steve");
        RecordingMirror luckPerms = new RecordingMirror(true);
        NativeMuteService mutes = service(config(MutesConfig.Enforcement.LUCKPERMS, false, MutesConfig.Indicator.NONE), luckPerms);
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();
        assertTrue(mutes.isMuted(steve.getUniqueId()));
        assertFalse(mutes.isVoiceBlocked(steve.getUniqueId()));
        assertEquals(List.of("apply " + steve.getUniqueId() + " PT5M"), luckPerms.calls);

        clock.advance(Duration.ofMinutes(6));
        mutes.sweepExpired();
        assertEquals("remove " + steve.getUniqueId(), luckPerms.calls.get(1));
    }

    @Test
    void nativeWithMirrorBlocksAndMirrors() {
        Player steve = players.join("Steve");
        RecordingMirror luckPerms = new RecordingMirror(true);
        NativeMuteService mutes = service(config(MutesConfig.Enforcement.NATIVE, true, MutesConfig.Indicator.NONE), luckPerms);
        mutes.mutePlayer(steve.getUniqueId(), "Steve", null, "r", "Mod").join();
        assertTrue(mutes.isVoiceBlocked(steve.getUniqueId()));
        assertEquals(List.of("apply " + steve.getUniqueId() + " null"), luckPerms.calls);
        mutes.unmutePlayer(steve.getUniqueId(), "Mod").join();
        assertEquals("remove " + steve.getUniqueId(), luckPerms.calls.get(1));
    }

    @Test
    void pardonClearsMuteAndStrikes() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service();
        storage.recordStrike(dev.voicewarden.core.storage.StrikeRecord.unsaved(steve.getUniqueId(), "Steve",
                dev.voicewarden.api.FlagSeverity.MILD, "x", "x", START, "server-1"));
        assertEquals(1, mutes.getActiveStrikes(steve.getUniqueId()));
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();

        assertTrue(mutes.pardonPlayer(steve.getUniqueId(), "Mod").join());

        assertFalse(mutes.isMuted(steve.getUniqueId()));
        assertEquals(0, mutes.getActiveStrikes(steve.getUniqueId()));
    }

    @Test
    void rejectsNonPositiveDurations() {
        NativeMuteService mutes = service();
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> mutes.mutePlayer(id, "x", Duration.ZERO, "r", "Mod"));
    }

    @Test
    void bukkitDispatcherAlwaysHopsOffThreadAndRejectsSyncEvents() {
        PluginManager pluginManager = mock(PluginManager.class);
        ManualScheduler deferred = new ManualScheduler() {
            @Override
            public dev.voicewarden.plugin.scheduler.TaskHandle runAsync(Runnable task) {
                return runAsyncDelayed(task, Duration.ZERO);
            }
        };
        BukkitEventDispatcher dispatcher = new BukkitEventDispatcher(deferred, pluginManager);
        VoiceUnmuteEvent event = new VoiceUnmuteEvent(UUID.randomUUID(), "x", "Mod", false);
        dispatcher.callAsync(event);
        verify(pluginManager, never()).callEvent(any());
        deferred.runPending();
        verify(pluginManager).callEvent(event);

        PlayerJoinEvent sync = new PlayerJoinEvent(FakePlayers.mockPlayer("x"), Component.empty());
        assertThrows(IllegalArgumentException.class, () -> dispatcher.callAsync(sync));
    }

    @Test
    void stopClearsActiveBossBarsForOnlinePlayers() {
        Player steve = players.join("Steve");
        NativeMuteService mutes = service(config(MutesConfig.Enforcement.NATIVE, false, MutesConfig.Indicator.BOSS_BAR), mirror);
        mutes.mutePlayer(steve.getUniqueId(), "Steve", Duration.ofMinutes(5), "r", "Mod").join();
        mutes.stop();
        verify(steve).hideBossBar(any(BossBar.class));
    }

    @Test
    void strikeCountCacheStaleRefreshDoesNotOverwriteNewerPutOrForget() {
        UUID player = UUID.randomUUID();
        List<Runnable> queued = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger dbValue = new java.util.concurrent.atomic.AtomicInteger(1);
        StrikeCountCache cache = new StrikeCountCache(
                (id, since) -> dbValue.get(), queued::add, clock, Duration.ofMinutes(10), Duration.ofSeconds(30));

        cache.refresh(player);
        assertEquals(1, queued.size());
        cache.put(player, 5);
        queued.removeFirst().run();
        assertEquals(5, cache.count(player));

        dbValue.set(2);
        cache.invalidate(player);
        assertEquals(1, queued.size());
        dbValue.set(9);
        cache.invalidate(player);
        queued.removeFirst().run();
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(9, cache.count(player));

        cache.refresh(player);
        cache.forget(player);
        while (!queued.isEmpty()) {
            queued.removeFirst().run();
        }
        assertEquals(0, cache.count(player));
    }

    static final class RecordingMirror implements MuteMirror {
        final List<String> calls = new ArrayList<>();
        private final boolean available;

        RecordingMirror(boolean available) {
            this.available = available;
        }

        @Override
        public void apply(UUID playerId, @Nullable Duration duration) {
            calls.add("apply " + playerId + " " + duration);
        }

        @Override
        public void remove(UUID playerId) {
            calls.add("remove " + playerId);
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }
}
