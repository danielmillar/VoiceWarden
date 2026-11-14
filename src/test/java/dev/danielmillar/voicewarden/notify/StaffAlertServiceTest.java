package dev.danielmillar.voicewarden.notify;

import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.moderation.Incident;
import dev.danielmillar.voicewarden.moderation.ModerationAction;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.testutil.Fakes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StaffAlertServiceTest {
    @TempDir Path folder;

    @Test void appliedAutoMuteAlertPreparesAPardonAndOtherAlertsPrepareAMute() throws Exception {
        var config = Fakes.with(Fakes.config(folder), "alerts", new PluginConfig.Alerts(true, false));
        var messages = Messages.load(folder.resolve("messages.yml"));
        var received = new ArrayList<Component>();
        UUID staffId = UUID.randomUUID();
        Player staff = Fakes.proxy(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> staffId;
            case "getName" -> "Staff";
            case "hasPermission" -> true;
            case "sendMessage" -> { received.add((Component) a[0]); yield null; }
            default -> throw new AssertionError(m);
        });
        var players = new OnlinePlayers();
        players.add(staff);
        var alerts = new StaffAlertService(() -> config, () -> messages, players, Logger.getAnonymousLogger());

        alerts.incident(incident(ModerationAction.mute(Duration.ofMinutes(5)), false), false);
        alerts.incident(incident(ModerationAction.mute(Duration.ofMinutes(5)), true), false);
        alerts.incident(incident(ModerationAction.mute(Duration.ofMinutes(5)), false), true);
        alerts.incident(incident(ModerationAction.WARN, false), false);

        assertEquals(List.of("/voicewarden pardon Steve ", "/voicewarden vcmute Steve 30m ",
                "/voicewarden vcmute Steve 30m ", "/voicewarden vcmute Steve 30m "),
                received.stream().map(c -> ((ClickEvent.Payload.Text) c.clickEvent().payload()).value()).toList());
    }

    private static Incident incident(ModerationAction action, boolean dryRun) {
        return new Incident(UUID.randomUUID(), Instant.now(), Instant.now(), Duration.ofSeconds(1), UUID.randomUUID(),
                "Steve", "badword", List.of(), 1, 1, 1, action, 1, dryRun, List.of(), null, new short[0], 16000);
    }
}
