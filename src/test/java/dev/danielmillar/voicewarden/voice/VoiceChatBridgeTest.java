package dev.danielmillar.voicewarden.voice;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.health.Metrics;
import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.mute.MuteScope;
import dev.danielmillar.voicewarden.mute.MuteScope.Groups;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VoiceChatBridgeTest {
    @TempDir Path folder;
    private final UUID id = new UUID(0, 23);
    private final UUID listenerId = new UUID(0, 24);
    private final AtomicInteger notices = new AtomicInteger();
    private final Metrics metrics = new Metrics();
    private final Map<Class<?>, Consumer<Event>> handlers = new HashMap<>();
    private PluginConfig.Mute settings;
    private MuteService mutes;
    private VoiceChatBridge bridge;
    private VoicechatConnection connection;
    private VoicechatConnection listener;
    private @Nullable Group group;

    @BeforeEach void setup() throws Exception {
        var defaults = Fakes.config(folder);
        settings = Fakes.with(defaults.mute(), "luckPerms", false);
        var config = Fakes.with(defaults, "mute", settings);
        Logger log = Logger.getLogger("VoiceChatBridgeTest");
        var permissions = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, args) -> { throw new AssertionError(m); }));
        var messages = Messages.load(folder.resolve("messages.yml"));
        mutes = new MuteService(() -> config, () -> messages, permissions, new OnlinePlayers(), Runnable::run, log, Fakes.database(folder));
        var ingest = new AudioIngestService(() -> config, metrics, new Fakes.Executor(false), s -> { },
                key -> null, p -> false, log);
        ingest.setAccepting(true);
        bridge = new VoiceChatBridge(ingest, mutes, () -> settings, player -> notices.incrementAndGet());
        bridge.registerEvents(Fakes.proxy(EventRegistration.class, (p, method, args) -> {
            assertEquals("registerEvent", method.getName());
            if (args.length == 3) assertEquals(100, args[2]);
            @SuppressWarnings("unchecked") Consumer<Event> handler = (Consumer<Event>) args[1];
            handlers.put((Class<?>) args[0], handler);
            return null;
        }));
        connection = connection(id, true);
        listener = connection(listenerId, false);
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
    }

    @Test void mutedAudioIsCancelledAndRequestsTheCustomNoticeWithoutTranscription() {
        assertTrue(packet(new byte[]{1}, false));
        assertEquals(1, notices.get());
        assertEquals(0, metrics.packets.sum());
    }

    @Test void mutedEndOfTransmissionIsCancelledWithoutAFalseSpeakingNotice() {
        assertTrue(packet(new byte[0], false));
        assertEquals(0, notices.get());
    }

    @Test void deactivatedBridgeDoesNotSendNoticesOrInterceptSpeech() {
        bridge.deactivate();
        assertFalse(packet(new byte[]{1}, false));
        assertEquals(0, notices.get());
        assertEquals(0, metrics.packets.sum());
    }

    @Test void defaultScopeBlocksProximityWhisperAndEveryGroupType() {
        for (var type : List.of(Group.Type.NORMAL, Group.Type.OPEN, Group.Type.ISOLATED)) {
            group = group("friends", type);
            assertTrue(packet(new byte[]{1}, false));
        }
        group = null;
        assertTrue(packet(new byte[]{1}, false));
        assertTrue(packet(new byte[]{1}, true));
        assertEquals(5, notices.get());
    }

    @Test void allowedProximityPassesAndIsModeratedWhileWhisperStaysBlocked() {
        scope(new MuteScope(false, true, Groups.ALL, List.of()));
        assertFalse(packet(new byte[]{1}, false));
        assertEquals(1, metrics.packets.sum());
        assertTrue(packet(new byte[]{1}, true));
        assertFalse(sound(EntitySoundPacketEvent.class, SoundPacketEvent.SOURCE_PROXIMITY, false));
        assertTrue(sound(EntitySoundPacketEvent.class, SoundPacketEvent.SOURCE_PROXIMITY, true));
    }

    @Test void allowedWhisperPassesWhileProximityStaysBlocked() {
        scope(new MuteScope(true, false, Groups.ALL, List.of()));
        assertFalse(packet(new byte[]{1}, true));
        assertTrue(packet(new byte[]{1}, false));
    }

    @Test void openOnlyBlocksOpenGroupsButNotNormalOrIsolatedGroups() {
        scope(new MuteScope(true, true, Groups.OPEN_ONLY, List.of()));
        group = group("friends", Group.Type.NORMAL);
        assertFalse(packet(new byte[]{1}, false));
        group = group("friends", Group.Type.ISOLATED);
        assertFalse(packet(new byte[]{1}, false));
        group = group("friends", Group.Type.OPEN);
        assertTrue(packet(new byte[]{1}, false));
        group = null;
        assertTrue(packet(new byte[]{1}, false));
    }

    @Test void namedGroupIsAlwaysAllowedRegardlessOfCase() {
        scope(new MuteScope(true, true, Groups.ALL, List.of("staff-appeal")));
        group = group("Staff-Appeal", Group.Type.NORMAL);
        assertFalse(packet(new byte[]{1}, false));
        group = group("friends", Group.Type.NORMAL);
        assertTrue(packet(new byte[]{1}, false));
    }

    @Test void openGroupWithOnlyTheGroupAllowedReachesTheGroupButNotNearbyPlayers() {
        scope(new MuteScope(true, true, Groups.NONE, List.of()));
        group = group("friends", Group.Type.OPEN);
        assertFalse(packet(new byte[]{1}, false));
        assertEquals(0, notices.get());
        assertFalse(sound(StaticSoundPacketEvent.class, SoundPacketEvent.SOURCE_GROUP, false));
        assertTrue(sound(EntitySoundPacketEvent.class, SoundPacketEvent.SOURCE_PROXIMITY, false));
    }

    @Test void openGroupWithOnlyNearbyAllowedReachesNearbyPlayersButNotTheGroup() {
        scope(new MuteScope(false, true, Groups.ALL, List.of()));
        group = group("friends", Group.Type.OPEN);
        assertFalse(packet(new byte[]{1}, false));
        assertTrue(sound(StaticSoundPacketEvent.class, SoundPacketEvent.SOURCE_GROUP, false));
        assertFalse(sound(EntitySoundPacketEvent.class, SoundPacketEvent.SOURCE_PROXIMITY, false));
    }

    @Test void mutedListenerHearsOthersUnlessListeningIsDisabled() {
        mutes.unmute(id, "staff").join();
        mutes.mute(listenerId, "Bob", null, "reason", "staff", false).join();
        assertFalse(sound(EntitySoundPacketEvent.class, SoundPacketEvent.SOURCE_PROXIMITY, false));
        settings = Fakes.with(settings, "disableListening", true);
        assertTrue(sound(EntitySoundPacketEvent.class, SoundPacketEvent.SOURCE_PROXIMITY, false));
        assertTrue(sound(StaticSoundPacketEvent.class, SoundPacketEvent.SOURCE_GROUP, false));
    }

    private void scope(MuteScope scope) {
        settings = Fakes.with(settings, "scope", scope);
    }

    private VoicechatConnection connection(UUID uuid, boolean speaker) {
        Player player = Fakes.proxy(Player.class, (p, m, args) -> switch (m.getName()) {
            case "getName" -> speaker ? "Alice" : "Bob";
            default -> throw new AssertionError(m);
        });
        ServerPlayer server = Fakes.proxy(ServerPlayer.class, (p, m, args) -> switch (m.getName()) {
            case "getUuid" -> uuid;
            case "getPlayer" -> player;
            case "getPosition" -> throw new IllegalStateException("no location in this test");
            default -> throw new AssertionError(m);
        });
        return Fakes.proxy(VoicechatConnection.class, (p, m, args) -> switch (m.getName()) {
            case "getPlayer" -> server;
            case "getGroup" -> speaker ? group : null;
            default -> throw new AssertionError(m);
        });
    }

    private static Group group(String name, Group.Type type) {
        return Fakes.proxy(Group.class, (p, m, args) -> switch (m.getName()) {
            case "getName" -> name;
            case "getType" -> type;
            default -> throw new AssertionError(m);
        });
    }

    private boolean packet(byte[] audio, boolean whispering) {
        boolean[] cancelled = {false};
        MicrophonePacket packet = Fakes.proxy(MicrophonePacket.class, (p, m, args) -> switch (m.getName()) {
            case "getOpusEncodedData" -> audio;
            case "isWhispering" -> whispering;
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

    /** One receiver's copy of the speaker's audio, as SVC fires it after routing. */
    private <E extends SoundPacketEvent<?>> boolean sound(Class<E> type, String source, boolean whispering) {
        boolean[] cancelled = {false};
        Object packet = type == EntitySoundPacketEvent.class
                ? Fakes.proxy(EntitySoundPacket.class, (p, m, args) -> {
                    if (m.getName().equals("isWhispering")) return whispering;
                    throw new AssertionError(m);
                })
                : Fakes.proxy(StaticSoundPacket.class, (p, m, args) -> { throw new AssertionError(m); });
        E event = Fakes.proxy(type, (p, m, args) -> switch (m.getName()) {
            case "getSenderConnection" -> connection;
            case "getReceiverConnection" -> listener;
            case "getSource" -> source;
            case "getPacket" -> packet;
            case "cancel" -> { cancelled[0] = true; yield true; }
            default -> throw new AssertionError(m);
        });
        handlers.get(type).accept(event);
        return cancelled[0];
    }
}
