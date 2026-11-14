package dev.danielmillar.voicewarden.voice;

import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.config.PluginConfig.MuteDisplay;
import dev.danielmillar.voicewarden.consent.ConsentService;
import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.testutil.Fakes;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
    private final List<Repeating> repeating = new ArrayList<>();
    private final List<Component> actionBars = new ArrayList<>();
    private final List<Component> chat = new ArrayList<>();
    private boolean canSpeak;
    private long now;
    private PluginConfig config;
    private Messages messages;
    private MuteService mutes;
    private ConsentService consent;
    private VoiceSpeechNotice notice;
    private Player player;

    /** A repeating task on the player's scheduler, ticked by hand. */
    private static final class Repeating {
        final Consumer<ScheduledTask> body;
        final ScheduledTask task;
        boolean cancelled;

        Repeating(Consumer<ScheduledTask> body) {
            this.body = body;
            this.task = Fakes.proxy(ScheduledTask.class, (p, m, args) -> {
                assertEquals("cancel", m.getName());
                cancelled = true;
                return ScheduledTask.CancelledState.CANCELLED_BY_CALLER;
            });
        }

        void tick() {
            if (!cancelled) body.accept(task);
        }
    }

    @BeforeEach void setup() throws Exception {
        config = Fakes.config(folder);
        mute("luckPerms", false);
        messages = Messages.load(folder.resolve("messages.yml"));
        Logger logger = Logger.getLogger("VoiceSpeechNoticeTest");
        var permissions = new LuckPermsHook(Fakes.proxy(LuckPerms.class,
                (p, m, args) -> { throw new AssertionError("Unexpected LuckPerms call"); }));
        var database = Fakes.database(folder);
        mutes = new MuteService(() -> config, () -> messages, permissions, new OnlinePlayers(), Runnable::run, logger, database);
        consent = new ConsentService(() -> config.consent(), database, () -> now, logger);
        Plugin plugin = Fakes.proxy(Plugin.class, (p, m, args) -> { throw new AssertionError(m); });
        EntityScheduler scheduler = Fakes.proxy(EntityScheduler.class, (p, m, args) -> {
            assertSame(plugin, args[0]);
            @SuppressWarnings("unchecked") Consumer<ScheduledTask> callback = (Consumer<ScheduledTask>) args[1];
            return switch (m.getName()) {
                case "run" -> { scheduled.add(() -> callback.accept(null)); yield null; }
                case "runAtFixedRate" -> {
                    assertEquals(20L, args[4]);
                    var task = new Repeating(callback);
                    repeating.add(task);
                    yield task.task;
                }
                default -> throw new AssertionError(m);
            };
        });
        player = Fakes.proxy(Player.class, (p, m, args) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "Alice";
            case "getScheduler" -> scheduler;
            case "hasPermission" -> {
                assertEquals("voicechat.speak", args[0]);
                yield canSpeak;
            }
            case "sendActionBar" -> { actionBars.add((Component) args[0]); yield null; }
            case "sendMessage" -> { chat.add((Component) args[0]); yield null; }
            default -> throw new AssertionError(m);
        });
        notice = new VoiceSpeechNotice(plugin, () -> config, () -> messages, mutes, consent, () -> now, logger);
    }

    @Test void playerWhoHasNotAcceptedIsToldHowToAcceptEvenWithMuteNoticesOff() {
        mute("notifyPlayer", false);
        config = Fakes.with(config, "consent", Fakes.with(config.consent(), "mode", PluginConfig.ConsentMode.CONFIRM));
        assertTrue(notice.onDeniedSpeech(player));
        drain();
        assertEquals(1, actionBars.size());
        assertTrue(text(actionBars).contains("/voiceconsent"));
        consent.answer(id, true);
        now = 5_000;
        assertFalse(notice.onDeniedSpeech(player));
    }

    @Test void externalPermissionDenialUsesCustomActionBarOnPlayerScheduler() {
        assertTrue(notice.onDeniedSpeech(player));
        assertTrue(actionBars.isEmpty(), "Never send from the voice-chat packet thread");
        drain();
        assertEquals(1, actionBars.size());
        assertTrue(text(actionBars).contains("You don't have permission to speak in voice chat."));
        assertTrue(repeating.isEmpty());
    }

    @Test void reminderIsSentAtMostOncePerIntervalWhileTheMutedPlayerKeepsTalking() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        for (int i = 0; i < 1000; i++) assertTrue(notice.onDeniedSpeech(player));
        now = 29_999;
        assertTrue(notice.onDeniedSpeech(player));
        drain();
        assertEquals(1, chat.size());
        assertTrue(text(chat).contains("nobody can hear you"));
        now = 30_000;
        notice.onDeniedSpeech(player);
        drain();
        assertEquals(2, chat.size());
    }

    @Test void zeroReminderIntervalSendsNoChatButKeepsTheStatus() {
        mute("reminderInterval", Duration.ZERO);
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        assertTrue(notice.onDeniedSpeech(player));
        drain();
        assertTrue(chat.isEmpty());
        assertEquals(1, repeating.size());
    }

    @Test void displayOffLeavesTheDenialToVoiceChatAndStartsNoStatus() {
        mute("display", MuteDisplay.OFF);
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        assertFalse(notice.onDeniedSpeech(player));
        notice.show(player);
        assertTrue(scheduled.isEmpty());
        assertTrue(repeating.isEmpty());
    }

    @Test void statusRunsOnlyForMutedPlayersOnceAndShowsTimeLeft() {
        notice.show(player);
        assertTrue(repeating.isEmpty(), "Nobody muted means no task");
        mutes.mute(id, "Alice", Duration.ofMinutes(5), "reason", "staff", false).join();
        notice.show(player);
        notice.show(player);
        notice.onDeniedSpeech(player);
        assertEquals(1, repeating.size());
        tick();
        assertTrue(text(actionBars).contains("Voice muted"));
        assertTrue(text(actionBars).contains("5m left"));
    }

    @Test void statusEndsOnUnmuteAndClearsTheActionBar() {
        mutes.mute(id, "Alice", Duration.ofMinutes(5), "reason", "staff", false).join();
        notice.show(player);
        tick();
        mutes.unmute(id, "staff").join();
        tick();
        assertTrue(repeating.getFirst().cancelled);
        assertEquals("", text(actionBars));
        notice.show(player);
        assertEquals(1, repeating.size());
    }

    @Test void statusEndsWhenTheDisplayChangesToChat() {
        mutes.mute(id, "Alice", Duration.ofMinutes(5), "reason", "staff", false).join();
        notice.show(player);
        tick();
        mute("display", MuteDisplay.CHAT);
        tick();
        assertTrue(repeating.getFirst().cancelled);
        assertEquals("", text(actionBars));
    }

    @Test void shutdownStopsEveryNotice() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        notice.show(player);
        tick();
        notice.onDeniedSpeech(player);
        notice.close();
        drain();
        assertTrue(chat.isEmpty());
        assertTrue(repeating.getFirst().cancelled);
        assertFalse(notice.onDeniedSpeech(player));
    }

    @Test void disconnectStopsTheStatusAndResetsTheReminder() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        notice.onDeniedSpeech(player);
        notice.onDisconnect(id);
        assertTrue(repeating.getFirst().cancelled);
        notice.onDeniedSpeech(player);
        assertEquals(2, scheduled.size());
        assertEquals(2, repeating.size());
    }

    @Test void respawnStartsAFreshStatusBecausePaperDropsTheOldTask() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        notice.show(player);
        notice.onRespawn(player);
        assertEquals(2, repeating.size());
        assertTrue(repeating.getFirst().cancelled);
        tick();
        assertTrue(text(actionBars).contains("permanent"));
    }

    @Test void restoredPermissionBeforeTheScheduledSendPreventsAStaleNotice() {
        notice.onDeniedSpeech(player);
        canSpeak = true;
        drain();
        assertTrue(actionBars.isEmpty());
    }

    @Test void unmuteBeforeTheScheduledReminderPreventsAStaleReminder() {
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        notice.onDeniedSpeech(player);
        mutes.unmute(id, "staff").join();
        canSpeak = true;
        drain();
        assertTrue(chat.isEmpty());
    }

    @Test void disabledNotificationsAllowTheOriginalVoiceChatNotice() {
        mute("notifyPlayer", false);
        mutes.mute(id, "Alice", null, "reason", "staff", false).join();
        assertFalse(notice.onDeniedSpeech(player));
        notice.show(player);
        assertTrue(scheduled.isEmpty());
        assertTrue(repeating.isEmpty());
    }

    private void mute(String field, Object value) {
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), field, value));
    }

    private void tick() {
        List.copyOf(repeating).forEach(Repeating::tick);
    }

    private void drain() {
        List<Runnable> pending = List.copyOf(scheduled);
        scheduled.clear();
        pending.forEach(Runnable::run);
    }

    private static String text(List<Component> sent) {
        return PlainTextComponentSerializer.plainText().serialize(sent.getLast());
    }
}
