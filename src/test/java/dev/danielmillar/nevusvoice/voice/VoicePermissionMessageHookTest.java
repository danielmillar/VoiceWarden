package dev.danielmillar.nevusvoice.voice;

import dev.danielmillar.nevusvoice.testutil.Fakes;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VoicePermissionMessageHookTest {
    public interface Compatibility {
        void sendStatusMessage(Player player, String key, String... args);
        int anotherOperation(int value);
    }
    public static final class Holder {
        public static Compatibility compatibility;
    }

    private final List<String> originals = new ArrayList<>();
    private final List<Player> custom = new ArrayList<>();
    private final Logger logger = Logger.getLogger("VoicePermissionMessageHookTest");
    private VoicePermissionMessageHook hook;
    private Compatibility original;
    private Player player;

    @BeforeEach void setup() throws Exception {
        original = Fakes.proxy(Compatibility.class, (p, m, args) -> switch (m.getName()) {
            case "sendStatusMessage" -> { originals.add((String) args[1]); yield null; }
            case "anotherOperation" -> (Integer) args[0] + 1;
            default -> throw new AssertionError(m);
        });
        Holder.compatibility = original;
        player = Fakes.proxy(Player.class, (p, m, args) -> { throw new AssertionError(m); });
        hook = VoicePermissionMessageHook.install(Holder.class.getField("compatibility"), p -> {
            custom.add(p);
            return true;
        }, logger);
    }

    @AfterEach void cleanup() {
        if (hook != null) hook.close();
        Holder.compatibility = null;
    }

    @Test void replacesOnlyTheNoSpeakNoticeAndForwardsAllOtherCalls() {
        Holder.compatibility.sendStatusMessage(player, "message.voicechat.no_speak_permission");
        assertEquals(List.of(player), custom);
        assertTrue(originals.isEmpty());
        Holder.compatibility.sendStatusMessage(player, "message.voicechat.no_listen_permission", "argument");
        assertEquals(List.of("message.voicechat.no_listen_permission"), originals);
        assertEquals(6, Holder.compatibility.anotherOperation(5));
    }

    @Test void disabledCustomNoticeFallsBackToVoiceChat() throws Exception {
        hook.close();
        hook = VoicePermissionMessageHook.install(Holder.class.getField("compatibility"), p -> false, logger);
        Holder.compatibility.sendStatusMessage(player, "message.voicechat.no_speak_permission");
        assertEquals(List.of("message.voicechat.no_speak_permission"), originals);
    }

    @Test void shutdownRestoresTheOriginalAdapterAndDeactivatesAnyRetainedWrapper() {
        Compatibility retained = Holder.compatibility;
        hook.close();
        assertSame(original, Holder.compatibility);
        retained.sendStatusMessage(player, "message.voicechat.no_speak_permission");
        assertTrue(custom.isEmpty());
        assertEquals(List.of("message.voicechat.no_speak_permission"), originals);
    }

    @Test void shutdownDoesNotOverwriteAReplacementInstalledByAnotherPlugin() {
        Compatibility later = Fakes.proxy(Compatibility.class, (p, m, args) -> null);
        Holder.compatibility = later;
        hook.close();
        assertSame(later, Holder.compatibility);
    }

    @Test void noticeFailureFallsBackWithoutBreakingVoiceChat() throws Exception {
        hook.close();
        hook = VoicePermissionMessageHook.install(Holder.class.getField("compatibility"),
                p -> { throw new IllegalStateException("notice failed"); }, logger);
        assertDoesNotThrow(() -> Holder.compatibility.sendStatusMessage(player, "message.voicechat.no_speak_permission"));
        assertEquals(List.of("message.voicechat.no_speak_permission"), originals);
    }
}
