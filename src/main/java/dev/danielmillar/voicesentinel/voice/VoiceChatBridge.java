package dev.danielmillar.voicesentinel.voice;

import dev.danielmillar.voicesentinel.mute.MuteService;
import de.maxhenkel.voicechat.api.VoicechatApi;
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
import org.bukkit.entity.Player;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Simple Voice Chat integration. Every handler here runs on SVC's single packet-processing thread, which carries all
 * voice traffic for the server, so each one is O(1): a concurrent map lookup, maybe a cancel, maybe a lock-free enqueue.
 *
 * <p>SVC has no API to unregister a plugin, so after NevusVoice is disabled the handlers become no-ops via
 * {@link #deactivate()}.
 */
public final class VoiceChatBridge implements VoicechatPlugin {

    /** Runs before other plugins' handlers so a muted player's audio is dropped before anyone else sees it. */
    private static final int PRIORITY = 100;

    private final AudioIngestService ingest;
    private final MuteService mutes;
    private final BooleanSupplier disableListeningOnMute;
    private final Consumer<Player> deniedSpeech;
    private volatile boolean active = true;

    public VoiceChatBridge(AudioIngestService ingest, MuteService mutes, BooleanSupplier disableListeningOnMute,
                           Consumer<Player> deniedSpeech) {
        this.ingest = ingest;
        this.mutes = mutes;
        this.disableListeningOnMute = disableListeningOnMute;
        this.deniedSpeech = deniedSpeech;
    }

    @Override
    public String getPluginId() {
        return "nevusvoice";
    }

    @Override
    public void initialize(VoicechatApi api) {
        ingest.setApi(api);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket, PRIORITY);
        registration.registerEvent(EntitySoundPacketEvent.class, this::onSoundPacket, PRIORITY);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::onSoundPacket, PRIORITY);
        registration.registerEvent(StaticSoundPacketEvent.class, this::onSoundPacket, PRIORITY);
        registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
            if (active) {
                ingest.onDisconnect(event.getPlayerUuid());
            }
        });
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (!active) {
            return;
        }
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) {
            return;
        }
        var speaker = sender.getPlayer();
        MicrophonePacket packet = event.getPacket();
        if (mutes.isMuted(speaker.getUuid())) {
            event.cancel();
            if (packet.getOpusEncodedData().length > 0 && speaker.getPlayer() instanceof Player player) {
                deniedSpeech.accept(player);
            }
            return;
        }
        ingest.onMicrophonePacket(speaker, packet.getOpusEncodedData(), packet.isWhispering());
    }

    /** Full mute: stop muted players hearing others. Fired once per receiving player per audio packet. */
    private void onSoundPacket(PacketEvent<?> event) {
        if (!active || !disableListeningOnMute.getAsBoolean()) {
            return;
        }
        VoicechatConnection receiver = event.getReceiverConnection();
        if (receiver != null && mutes.isMuted(receiver.getPlayer().getUuid())) {
            event.cancel();
        }
    }

    public void deactivate() {
        active = false;
    }
}
