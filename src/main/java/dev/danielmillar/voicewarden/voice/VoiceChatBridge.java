package dev.danielmillar.voicewarden.voice;

import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.consent.ConsentService;
import dev.danielmillar.voicewarden.mute.MuteScope;
import dev.danielmillar.voicewarden.mute.MuteService;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Simple Voice Chat integration. Every handler here runs on SVC's single packet-processing thread, which carries all
 * voice traffic for the server, so each one is O(1): a concurrent map lookup, maybe a cancel, maybe a lock-free enqueue.
 *
 * <p>A mute's {@link MuteScope} is applied where SVC routes the audio: no group sends to proximity (or whisper range),
 * a normal or isolated group to the group only, an open group to both. The microphone packet is cancelled when every
 * destination is blocked. Only an open group with one side allowed is filtered per receiver, by the sound's source.
 *
 * <p>SVC has no API to unregister a plugin, so after VoiceWarden is disabled the handlers become no-ops via
 * {@link #deactivate()}.
 */
public final class VoiceChatBridge implements VoicechatPlugin {

    /** Runs before other plugins' handlers so a muted player's audio is dropped before anyone else sees it. */
    private static final int PRIORITY = 100;

    private final AudioIngestService ingest;
    private final MuteService mutes;
    private final ConsentService consent;
    private final Supplier<PluginConfig.Mute> settings;
    private final Consumer<Player> deniedSpeech;
    private volatile boolean active = true;
    private volatile boolean initialized;

    public VoiceChatBridge(AudioIngestService ingest, MuteService mutes, ConsentService consent,
                           Supplier<PluginConfig.Mute> settings, Consumer<Player> deniedSpeech) {
        this.ingest = ingest;
        this.mutes = mutes;
        this.consent = consent;
        this.settings = settings;
        this.deniedSpeech = deniedSpeech;
    }

    @Override
    public String getPluginId() {
        return "voicewarden";
    }

    @Override
    public void initialize(VoicechatApi api) {
        ingest.setApi(api);
        initialized = true;
    }

    /** Simple Voice Chat has picked VoiceWarden up and handed it its API. */
    public boolean initialized() {
        return initialized;
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
        if (mutes.isMuted(speaker.getUuid())
                && silenced(settings.get().scope(), sender.getGroup(), packet.isWhispering())) {
            event.cancel();
            if (packet.getOpusEncodedData().length > 0 && speaker.getPlayer() instanceof Player player) {
                deniedSpeech.accept(player);
            }
            return;
        }
        if (consent.blocksSpeech(speaker.getUuid())) {
            event.cancel();
            if (packet.getOpusEncodedData().length > 0 && speaker.getPlayer() instanceof Player player) {
                deniedSpeech.accept(player);
            }
            return;
        }
        ingest.onMicrophonePacket(speaker, packet.getOpusEncodedData(), packet.isWhispering());
    }

    /** Fired once per receiving player per audio packet. */
    private void onSoundPacket(SoundPacketEvent<?> event) {
        if (!active) {
            return;
        }
        PluginConfig.Mute mute = settings.get();
        if (mute.disableListening()) {
            VoicechatConnection receiver = event.getReceiverConnection();
            if (receiver != null && mutes.isMuted(receiver.getPlayer().getUuid())) {
                event.cancel();
                return;
            }
        }
        if (mute.scope().blocksEverything()) {
            return;
        }
        VoicechatConnection sender = event.getSenderConnection();
        if (sender != null && mutes.isMuted(sender.getPlayer().getUuid()) && blocks(mute.scope(), sender.getGroup(), event)) {
            event.cancel();
        }
    }

    /** True when the mute blocks every destination SVC would send this microphone packet to. */
    static boolean silenced(MuteScope scope, @Nullable Group group, boolean whispering) {
        if (scope.blocksEverything()) {
            return true;
        }
        if (group == null) {
            return scope.blocksNearby(whispering);
        }
        boolean open = group.getType() == Group.Type.OPEN;
        boolean groupBlocked = scope.blocksGroup(group.getName(), open);
        return open ? groupBlocked && scope.blocksNearby(whispering) : groupBlocked;
    }

    /** One receiver's copy: group audio follows the group rules, everything else (proximity, spectator) is nearby. */
    static boolean blocks(MuteScope scope, @Nullable Group group, SoundPacketEvent<?> event) {
        if (SoundPacketEvent.SOURCE_GROUP.equals(event.getSource())) {
            return group == null || scope.blocksGroup(group.getName(), group.getType() == Group.Type.OPEN);
        }
        return scope.blocksNearby(event.getPacket() instanceof EntitySoundPacket sound && sound.isWhispering());
    }

    public void deactivate() {
        active = false;
    }
}
