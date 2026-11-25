package dev.voicewarden.plugin.voice;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Server;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.AddonLoaderScope;
import su.plo.voice.api.addon.AddonsLoader;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.encryption.Encryption;
import su.plo.voice.api.encryption.EncryptionException;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEndEvent;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEvent;
import su.plo.voice.api.server.event.connection.UdpPacketSendEvent;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

@Addon(id = "voicewarden", name = "VoiceWarden", version = "1.0.0", authors = {"Daniel Millar"}, scope = AddonLoaderScope.SERVER)
public final class PlasmoVoiceAdapter implements VoiceBackendAdapter, AddonInitializer {

    public static final String PLUGIN_NAME = "PlasmoVoice";

    private final Server server;
    private final VoicePacketPolicy policy;
    private final VoiceAudioSink sink;
    private final Logger logger;
    private final AddonsLoader addons;
    private volatile boolean active;
    private volatile boolean decryptFailureLogged;
    private volatile boolean loaded;
    private volatile boolean handlersRegistered;

    @InjectPlasmoVoice
    private PlasmoVoiceServer voiceServer;

    PlasmoVoiceAdapter(Server server, VoicePacketPolicy policy, VoiceAudioSink sink, Logger logger, AddonsLoader addons) {
        this.server = server;
        this.addons = Objects.requireNonNull(addons, "addons");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    static VoiceBackendAdapter create(Server server, VoicePacketPolicy policy, VoiceAudioSink sink, Logger logger) {
        return new PlasmoVoiceAdapter(server, policy, sink, logger, PlasmoVoiceServer.getAddonsLoader());
    }

    @Override
    public String name() {
        return "PlasmoVoice";
    }

    @Override
    public boolean isAvailable() {
        return server.getPluginManager().isPluginEnabled(PLUGIN_NAME);
    }

    @Override
    public void register() {
        active = true;
        if (!loaded) {
            addons.load(this);
            loaded = true;
        }
    }

    @Override
    public void onAddonInitialize() {
        if (handlersRegistered) {
            return;
        }
        voiceServer.getEventBus().register(this, PlayerSpeakEvent.class, EventPriority.HIGHEST, this::onSpeak);
        voiceServer.getEventBus().register(this, PlayerSpeakEndEvent.class, EventPriority.HIGHEST, this::onSpeakEnd);
        voiceServer.getEventBus().register(this, UdpPacketSendEvent.class, EventPriority.HIGHEST, this::onUdpSend);
        handlersRegistered = true;
        logger.info("PlasmoVoice initialised the VoiceWarden addon.");
    }

    @Override
    public void onAddonShutdown() {
        active = false;
        handlersRegistered = false;
        loaded = false;
    }

    @Override
    public void unregister() {
        active = false;
    }

    @Override
    public void close() {
        active = false;
        PlasmoVoiceServer current = voiceServer;
        if (current != null && handlersRegistered) {
            current.getEventBus().unregister(this);
            handlersRegistered = false;
        }
        if (loaded) {
            addons.unload(this);
            loaded = false;
        }
    }

    void onSpeak(PlayerSpeakEvent event) {
        if (!active) {
            return;
        }
        UUID playerId = event.getPlayer().getInstance().getUuid();
        switch (policy.fullMute(playerId)) {
            case BLOCK -> {
                event.setCancelled(true);
                policy.gate().onMutedSpeakAttempt(playerId);
            }
            case BLOCK_NO_CONSENT -> {
                event.setCancelled(true);
                policy.gate().onUnconsentedSpeakAttempt(playerId);
            }
            case MONITOR -> {
                byte[] opus = decrypt(event.getPacket().getData());
                if (opus != null && opus.length > 0) {
                    sink.submitOpus(playerId, opus, 0, opus.length);
                }
            }
            case PASS -> {
            }
        }
    }

    void onSpeakEnd(PlayerSpeakEndEvent event) {
        if (!active) {
            return;
        }
        UUID playerId = event.getPlayer().getInstance().getUuid();
        if (policy.gate().isMuted(playerId) || policy.isBlockedForMissingConsent(playerId)) {
            event.setCancelled(true);
            return;
        }
        sink.speakingStopped(playerId);
    }

    void onUdpSend(UdpPacketSendEvent event) {
        if (!active || !(event.getPacket() instanceof SourceAudioPacket)) {
            return;
        }
        if (event.getConnection() == null || event.getConnection().getPlayer() == null) {
            return;
        }
        UUID receiverId = event.getConnection().getPlayer().getInstance().getUuid();
        if (policy.blocksListening(receiverId)) {
            event.setCancelled(true);
        }
    }

    private byte[] decrypt(byte[] data) {
        if (data == null) {
            return null;
        }
        Encryption encryption = voiceServer == null ? null : voiceServer.getDefaultEncryption();
        if (encryption == null) {
            return data;
        }
        try {
            return encryption.decrypt(data);
        } catch (EncryptionException e) {
            if (!decryptFailureLogged) {
                decryptFailureLogged = true;
                logger.log(Level.WARNING, "Could not decrypt a PlasmoVoice audio packet; monitoring for PlasmoVoice is skipped for those packets", e);
            }
            return null;
        }
    }
}
