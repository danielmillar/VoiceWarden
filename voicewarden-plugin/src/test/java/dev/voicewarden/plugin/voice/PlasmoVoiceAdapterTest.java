package dev.voicewarden.plugin.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.voicewarden.core.config.MutesConfig;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import su.plo.slib.api.entity.player.McPlayer;
import su.plo.slib.api.server.entity.player.McServerPlayer;
import su.plo.voice.api.addon.AddonsLoader;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEndEvent;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEvent;
import su.plo.voice.api.server.event.connection.UdpPacketSendEvent;
import su.plo.voice.api.server.player.VoicePlayer;
import su.plo.voice.api.server.player.VoiceServerPlayer;
import su.plo.voice.api.server.socket.UdpServerConnection;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

class PlasmoVoiceAdapterTest {

    private final UUID speaker = UUID.randomUUID();
    private final SimpleVoiceChatAdapterTest.FakeGate gate = new SimpleVoiceChatAdapterTest.FakeGate();
    private final SimpleVoiceChatAdapterTest.RecordingSink sink = new SimpleVoiceChatAdapterTest.RecordingSink();

    private final AddonsLoader addons = mock(AddonsLoader.class);

    private PlasmoVoiceAdapter activeAdapter() {
        MutesConfig.Block block = new MutesConfig.Block(false, false, MutesConfig.GroupBlock.NONE, List.of(), false);
        PlasmoVoiceAdapter adapter = new PlasmoVoiceAdapter(mock(Server.class),
                new VoicePacketPolicy(gate, new VoicePacketPolicy.Settings(block, true)), sink, Logger.getLogger("test"), addons);
        adapter.register();
        return adapter;
    }

    private VoicePlayer voicePlayer(UUID id) {
        VoicePlayer player = mock(VoicePlayer.class);
        McPlayer instance = mock(McPlayer.class);
        when(instance.getUuid()).thenReturn(id);
        when(player.getInstance()).thenReturn(instance);
        return player;
    }

    private PlayerSpeakEvent speak(UUID id, byte[] data) {
        return new PlayerSpeakEvent(voicePlayer(id), new PlayerAudioPacket(1L, data, UUID.randomUUID(), (short) 16, false));
    }

    @Test
    void plasmoMutesBlockAllVoiceRegardlessOfSvcGranularSettings() {
        gate.muted.add(speaker);
        PlayerSpeakEvent event = speak(speaker, new byte[] {9, 9});
        activeAdapter().onSpeak(event);
        assertTrue(event.isCancelled());
        assertEquals(List.of(speaker), gate.attempts);
        assertTrue(sink.calls.isEmpty());
    }

    @Test
    void unmutedSpeechIsCapturedWithoutEncryption() {
        PlayerSpeakEvent event = speak(speaker, new byte[] {1, 2, 3});
        activeAdapter().onSpeak(event);
        assertFalse(event.isCancelled());
        assertEquals(List.of("opus " + speaker + " 3"), sink.calls);
    }

    @Test
    void bypassIsNotCapturedAndNotCancelled() {
        gate.bypass.add(speaker);
        PlayerSpeakEvent event = speak(speaker, new byte[] {1});
        activeAdapter().onSpeak(event);
        assertFalse(event.isCancelled());
        assertTrue(sink.calls.isEmpty());
    }

    @Test
    void missingConsentCancelsSpeechAndNotifiesPlayer() {
        gate.noConsent.add(speaker);
        PlasmoVoiceAdapter adapter = activeAdapter();
        PlayerSpeakEvent event = speak(speaker, new byte[] {1, 2});
        adapter.onSpeak(event);
        assertTrue(event.isCancelled());
        assertEquals(List.of(speaker), gate.unconsentedAttempts);
        assertTrue(sink.calls.isEmpty());

        PlayerSpeakEndEvent end = new PlayerSpeakEndEvent(voicePlayer(speaker), new PlayerAudioEndPacket(3L, UUID.randomUUID(), (short) 16));
        adapter.onSpeakEnd(end);
        assertTrue(end.isCancelled());
    }

    @Test
    void udpSourceAudioIsBlockedWhenMissingConsentOrListenMuted() {
        UUID receiver = UUID.randomUUID();
        gate.noConsent.add(receiver);
        PlasmoVoiceAdapter adapter = activeAdapter();

        UdpPacketSendEvent blocked = udpSend(receiver, mock(SourceAudioPacket.class));
        adapter.onUdpSend(blocked);
        assertTrue(blocked.isCancelled());

        UdpPacketSendEvent nonAudio = udpSend(receiver, mock(Packet.class));
        adapter.onUdpSend(nonAudio);
        assertFalse(nonAudio.isCancelled());

        gate.noConsent.remove(receiver);
        UdpPacketSendEvent allowed = udpSend(receiver, mock(SourceAudioPacket.class));
        adapter.onUdpSend(allowed);
        assertFalse(allowed.isCancelled());
    }

    private UdpPacketSendEvent udpSend(UUID receiver, Packet<?> packet) {
        UdpServerConnection connection = mock(UdpServerConnection.class);
        VoiceServerPlayer player = mock(VoiceServerPlayer.class);
        McServerPlayer instance = mock(McServerPlayer.class);
        when(instance.getUuid()).thenReturn(receiver);
        when(player.getInstance()).thenReturn(instance);
        when(connection.getPlayer()).thenReturn(player);
        return new UdpPacketSendEvent(connection, packet);
    }

    @Test
    void speakEndMarksSpeakingStoppedOrIsCancelledWhileMuted() {
        PlasmoVoiceAdapter adapter = activeAdapter();
        adapter.onSpeakEnd(new PlayerSpeakEndEvent(voicePlayer(speaker), new PlayerAudioEndPacket(1L, UUID.randomUUID(), (short) 16)));
        assertEquals(List.of("stopped " + speaker), sink.calls);

        gate.muted.add(speaker);
        PlayerSpeakEndEvent muted = new PlayerSpeakEndEvent(voicePlayer(speaker), new PlayerAudioEndPacket(2L, UUID.randomUUID(), (short) 16));
        adapter.onSpeakEnd(muted);
        assertTrue(muted.isCancelled());
    }

    @Test
    void addonIsLoadedOnceAndUnloadedOnClose() {
        PlasmoVoiceAdapter adapter = activeAdapter();
        adapter.unregister();
        adapter.register();
        org.mockito.Mockito.verify(addons).load(adapter);
        adapter.close();
        org.mockito.Mockito.verify(addons).unload(adapter);
    }

    @Test
    void inactiveAdapterIgnoresEvents() {
        gate.muted.add(speaker);
        PlasmoVoiceAdapter adapter = activeAdapter();
        adapter.unregister();
        PlayerSpeakEvent event = speak(speaker, new byte[] {1});
        adapter.onSpeak(event);
        assertFalse(event.isCancelled());
    }
}
