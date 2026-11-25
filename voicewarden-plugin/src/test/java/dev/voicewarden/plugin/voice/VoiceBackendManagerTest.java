package dev.voicewarden.plugin.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.voicewarden.core.config.MutesConfig;
import dev.voicewarden.core.config.VoiceWardenConfig.VoiceBackend;
import dev.voicewarden.plugin.consent.ConsentView;
import dev.voicewarden.plugin.mute.NativeMuteService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class VoiceBackendManagerTest {

    private final NativeMuteService mutes = mock(NativeMuteService.class);
    private final Map<VoiceBackendManager.Backend, FakeAdapter> made = new EnumMap<>(VoiceBackendManager.Backend.class);
    private final List<UUID> unconsentedNotices = new ArrayList<>();

    private VoiceBackendManager manager(Set<String> enabledPlugins, List<String> allowedGroups) {
        ConsentView consent = new ConsentView() {
            @Override
            public boolean isMonitoringAllowed(UUID playerId) {
                return false;
            }

            @Override
            public String placeholderStatus(UUID playerId) {
                return "denied";
            }

            @Override
            public void onUnconsentedSpeakAttempt(UUID playerId) {
                unconsentedNotices.add(playerId);
            }
        };
        SimpleVoiceChatAdapterTest.RecordingSink sink = new SimpleVoiceChatAdapterTest.RecordingSink();
        MutesConfig.Block block = new MutesConfig.Block(true, true, MutesConfig.GroupBlock.ALL, allowedGroups, false);
        return new VoiceBackendManager(mutes, consent, sink, new VoicePacketPolicy.Settings(block, true), enabledPlugins::contains,
                (backend, policy, s) -> made.computeIfAbsent(backend, FakeAdapter::new), Logger.getLogger("test"));
    }

    @Test
    void autoRegistersEveryInstalledBackend() {
        VoiceBackendManager manager = manager(Set.of("voicechat", "PlasmoVoice"), List.of());
        assertEquals(List.of("SVC", "PLASMOVOICE"), manager.start(VoiceBackend.AUTO));
        assertTrue(made.get(VoiceBackendManager.Backend.SVC).active);
    }

    @Test
    void autoWithOnlyPlasmoVoiceNeverTouchesSvc() {
        VoiceBackendManager manager = manager(Set.of("PlasmoVoice"), List.of());
        assertEquals(List.of("PLASMOVOICE"), manager.start(VoiceBackend.AUTO));
        assertFalse(made.containsKey(VoiceBackendManager.Backend.SVC));
    }

    @Test
    void explicitModeOnlyRegistersThatBackend() {
        VoiceBackendManager manager = manager(Set.of("voicechat", "PlasmoVoice"), List.of());
        assertEquals(List.of("PLASMOVOICE"), manager.start(VoiceBackend.PLASMOVOICE));
        assertEquals(List.of(), manager(Set.of(), List.of()).start(VoiceBackend.SVC));
    }

    @Test
    void restartReusesAdaptersAndShutdownClosesThem() {
        VoiceBackendManager manager = manager(Set.of("voicechat"), List.of());
        manager.start(VoiceBackend.AUTO);
        manager.start(VoiceBackend.AUTO);
        FakeAdapter svc = made.get(VoiceBackendManager.Backend.SVC);
        assertEquals(2, svc.registrations);
        manager.shutdown();
        assertTrue(svc.closed);
        assertEquals(List.of(), manager.activeBackends());
    }

    @Test
    void failingAdapterDoesNotStopOthers() {
        VoiceBackendManager manager = manager(Set.of("voicechat", "PlasmoVoice"), List.of());
        made.put(VoiceBackendManager.Backend.SVC, new FakeAdapter(VoiceBackendManager.Backend.SVC) {
            @Override
            public void register() {
                throw new NoClassDefFoundError("de/maxhenkel/voicechat/api/VoicechatPlugin");
            }
        });
        assertEquals(List.of("PLASMOVOICE"), manager.start(VoiceBackend.AUTO));
    }

    @Test
    void gateUsesCachedBypassMuteServiceAndConsent() {
        VoiceBackendManager manager = manager(Set.of(), List.of("Staff"));
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(VoiceBackendManager.BYPASS_PERMISSION)).thenReturn(true);
        when(mutes.isVoiceBlocked(id)).thenReturn(true);

        assertFalse(manager.hasBypass(id));
        manager.refreshBypass(player);
        assertTrue(manager.hasBypass(id));
        assertTrue(manager.isMuted(id));
        assertFalse(manager.hasConsentForMonitoring(id));
        assertTrue(manager.isGroupAllowedWhileMuted("staff"));
        assertFalse(manager.isGroupAllowedWhileMuted("party"));

        manager.forget(id);
        assertFalse(manager.hasBypass(id));

        manager.onMutedSpeakAttempt(id);
        org.mockito.Mockito.verify(mutes).onSpeakAttempt(id);
        manager.onUnconsentedSpeakAttempt(id);
        assertEquals(List.of(id), unconsentedNotices);
    }

    static class FakeAdapter implements VoiceBackendAdapter {
        final VoiceBackendManager.Backend backend;
        boolean active;
        boolean closed;
        int registrations;

        FakeAdapter(VoiceBackendManager.Backend backend) {
            this.backend = backend;
        }

        @Override
        public String name() {
            return backend.name();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void register() {
            active = true;
            registrations++;
        }

        @Override
        public void unregister() {
            active = false;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
