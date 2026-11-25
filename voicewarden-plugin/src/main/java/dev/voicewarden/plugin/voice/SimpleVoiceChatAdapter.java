package dev.voicewarden.plugin.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Server;

public final class SimpleVoiceChatAdapter implements VoiceBackendAdapter, VoicechatPlugin {

    public static final String PLUGIN_NAME = "voicechat";
    public static final String PLUGIN_ID = "voicewarden";
    private static final int PRIORITY = 100;

    private final Server server;
    private final VoicePacketPolicy policy;
    private final VoiceAudioSink sink;
    private volatile boolean active;
    private volatile boolean registeredWithVoicechat;

    SimpleVoiceChatAdapter(Server server, VoicePacketPolicy policy, VoiceAudioSink sink) {
        this.server = server;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    static VoiceBackendAdapter create(Server server, VoicePacketPolicy policy, VoiceAudioSink sink) {
        return new SimpleVoiceChatAdapter(server, policy, sink);
    }

    @Override
    public String name() {
        return "Simple Voice Chat";
    }

    @Override
    public boolean isAvailable() {
        return server.getPluginManager().isPluginEnabled(PLUGIN_NAME);
    }

    @Override
    public void register() {
        active = true;
        if (registeredWithVoicechat) {
            return;
        }
        BukkitVoicechatService service = server.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            active = false;
            throw new IllegalStateException("Simple Voice Chat is enabled but did not provide BukkitVoicechatService");
        }
        service.registerPlugin(this);
        registeredWithVoicechat = true;
    }

    @Override
    public void unregister() {
        active = false;
    }

    boolean isActive() {
        return active;
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone, PRIORITY);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onDisconnect, PRIORITY);
        registration.registerEvent(EntitySoundPacketEvent.class, this::onSound, PRIORITY);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::onSound, PRIORITY);
        registration.registerEvent(StaticSoundPacketEvent.class, this::onSound, PRIORITY);
    }

    void onMicrophone(MicrophonePacketEvent event) {
        if (!active) {
            return;
        }
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null || sender.getPlayer() == null) {
            return;
        }
        UUID playerId = sender.getPlayer().getUuid();
        MicrophonePacket packet = event.getPacket();
        Group group = sender.isInGroup() ? sender.getGroup() : null;
        String groupName = group == null ? null : group.getName();
        boolean openGroup = group != null && group.getType() == Group.Type.OPEN;
        switch (policy.microphone(playerId, groupName, openGroup, packet.isWhispering())) {
            case BLOCK -> {
                event.cancel();
                policy.gate().onMutedSpeakAttempt(playerId);
            }
            case BLOCK_NO_CONSENT -> {
                event.cancel();
                policy.gate().onUnconsentedSpeakAttempt(playerId);
            }
            case MONITOR -> {
                sink.voiceGroup(playerId, group == null ? null : group.getId());
                byte[] opus = packet.getOpusEncodedData();
                if (opus == null || opus.length == 0) {
                    sink.speakingStopped(playerId);
                } else {
                    sink.submitOpus(playerId, opus, 0, opus.length);
                }
            }
            case PASS -> {
            }
        }
    }

    void onDisconnect(PlayerDisconnectedEvent event) {
        if (!active) {
            return;
        }
        sink.playerLeft(event.getPlayerUuid());
    }

    void onSound(PacketEvent<?> event) {
        if (!active) {
            return;
        }
        VoicechatConnection receiver = event.getReceiverConnection();
        if (receiver == null || receiver.getPlayer() == null) {
            return;
        }
        if (policy.blocksListening(receiver.getPlayer().getUuid())) {
            event.cancel();
        }
    }
}
