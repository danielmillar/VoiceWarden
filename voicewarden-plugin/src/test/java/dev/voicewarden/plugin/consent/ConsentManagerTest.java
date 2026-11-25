package dev.voicewarden.plugin.consent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.voicewarden.core.config.ConsentConfig;
import dev.voicewarden.core.config.ConsentConfig.Mode;
import dev.voicewarden.core.config.ConsentConfig.OnDeny;
import dev.voicewarden.core.moderation.ConsentStatus;
import dev.voicewarden.core.moderation.ConsentStore;
import dev.voicewarden.plugin.FakePlayers;
import dev.voicewarden.plugin.ManualScheduler;
import dev.voicewarden.plugin.MutableClock;
import dev.voicewarden.plugin.TestMessages;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsentManagerTest {

    @TempDir
    Path dir;

    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakePlayers players = new FakePlayers();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T15:00:00Z"));
    private final RecordingPrompt javaMenu = new RecordingPrompt();
    private final RecordingBedrock bedrock = new RecordingBedrock();
    private final List<UUID> deniedCallbacks = new ArrayList<>();
    private ConsentStore store;

    private static ConsentConfig config(boolean enabled, Mode mode, OnDeny onDeny, int version) {
        return new ConsentConfig(enabled, mode, onDeny, version, 40);
    }

    private ConsentManager manager(ConsentConfig config) {
        store = new ConsentStore(dir.resolve("consent.properties"));
        return new ConsentManager(store, config, scheduler, players, TestMessages.renderer(), javaMenu, bedrock, clock,
                deniedCallbacks::add, Logger.getLogger("test"));
    }

    @Test
    void disabledConsentMonitorsEveryoneAndNeverPrompts() {
        ConsentManager consent = manager(config(false, Mode.GUI, OnDeny.KICK, 1));
        Player steve = players.join("Steve");
        consent.onJoin(steve);
        assertTrue(scheduler.pending().isEmpty());
        assertTrue(consent.isMonitoringAllowed(steve.getUniqueId()));
        assertEquals("disabled", consent.placeholderStatus(steve.getUniqueId()));
    }

    @Test
    void noticeModeAutoAcceptsOnJoinAndStaysDeniedAfterOptOut() {
        ConsentManager consent = manager(config(true, Mode.NOTICE, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        consent.onJoin(steve);
        verify(steve).sendMessage(any(Component.class));
        assertTrue(javaMenu.opened.isEmpty());
        assertTrue(scheduler.pending().isEmpty());
        assertTrue(consent.isMonitoringAllowed(steve.getUniqueId()));
        assertEquals(ConsentStatus.ACCEPTED, consent.status(steve.getUniqueId()));
        assertEquals("accepted", consent.placeholderStatus(steve.getUniqueId()));
        assertEquals(ConsentStatus.ACCEPTED, reloaded().status(steve.getUniqueId(), 1));

        consent.onJoin(steve);
        verify(steve, times(1)).sendMessage(any(Component.class));

        consent.onCommand(steve, "deny");
        assertFalse(consent.isMonitoringAllowed(steve.getUniqueId()));
        assertEquals("denied", consent.placeholderStatus(steve.getUniqueId()));
        verify(steve, never()).kick(any(Component.class));

        consent.onJoin(steve);
        assertFalse(consent.isMonitoringAllowed(steve.getUniqueId()));
        assertEquals(ConsentStatus.DENIED, consent.status(steve.getUniqueId()));
    }

    @Test
    void unconsentedSpeakAttemptNotifiesWithCooldownAndResetsOnAccept() {
        ConsentManager consent = manager(config(true, Mode.NOTICE, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        consent.deny(steve);

        consent.onUnconsentedSpeakAttempt(steve.getUniqueId());
        consent.onUnconsentedSpeakAttempt(steve.getUniqueId());
        verify(steve, times(2)).sendMessage(any(Component.class));

        clock.advance(Duration.ofSeconds(10));
        consent.onUnconsentedSpeakAttempt(steve.getUniqueId());
        verify(steve, times(3)).sendMessage(any(Component.class));

        consent.accept(steve);
        consent.onUnconsentedSpeakAttempt(steve.getUniqueId());
        verify(steve, times(4)).sendMessage(any(Component.class));
    }

    @Test
    void denyTriggersCallbackAndResetRestoresPending() {
        ConsentManager consent = manager(config(true, Mode.NOTICE, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");

        consent.deny(steve);
        assertEquals(List.of(steve.getUniqueId()), deniedCallbacks);
        assertEquals(ConsentStatus.DENIED, consent.status(steve.getUniqueId()));

        consent.reset(steve.getUniqueId()).join();
        assertEquals(ConsentStatus.PENDING, consent.status(steve.getUniqueId()));
        assertEquals(ConsentStatus.PENDING, reloaded().status(steve.getUniqueId(), 1));
    }

    @Test
    void bypassStaffSkipJoinAndReloadPrompts() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.KICK, 1));
        Player staff = players.join("Staff");
        when(staff.hasPermission("voicewarden.bypass")).thenReturn(true);

        consent.onJoin(staff);
        assertTrue(scheduler.pending().isEmpty());
        assertTrue(javaMenu.opened.isEmpty());
        verify(staff, never()).sendMessage(any(Component.class));

        consent.onConfigReload(config(true, Mode.NOTICE, OnDeny.IGNORE, 2), players.onlinePlayers());
        assertTrue(javaMenu.opened.isEmpty());
        verify(staff, never()).sendMessage(any(Component.class));
    }

    @Test
    void guiModeOpensMenuAfterDelayAndBlocksMonitoringUntilAccepted() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        consent.onJoin(steve);
        assertEquals(1, scheduler.pending().size());
        assertEquals(40, scheduler.pending().get(0).delayTicks());
        assertTrue(javaMenu.opened.isEmpty());
        assertFalse(consent.isMonitoringAllowed(steve.getUniqueId()));

        scheduler.runPending();
        assertEquals(List.of(steve), javaMenu.opened);
        showingConsentMenu(steve);

        consent.accept(steve);
        assertEquals(ConsentStatus.ACCEPTED, consent.status(steve.getUniqueId()));
        assertTrue(consent.isMonitoringAllowed(steve.getUniqueId()));
        verify(steve).closeInventory(InventoryCloseEvent.Reason.PLUGIN);
        verify(steve).sendMessage(any(Component.class));
        assertEquals(ConsentStatus.ACCEPTED, reloaded().status(steve.getUniqueId(), 1));
    }

    private ConsentStore reloaded() {
        ConsentStore fresh = new ConsentStore(store.file());
        try {
            fresh.load();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return fresh;
    }

    private static void showingConsentMenu(Player player) {
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(top.getHolder(false)).thenReturn(new ConsentMenuHolder());
        when(view.getTopInventory()).thenReturn(top);
        when(player.getOpenInventory()).thenReturn(view);
    }

    @Test
    void acceptingByCommandLeavesAnUnrelatedOpenInventoryAlone() {
        ConsentManager consent = manager(config(true, Mode.NOTICE, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        InventoryView chest = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(chest.getTopInventory()).thenReturn(top);
        when(steve.getOpenInventory()).thenReturn(chest);
        consent.onCommand(steve, "accept");
        verify(steve, never()).closeInventory(any(InventoryCloseEvent.Reason.class));
        assertEquals(ConsentStatus.ACCEPTED, consent.status(steve.getUniqueId()));
    }

    @Test
    void bedrockPlayersGetTheFloodgateFormInsteadOfAChest() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        Player pocket = players.join(".Pocket");
        bedrock.bedrockPlayers.add(pocket.getUniqueId());
        consent.onJoin(pocket);
        scheduler.runPending();
        assertEquals(List.of(pocket), bedrock.opened);
        assertTrue(javaMenu.opened.isEmpty());
    }

    @Test
    void denyWithIgnoreLeavesPlayerUnmonitoredWithoutKick() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        consent.deny(steve);
        assertEquals(ConsentStatus.DENIED, consent.status(steve.getUniqueId()));
        assertFalse(consent.isMonitoringAllowed(steve.getUniqueId()));
        verify(steve, never()).kick(any(Component.class));
        consent.onPromptDismissed(steve);
        assertTrue(scheduler.pending().isEmpty());
    }

    @Test
    void denyWithKickKicksOnThePlayersSchedulerAndRepromptsNextJoin() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.KICK, 1));
        Player steve = players.join("Steve");
        consent.deny(steve);
        verify(steve).kick(any(Component.class));
        assertEquals(ConsentStatus.DENIED, reloaded().status(steve.getUniqueId(), 1));

        consent.onJoin(steve);
        scheduler.runPending();
        assertEquals(List.of(steve), javaMenu.opened);
    }

    @Test
    void escapeReopensTheMenuWhileStillPending() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        consent.onPromptDismissed(steve);
        assertEquals(ConsentManager.REOPEN_DELAY_TICKS, scheduler.pending().get(0).delayTicks());
        scheduler.runPending();
        assertEquals(List.of(steve), javaMenu.opened);

        consent.accept(steve);
        consent.onPromptDismissed(steve);
        assertTrue(scheduler.pending().isEmpty());
    }

    @Test
    void reopenIsSkippedIfPlayerAcceptedBeforeTheDelayEnded() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        consent.onPromptDismissed(steve);
        consent.onCommand(steve, "accept");
        scheduler.runPending();
        assertTrue(javaMenu.opened.isEmpty());
    }

    @Test
    void quitCancelsTheScheduledPrompt() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        consent.onJoin(steve);
        consent.onQuit(steve.getUniqueId());
        scheduler.runPending();
        assertTrue(javaMenu.opened.isEmpty());
    }

    @Test
    void rulesButtonShowsPrivacyTextThenReopens() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        consent.showRules(steve);
        verify(steve).sendMessage(any(Component.class));
        assertEquals(ConsentManager.RULES_REOPEN_DELAY_TICKS, scheduler.pending().get(0).delayTicks());
        scheduler.runPending();
        assertEquals(List.of(steve), javaMenu.opened);
    }

    @Test
    void rulesVersionBumpOnReloadRepromptsOutdatedOnlinePlayers() throws Exception {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        Player accepted = players.join("Accepted");
        Player fresh = players.join("Fresh");
        store.accept(accepted.getUniqueId(), 1);
        assertTrue(consent.isMonitoringAllowed(accepted.getUniqueId()));

        consent.onConfigReload(config(true, Mode.GUI, OnDeny.IGNORE, 1), players.onlinePlayers());
        assertTrue(javaMenu.opened.isEmpty());

        consent.onConfigReload(config(true, Mode.GUI, OnDeny.IGNORE, 2), players.onlinePlayers());
        assertEquals(List.of(accepted, fresh), javaMenu.opened);
        assertEquals("outdated", consent.placeholderStatus(accepted.getUniqueId()));
        assertFalse(consent.isMonitoringAllowed(accepted.getUniqueId()));
        verify(accepted).sendMessage(any(Component.class));
        verify(fresh, never()).sendMessage(any(Component.class));

        consent.accept(accepted);
        assertEquals(2, store.record(accepted.getUniqueId()).orElseThrow().rulesVersion());
    }

    @Test
    void rulesVersionBumpInNoticeModeSendsNoticeAndKeepsMonitoringPreviouslyAccepted() throws Exception {
        ConsentManager consent = manager(config(true, Mode.NOTICE, OnDeny.IGNORE, 1));
        Player accepted = players.join("Accepted");
        Player denied = players.join("Denied");
        store.accept(accepted.getUniqueId(), 1);
        store.deny(denied.getUniqueId(), 1);

        consent.onConfigReload(config(true, Mode.NOTICE, OnDeny.IGNORE, 2), players.onlinePlayers());

        verify(accepted, times(2)).sendMessage(any(Component.class));
        assertTrue(javaMenu.opened.isEmpty());
        assertTrue(consent.isMonitoringAllowed(accepted.getUniqueId()));
        assertEquals(ConsentStatus.ACCEPTED, consent.status(accepted.getUniqueId()));
        assertFalse(consent.isMonitoringAllowed(denied.getUniqueId()));
        assertEquals(ConsentStatus.OUTDATED, consent.status(denied.getUniqueId()));
    }

    @Test
    void consentCommandShowsPrivacyAndStatusAndRejectsUnknownSubcommands() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        Player steve = players.join("Steve");
        consent.onCommand(steve, null);
        verify(steve, times(2)).sendMessage(any(Component.class));
        assertEquals(List.of(steve), javaMenu.opened);

        consent.onCommand(steve, "maybe");
        verify(steve, times(3)).sendMessage(any(Component.class));
        assertEquals(ConsentStatus.PENDING, consent.status(steve.getUniqueId()));
    }

    @Test
    void menuListenerCancelsEveryClickAndRoutesTopSlots() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        ConsentMenuListener listener = new ConsentMenuListener(consent);
        Player steve = players.join("Steve");

        InventoryClickEvent bottomClick = click(steve, 20);
        listener.onClick(bottomClick);
        verify(bottomClick).setCancelled(true);
        assertEquals(ConsentStatus.PENDING, consent.status(steve.getUniqueId()));

        InventoryClickEvent emptySlot = click(steve, 0);
        listener.onClick(emptySlot);
        verify(emptySlot).setCancelled(true);
        assertEquals(ConsentStatus.PENDING, consent.status(steve.getUniqueId()));

        InventoryClickEvent acceptClick = click(steve, ConsentMenuHolder.ACCEPT_SLOT);
        listener.onClick(acceptClick);
        verify(acceptClick).setCancelled(true);
        assertEquals(ConsentStatus.ACCEPTED, consent.status(steve.getUniqueId()));
    }

    @Test
    void menuListenerReopensOnlyWhenThePlayerClosedTheMenu() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        ConsentMenuListener listener = new ConsentMenuListener(consent);
        Player steve = players.join("Steve");

        listener.onClose(close(steve, InventoryCloseEvent.Reason.PLUGIN));
        assertTrue(scheduler.pending().isEmpty());
        listener.onClose(close(steve, InventoryCloseEvent.Reason.PLAYER));
        assertEquals(1, scheduler.pending().size());
    }

    @Test
    void clickingOldRulesVersionGuiAfterReloadRecordsOldVersionAndReprompts() {
        ConsentManager consent = manager(config(true, Mode.GUI, OnDeny.IGNORE, 1));
        ConsentMenuListener listener = new ConsentMenuListener(consent);
        Player steve = players.join("Steve");

        consent.onConfigReload(config(true, Mode.GUI, OnDeny.IGNORE, 2), List.of());

        InventoryClickEvent clickV1 = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(top.getHolder(false)).thenReturn(new ConsentMenuHolder(1));
        when(view.getTopInventory()).thenReturn(top);
        when(clickV1.getView()).thenReturn(view);
        when(clickV1.getWhoClicked()).thenReturn(steve);
        when(clickV1.getRawSlot()).thenReturn(ConsentMenuHolder.ACCEPT_SLOT);

        listener.onClick(clickV1);
        assertEquals(1, store.record(steve.getUniqueId()).orElseThrow().rulesVersion());
        assertEquals(ConsentStatus.OUTDATED, consent.status(steve.getUniqueId()));
        assertEquals(List.of(steve), javaMenu.opened);
    }

    @Test
    void failedConsentSaveDoesNotKickOrConfirm() throws Exception {
        Path blockingDir = dir.resolve("blocked-consent");
        java.nio.file.Files.createDirectories(blockingDir);
        java.nio.file.Files.writeString(blockingDir.resolve("file"), "busy");
        ConsentStore failingStore = new ConsentStore(blockingDir);
        ConsentManager consent = new ConsentManager(failingStore, config(true, Mode.GUI, OnDeny.KICK, 1),
                scheduler, players, TestMessages.renderer(), javaMenu, bedrock, clock, deniedCallbacks::add, Logger.getLogger("test"));
        Player steve = players.join("Steve");

        consent.deny(steve);
        verify(steve, never()).kick(any(Component.class));
        assertEquals(ConsentStatus.PENDING, consent.status(steve.getUniqueId()));
    }

    private static InventoryClickEvent click(Player player, int rawSlot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(top.getHolder(false)).thenReturn(new ConsentMenuHolder());
        when(view.getTopInventory()).thenReturn(top);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(rawSlot);
        return event;
    }

    private static InventoryCloseEvent close(Player player, InventoryCloseEvent.Reason reason) {
        InventoryCloseEvent event = mock(InventoryCloseEvent.class);
        Inventory top = mock(Inventory.class);
        when(top.getHolder(false)).thenReturn(new ConsentMenuHolder());
        when(event.getInventory()).thenReturn(top);
        when(event.getPlayer()).thenReturn(player);
        when(event.getReason()).thenReturn(reason);
        return event;
    }

    static final class RecordingPrompt implements ConsentPrompt {
        final List<Player> opened = new ArrayList<>();

        @Override
        public void open(Player player, ConsentManager manager) {
            opened.add(player);
        }
    }

    static final class RecordingBedrock implements BedrockConsentForms {
        final Set<UUID> bedrockPlayers = new HashSet<>();
        final List<Player> opened = new ArrayList<>();

        @Override
        public boolean isBedrock(UUID playerId) {
            return bedrockPlayers.contains(playerId);
        }

        @Override
        public void open(Player player, ConsentManager manager) {
            opened.add(player);
        }
    }
}
