package dev.danielmillar.voicewarden.voice;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.health.Metrics;
import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VoiceChatBridgeTest {
    @TempDir Path folder;
    private final UUID id = new UUID(0, 23);
    private final AtomicInteger notices = new AtomicInteger();
    private final Metrics metrics = new Metrics();
    private final Map<Class<?>, Consumer<Event>> handlers = new HashMap<>();
    private MuteService mutes;
    private VoiceChatBridge bridge;
    private VoicechatConnection connection;

    @BeforeEach void setup() throws Exception {
        var defaults = Fakes.config(folder);
        var config = Fakes.with(defaults, "mute", Fakes.with(defaults.mute(), "luckPerms", false));
        Logger log = Logger.getLogger("VoiceChatBridgeTest");
        var permissions = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, args) -> { throw new AssertionError(m); }));
        var messages = Messages.load(folder.resolve("messages.yml"));
        mutes = new MuteService(() -> config, () -> messages, permissions, new OnlinePlayers(), Runnable::run, log, folder);
        var ingest = new AudioIngestService(() -> config, metrics, new Fakes.Executor(true), s -> { },
                key -> null, p -> false, log);
        ingest.setAccepting(true);
        bridge = new VoiceChatBridge(ingest, mutes, () -> false, player -> notices.incrementAndGet());
        bridge.registerEvents(Fakes.proxy(EventRegistration.class, (p, method, args) -> {
            assertEquals("registerEvent", method.getName());
            if (args.length == 3) assertEquals(100, args[2]);
            @SuppressWarnings("unchecked") Consumer<Event> handler = (Consumer<Event>) args[1];
            handlers.put((Class<?>) args[0], handler);
            return null;
        }));
        Player player = Fakes.proxy(Player.class, (p, m, args) -> switch (m.getName()) {
            case "getName" -> "Alice";
            default -> throw new AssertionError(m);
        });
        ServerPlayer speaker = Fakes.proxy(ServerPlayer.class, (p, m, args) -> switch (m.getName()) {
            case "getUuid" -> id;
            case "getPlayer" -> player;
            default -> throw new AssertionError(m);
        });
        connection = Fakes.proxy(VoicechatConnection.class, (p, m, args) -> {
            if (m.getName().equals("getPlayer")) return speaker;
            throw new AssertionError(m);
        });
    }

    @Test void mutedAudioIsCancelledAndRequestsTheCustomNoticeWithoutTranscription() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        assertTrue(packet(new byte[]{1}));
        assertEquals(1, notices.get());
        assertEquals(0, metrics.packets.sum());
    }

    @Test void mutedEndOfTransmissionIsCancelledWithoutAFalseSpeakingNotice() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        assertTrue(packet(new byte[0]));
        assertEquals(0, notices.get());
    }

    @Test void deactivatedBridgeDoesNotSendNoticesOrInterceptSpeech() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        bridge.deactivate();
        assertFalse(packet(new byte[]{1}));
        assertEquals(0, notices.get());
        assertEquals(0, metrics.packets.sum());
    }

    private boolean packet(byte[] audio) {
        boolean[] cancelled = {false};
        MicrophonePacket packet = Fakes.proxy(MicrophonePacket.class, (p, m, args) -> switch (m.getName()) {
            case "getOpusEncodedData" -> audio;
            case "isWhispering" -> false;
            default -> throw new AssertionError(m);
        });
        var event = Fakes.proxy(MicrophonePacketEvent.class, (p, m, args) -> switch (m.getName()) {
            case "getSenderConnection" -> connection;
            case "getPacket" -> packet;
            case "cancel" -> { cancelled[0] = true; yield true; }
            default -> throw new AssertionError(m);
        });
        handlers.get(MicrophonePacketEvent.class).accept(event);
        return cancelled[0];
    }
}
