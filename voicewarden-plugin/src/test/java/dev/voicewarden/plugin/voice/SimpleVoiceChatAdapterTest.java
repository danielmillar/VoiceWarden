package dev.voicewarden.plugin.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import dev.voicewarden.core.config.MutesConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SimpleVoiceChatAdapterTest {

    private static final byte[] OPUS = {1, 2, 3, 4};

    private final UUID speaker = UUID.randomUUID();
    private final FakeGate gate = new FakeGate();
    private final RecordingSink sink = new RecordingSink();
    private final BukkitVoicechatService voicechat = mock(BukkitVoicechatService.class);
    private final Server server = serverWith(voicechat);

    private static Server serverWith(BukkitVoicechatService service) {
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        lenient().when(services.load(BukkitVoicechatService.class)).thenReturn(service);
        lenient().when(server.getServicesManager()).thenReturn(services);
        return server;
    }

    static MutesConfig.Block block(boolean proximity, boolean whisper, MutesConfig.GroupBlock groups, List<String> allowed, boolean listen) {
        return new MutesConfig.Block(proximity, whisper, groups, allowed, listen);
    }

    private SimpleVoiceChatAdapter adapter(MutesConfig.Block block, boolean monitorGroups) {
        VoicePacketPolicy policy = new VoicePacketPolicy(gate, new VoicePacketPolicy.Settings(block, monitorGroups));
        SimpleVoiceChatAdapter adapter = new SimpleVoiceChatAdapter(server, policy, sink);
        adapter.register();
        return adapter;
    }

    private SimpleVoiceChatAdapter defaultAdapter() {
        return adapter(block(true, true, MutesConfig.GroupBlock.ALL, List.of(), false), true);
    }

    private MicrophonePacketEvent microphone(UUID id, @Nullable String groupName, @Nullable Group.Type type, boolean whisper, byte[] opus) {
        MicrophonePacketEvent event = mock(MicrophonePacketEvent.class);
        VoicechatConnection connection = mock(VoicechatConnection.class);
        ServerPlayer player = mock(ServerPlayer.class);
        MicrophonePacket packet = mock(MicrophonePacket.class);
        lenient().when(player.getUuid()).thenReturn(id);
        lenient().when(connection.getPlayer()).thenReturn(player);
        lenient().when(connection.isInGroup()).thenReturn(groupName != null);
        if (groupName != null) {
            Group group = mock(Group.class);
            lenient().when(group.getId()).thenReturn(UUID.nameUUIDFromBytes(groupName.getBytes(StandardCharsets.UTF_8)));
            lenient().when(group.getName()).thenReturn(groupName);
            lenient().when(group.getType()).thenReturn(type);
            lenient().when(connection.getGroup()).thenReturn(group);
        }
        lenient().when(packet.isWhispering()).thenReturn(whisper);
        lenient().when(packet.getOpusEncodedData()).thenReturn(opus);
        lenient().when(event.getSenderConnection()).thenReturn(connection);
        lenient().when(event.getPacket()).thenReturn(packet);
        return event;
    }

    @Test
    void unmutedConsentingSpeakerIsCopiedIntoThePipeline() {
        MicrophonePacketEvent event = microphone(speaker, null, null, false, OPUS);
        defaultAdapter().onMicrophone(event);
        verify(event, never()).cancel();
        assertEquals(List.of("opus " + speaker + " 4"), sink.calls);
    }

    @Test
    void monitoredSpeakerReportsTheirVoiceGroupForConversationContext() {
        SimpleVoiceChatAdapter adapter = defaultAdapter();
        adapter.onMicrophone(microphone(speaker, null, null, false, OPUS));
        adapter.onMicrophone(microphone(speaker, "Party", Group.Type.NORMAL, false, OPUS));

        UUID party = UUID.nameUUIDFromBytes("Party".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(speaker + " null", speaker + " " + party), sink.groups);
    }

    @Test
    void unmonitoredSpeakersAreNotTrackedForConversationContext() {
        gate.muted.add(speaker);
        SimpleVoiceChatAdapter adapter = adapter(block(true, true, MutesConfig.GroupBlock.ALL, List.of(), false), false);
        adapter.onMicrophone(microphone(speaker, null, null, false, OPUS));
        adapter.onMicrophone(microphone(UUID.randomUUID(), "Party", Group.Type.NORMAL, false, OPUS));

        assertTrue(sink.groups.isEmpty(), sink.groups.toString());
    }

    @Test
    void emptyOpusPayloadMarksSpeakingStopped() {
        defaultAdapter().onMicrophone(microphone(speaker, null, null, false, new byte[0]));
        assertEquals(List.of("stopped " + speaker), sink.calls);
    }

    @Test
    void mutedProximitySpeakerIsCancelledAndNotified() {
        gate.muted.add(speaker);
        MicrophonePacketEvent event = microphone(speaker, null, null, false, OPUS);
        defaultAdapter().onMicrophone(event);
        verify(event).cancel();
        assertEquals(List.of(speaker), gate.attempts);
        assertTrue(sink.calls.isEmpty());
    }

    @ParameterizedTest(name = "proximity={0} whisper={1} whispering={2} -> blocked={3}")
    @CsvSource({
        "true, true, false, true",
        "true, true, true, true",
        "false, true, false, false",
        "false, true, true, true",
        "true, false, true, false",
        "true, false, false, true",
    })
    void proximityAndWhisperBlocking(boolean proximity, boolean whisper, boolean whispering, boolean blocked) {
        gate.muted.add(speaker);
        MicrophonePacketEvent event = microphone(speaker, null, null, whispering, OPUS);
        adapter(block(proximity, whisper, MutesConfig.GroupBlock.ALL, List.of(), false), true).onMicrophone(event);
        verify(event, blocked ? org.mockito.Mockito.times(1) : never()).cancel();
        assertEquals(!blocked, !sink.calls.isEmpty());
    }

    @ParameterizedTest(name = "groups={0} type={1} -> blocked={2}")
    @CsvSource({
        "ALL, NORMAL, true",
        "ALL, OPEN, true",
        "NONE, OPEN, false",
        "NONE, ISOLATED, false",
        "OPEN_ONLY, OPEN, true",
        "OPEN_ONLY, NORMAL, false",
        "OPEN_ONLY, ISOLATED, false",
    })
    void groupBlocking(MutesConfig.GroupBlock groups, String type, boolean blocked) {
        gate.muted.add(speaker);
        Group.Type groupType = switch (type) {
            case "OPEN" -> Group.Type.OPEN;
            case "ISOLATED" -> Group.Type.ISOLATED;
            default -> Group.Type.NORMAL;
        };
        MicrophonePacketEvent event = microphone(speaker, "Party", groupType, false, OPUS);
        adapter(block(true, true, groups, List.of(), false), true).onMicrophone(event);
        verify(event, blocked ? org.mockito.Mockito.times(1) : never()).cancel();
    }

    @Test
    void allowedGroupsOverrideGroupBlockingCaseInsensitively() {
        gate.muted.add(speaker);
        gate.allowedGroups.add("staff help");
        MicrophonePacketEvent allowed = microphone(speaker, "Staff Help", Group.Type.NORMAL, false, OPUS);
        MicrophonePacketEvent other = microphone(speaker, "Party", Group.Type.NORMAL, false, OPUS);
        SimpleVoiceChatAdapter adapter = adapter(block(true, true, MutesConfig.GroupBlock.ALL, List.of("Staff Help"), false), true);
        adapter.onMicrophone(allowed);
        adapter.onMicrophone(other);
        verify(allowed, never()).cancel();
        verify(other).cancel();
    }

    @Test
    void groupVoiceIsNotMonitoredWhenMonitorGroupsIsOff() {
        SimpleVoiceChatAdapter adapter = adapter(block(true, true, MutesConfig.GroupBlock.ALL, List.of(), false), false);
        adapter.onMicrophone(microphone(speaker, "Party", Group.Type.NORMAL, false, OPUS));
        assertTrue(sink.calls.isEmpty());
        adapter.onMicrophone(microphone(speaker, null, null, false, OPUS));
        assertEquals(1, sink.calls.size());
    }

    @Test
    void mutedPlayerInGroupIsStillBlockedWhenGroupMonitoringIsOff() {
        gate.muted.add(speaker);
        MicrophonePacketEvent event = microphone(speaker, "Party", Group.Type.NORMAL, false, OPUS);
        adapter(block(true, true, MutesConfig.GroupBlock.ALL, List.of(), false), false).onMicrophone(event);
        verify(event).cancel();
    }

    @Test
    void bypassSkipsMonitoringButNotMutes() {
        UUID bypassed = UUID.randomUUID();
        gate.bypass.add(bypassed);
        SimpleVoiceChatAdapter adapter = defaultAdapter();
        MicrophonePacketEvent open = microphone(bypassed, null, null, false, OPUS);
        adapter.onMicrophone(open);
        verify(open, never()).cancel();
        assertTrue(sink.calls.isEmpty());

        gate.muted.add(bypassed);
        MicrophonePacketEvent event = microphone(bypassed, null, null, false, OPUS);
        adapter.onMicrophone(event);
        verify(event).cancel();
    }

    @Test
    void missingConsentDisablesTalkingAndListeningUntilAccepted() {
        UUID denied = UUID.randomUUID();
        gate.noConsent.add(denied);
        SimpleVoiceChatAdapter adapter = defaultAdapter();

        MicrophonePacketEvent talk = microphone(denied, null, null, false, OPUS);
        adapter.onMicrophone(talk);
        verify(talk).cancel();
        assertEquals(List.of(denied), gate.unconsentedAttempts);
        assertTrue(gate.attempts.isEmpty());
        assertTrue(sink.calls.isEmpty());

        EntitySoundPacketEvent incoming = soundTo(EntitySoundPacketEvent.class, denied);
        adapter.onSound(incoming);
        verify(incoming).cancel();

        gate.noConsent.remove(denied);
        MicrophonePacketEvent acceptedTalk = microphone(denied, null, null, false, OPUS);
        EntitySoundPacketEvent acceptedIncoming = soundTo(EntitySoundPacketEvent.class, denied);
        adapter.onMicrophone(acceptedTalk);
        adapter.onSound(acceptedIncoming);
        verify(acceptedTalk, never()).cancel();
        verify(acceptedIncoming, never()).cancel();
        assertEquals(List.of("opus " + denied + " 4"), sink.calls);
    }

    @Test
    void listenBlockingCancelsSoundToMutedReceiversOnlyWhenEnabled() {
        UUID listener = UUID.randomUUID();
        gate.muted.add(listener);
        EntitySoundPacketEvent entitySound = soundTo(EntitySoundPacketEvent.class, listener);
        StaticSoundPacketEvent staticSound = soundTo(StaticSoundPacketEvent.class, listener);

        defaultAdapter().onSound(entitySound);
        verify(entitySound, never()).cancel();

        SimpleVoiceChatAdapter listening = adapter(block(true, true, MutesConfig.GroupBlock.ALL, List.of(), true), true);
        listening.onSound(entitySound);
        listening.onSound(staticSound);
        verify(entitySound).cancel();
        verify(staticSound).cancel();

        EntitySoundPacketEvent toUnmuted = soundTo(EntitySoundPacketEvent.class, UUID.randomUUID());
        listening.onSound(toUnmuted);
        verify(toUnmuted, never()).cancel();
    }

    @Test
    void registersWithVoicechatOnceAcrossReloads() {
        SimpleVoiceChatAdapter adapter = defaultAdapter();
        adapter.unregister();
        adapter.register();
        verify(voicechat).registerPlugin(adapter);
        assertTrue(adapter.isActive());
    }

    @Test
    void missingVoicechatServiceFailsLoudly() {
        SimpleVoiceChatAdapter adapter = new SimpleVoiceChatAdapter(serverWith(null),
                new VoicePacketPolicy(gate, new VoicePacketPolicy.Settings(block(true, true, MutesConfig.GroupBlock.ALL, List.of(), false), true)), sink);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, adapter::register);
        assertFalse(adapter.isActive());
    }

    @Test
    void missingSenderConnectionIsIgnored() {
        MicrophonePacketEvent event = mock(MicrophonePacketEvent.class);
        when(event.getSenderConnection()).thenReturn(null);
        defaultAdapter().onMicrophone(event);
        verify(event, never()).cancel();
    }

    @Test
    void unregisteredAdapterLetsEverythingThrough() {
        gate.muted.add(speaker);
        SimpleVoiceChatAdapter adapter = defaultAdapter();
        adapter.unregister();
        MicrophonePacketEvent event = microphone(speaker, null, null, false, OPUS);
        adapter.onMicrophone(event);
        verify(event, never()).cancel();
        assertFalse(adapter.isActive());
    }

    @Test
    void disconnectRemovesThePlayerFromThePipeline() {
        PlayerDisconnectedEvent event = mock(PlayerDisconnectedEvent.class);
        when(event.getPlayerUuid()).thenReturn(speaker);
        defaultAdapter().onDisconnect(event);
        assertEquals(List.of("left " + speaker), sink.calls);
    }

    private static <T extends de.maxhenkel.voicechat.api.events.PacketEvent<?>> T soundTo(Class<T> type, UUID receiver) {
        T event = mock(type);
        VoicechatConnection connection = mock(VoicechatConnection.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUuid()).thenReturn(receiver);
        when(connection.getPlayer()).thenReturn(player);
        when(event.getReceiverConnection()).thenReturn(connection);
        return event;
    }

    static final class FakeGate implements VoicePacketGate {
        final Set<UUID> muted = new HashSet<>();
        final Set<UUID> bypass = new HashSet<>();
        final Set<UUID> noConsent = new HashSet<>();
        final Set<String> allowedGroups = new HashSet<>();
        final List<UUID> attempts = new ArrayList<>();
        final List<UUID> unconsentedAttempts = new ArrayList<>();

        @Override
        public boolean isMuted(UUID playerId) {
            return muted.contains(playerId);
        }

        @Override
        public boolean hasBypass(UUID playerId) {
            return bypass.contains(playerId);
        }

        @Override
        public boolean hasConsentForMonitoring(UUID playerId) {
            return !noConsent.contains(playerId);
        }

        @Override
        public boolean isGroupAllowedWhileMuted(String groupName) {
            return allowedGroups.contains(groupName.toLowerCase(Locale.ROOT));
        }

        @Override
        public void onMutedSpeakAttempt(UUID playerId) {
            attempts.add(playerId);
        }

        @Override
        public void onUnconsentedSpeakAttempt(UUID playerId) {
            unconsentedAttempts.add(playerId);
        }
    }

    static final class RecordingSink implements VoiceAudioSink {
        final List<String> calls = new ArrayList<>();
        final List<String> groups = new ArrayList<>();

        @Override
        public void voiceGroup(UUID playerId, @Nullable UUID groupId) {
            groups.add(playerId + " " + groupId);
        }

        @Override
        public void submitOpus(UUID playerId, byte[] opus, int offset, int length) {
            calls.add("opus " + playerId + " " + length);
        }

        @Override
        public void speakingStopped(UUID playerId) {
            calls.add("stopped " + playerId);
        }

        @Override
        public void playerLeft(UUID playerId) {
            calls.add("left " + playerId);
        }
    }
}
