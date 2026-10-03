package dev.danielmillar.voicesentinel.voice;

import dev.danielmillar.voicesentinel.config.Messages;
import dev.danielmillar.voicesentinel.config.PluginConfig;
import dev.danielmillar.voicesentinel.luckperms.LuckPermsHook;
import dev.danielmillar.voicesentinel.mute.MuteService;
import dev.danielmillar.voicesentinel.player.OnlinePlayers;
import dev.danielmillar.voicesentinel.testutil.Fakes;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VoiceSpeechNoticeTest {
    @TempDir Path folder;
    private final UUID id = new UUID(0, 42);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Component> actionBars = new ArrayList<>();
    private boolean canSpeak;
    private PluginConfig config;
    private Messages messages;
    private MuteService mutes;
    private VoiceSpeechNotice notice;
    private Player player;

    @BeforeEach void setup() throws Exception {
        config = Fakes.config(folder);
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "luckPerms", false));
        messages = Messages.load(folder.resolve("messages.yml"));
        Logger logger = Logger.getLogger("VoiceSpeechNoticeTest");
        var permissions = new LuckPermsHook(Fakes.proxy(LuckPerms.class,
                (p, m, args) -> { throw new AssertionError("Unexpected LuckPerms call"); }));
        mutes = new MuteService(() -> config, () -> messages, permissions, new OnlinePlayers(), Runnable::run, logger, folder);
        Plugin plugin = Fakes.proxy(Plugin.class, (p, m, args) -> { throw new AssertionError(m); });
        EntityScheduler scheduler = Fakes.proxy(EntityScheduler.class, (p, m, args) -> {
            assertEquals("run", m.getName());
            assertSame(plugin, args[0]);
            @SuppressWarnings("unchecked")
            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> callback = (Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>) args[1];
            scheduled.add(() -> callback.accept(null));
            return null;
        });
        player = Fakes.proxy(Player.class, (p, m, args) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "getScheduler" -> scheduler;
            case "hasPermission" -> {
                assertEquals("voicechat.speak", args[0]);
                yield canSpeak;
            }
            case "sendActionBar" -> { actionBars.add((Component) args[0]); yield null; }
            default -> throw new AssertionError(m);
        });
        notice = new VoiceSpeechNotice(plugin, () -> config, () -> messages, mutes, logger);
    }

    @Test void externalPermissionDenialUsesCustomActionBarOnPlayerScheduler() {
        assertTrue(notice.onDeniedSpeech(player));
        assertTrue(actionBars.isEmpty(), "Never send from the voice-chat packet thread");
        drain();
        assertEquals(1, actionBars.size());
        assertTrue(text().contains("You don't have permission to speak in voice chat."));
        assertFalse(text().contains("remaining"));
    }

    @Test void localMuteNoticeIncludesRemainingTimeAndRepeatedPacketsAreThrottled() {
        mutes.mute(id, "Alice", Duration.ofMinutes(5), "reason", "staff", false).join();
        assertTrue(notice.onDeniedSpeech(player));
        for (int i = 0; i < 1000; i++) assertTrue(notice.onDeniedSpeech(player));
        assertEquals(1, scheduled.size());
        drain();
        assertTrue(text().contains("You cannot speak: you are voice-muted."));
        assertTrue(text().contains("remaining"));
        assertTrue(text().contains("5m"));
    }

    @Test void permanentMuteNoticeNeverShowsANullOrNegativeDuration() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        notice.onDeniedSpeech(player);
        drain();
        assertTrue(text().contains("permanent"));
        assertFalse(text().contains("null"));
    }

    @Test void restoredPermissionBeforeTheScheduledSendPreventsAStaleNotice() {
        notice.onDeniedSpeech(player);
        canSpeak = true;
        drain();
        assertTrue(actionBars.isEmpty());
    }

    @Test void unmuteBeforeTheScheduledSendPreventsAStaleMuteNotice() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        notice.onDeniedSpeech(player);
        mutes.unmute(id).join();
        canSpeak = true;
        drain();
        assertTrue(actionBars.isEmpty());
    }

    @Test void disabledNotificationsAllowTheOriginalVoiceChatNotice() {
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "notifyPlayer", false));
        assertFalse(notice.onDeniedSpeech(player));
        assertTrue(scheduled.isEmpty());
    }

    @Test void disconnectClearsThrottleAndShutdownCancelsQueuedNotices() {
        notice.onDeniedSpeech(player);
        notice.onDisconnect(id);
        notice.onDeniedSpeech(player);
        assertEquals(2, scheduled.size());
        notice.close();
        drain();
        assertTrue(actionBars.isEmpty());
        assertFalse(notice.onDeniedSpeech(player));
    }

    private void drain() {
        List<Runnable> pending = List.copyOf(scheduled);
        scheduled.clear();
        pending.forEach(Runnable::run);
    }

    private String text() {
        return PlainTextComponentSerializer.plainText().serialize(actionBars.getLast());
    }
}
