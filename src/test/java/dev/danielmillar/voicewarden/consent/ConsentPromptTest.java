package dev.danielmillar.voicewarden.consent;

import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.testutil.Fakes;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConsentPromptTest {
    @TempDir Path folder;
    private final UUID id = new UUID(0, 7);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final List<Component> chat = new ArrayList<>();
    private boolean online = true;
    private PluginConfig config;
    private Database database;
    private ConsentService consent;
    private ConsentPrompt prompt;
    private Player player;

    @BeforeEach void setup() throws Exception {
        config = Fakes.config(folder);
        config = Fakes.with(config, "consent", new PluginConfig.Consent(PluginConfig.ConsentMode.CONFIRM, 1,
                PluginConfig.OnDeny.STAY, false));
        Fakes.resource(folder, "messages.yml");
        Messages messages = Messages.load(folder.resolve("messages.yml"));
        database = Fakes.database(folder);
        consent = new ConsentService(() -> config.consent(), database, () -> 1_000L, Logger.getAnonymousLogger());
        Plugin plugin = Fakes.proxy(Plugin.class, (p, m, args) -> { throw new AssertionError(m); });
        EntityScheduler scheduler = Fakes.proxy(EntityScheduler.class, (p, m, args) -> {
            assertEquals("runDelayed", m.getName());
            @SuppressWarnings("unchecked") Consumer<ScheduledTask> task = (Consumer<ScheduledTask>) args[1];
            scheduled.add(() -> task.accept(null));
            delays.add((Long) args[3]);
            return null;
        });
        player = Fakes.proxy(Player.class, (p, m, args) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "Alice";
            case "getScheduler" -> scheduler;
            case "isOnline" -> online;
            case "sendMessage" -> { chat.add((Component) args[0]); yield null; }
            default -> throw new AssertionError(m);
        });
        prompt = new ConsentPrompt(plugin, () -> config, () -> messages, consent, Logger.getAnonymousLogger());
    }

    @AfterEach void close() {
        database.close();
    }

    @Test void deniedPlayerIsRemindedOnceTheClientHasLoaded() {
        consent.answer(id, false);
        prompt.onJoin(player);
        assertTrue(chat.isEmpty(), "Not during the join event, while the client is still loading");
        assertEquals(List.of(20L), delays);
        drain();
        assertEquals(1, chat.size());
    }

    @Test void reminderIsSkippedWhenThePlayerLeftOrAcceptedMeanwhile() {
        consent.answer(id, false);
        prompt.onJoin(player);
        consent.answer(id, true);
        drain();
        assertTrue(chat.isEmpty());

        consent.answer(id, false);
        prompt.onJoin(player);
        online = false;
        drain();
        assertTrue(chat.isEmpty());
    }

    private void drain() {
        List<Runnable> due = List.copyOf(scheduled);
        scheduled.clear();
        due.forEach(Runnable::run);
    }
}
