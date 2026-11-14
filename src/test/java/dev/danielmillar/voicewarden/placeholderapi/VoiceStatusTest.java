package dev.danielmillar.voicewarden.placeholderapi;

import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.moderation.OffenseTracker;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.mute.VoiceMuteMirror;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VoiceStatusTest {
    @TempDir Path folder;
    private final UUID alice = new UUID(0, 1);
    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());
    private PluginConfig config;
    private Database database;
    private MuteService mutes;
    private OffenseTracker offenses;
    private VoiceStatus status;

    @BeforeEach void setup() throws Exception {
        config = Fakes.config(folder);
        config = Fakes.with(config, "mute", Fakes.with(config.mute(), "notifyPlayer", false));
        var messages = Messages.load(folder.resolve("messages.yml"));
        database = Fakes.database(folder);
        mutes = new MuteService(() -> config, () -> messages, VoiceMuteMirror.NONE, new OnlinePlayers(),
                new Fakes.Executor(false), Logger.getAnonymousLogger(), database);
        offenses = new OffenseTracker(database, Logger.getAnonymousLogger());
        status = new VoiceStatus(mutes, offenses, () -> config, now::get);
    }

    @AfterEach void close() {
        database.close();
    }

    @Test void unmutedPlayerHasNoMuteAndTheFirstLadderStepNext() {
        assertEquals("false", status.resolve(alice, "muted"));
        assertEquals("", status.resolve(alice, "mute_remaining"));
        assertEquals("", status.resolve(alice, "mute_reason"));
        assertEquals("0", status.resolve(alice, "offenses"));
        assertEquals("5m", status.resolve(alice, "next_mute"));
    }

    @Test void timedMuteShowsTimeLeftAndReason() {
        mutes.mute(alice, "Alice", Duration.ofMinutes(10), "slurs", "staff", false).join();
        now.set(mutes.get(alice).orElseThrow().expiresAt() - Duration.ofSeconds(587).toMillis());
        assertEquals("true", status.resolve(alice, "muted"));
        assertEquals("9m 47s", status.resolve(alice, "mute_remaining"));
        assertEquals("slurs", status.resolve(alice, "mute_reason"));
    }

    @Test void permanentMuteShowsPermanent() {
        mutes.mute(alice, "Alice", null, "spam", "staff", false).join();
        assertEquals("true", status.resolve(alice, "muted"));
        assertEquals("permanent", status.resolve(alice, "mute_remaining"));
    }

    @Test void expiredMuteReadsAsUnmuted() {
        mutes.mute(alice, "Alice", Duration.ZERO, "spam", "staff", false).join();
        assertEquals("false", status.resolve(alice, "muted"));
        assertEquals("", status.resolve(alice, "mute_remaining"));
        assertEquals("", status.resolve(alice, "mute_reason"));
    }

    @Test void offencesCountAndDecayAfterTheLadderReset() {
        offenses.recordNext(alice, 0);
        offenses.recordNext(alice, 0);
        assertEquals("2", status.resolve(alice, "offenses"));
        assertEquals("30m", status.resolve(alice, "next_mute"));

        UUID bob = new UUID(0, 2);
        database.write("a stale offence", c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO offenses VALUES (?, 4, 1)")) {
                s.setString(1, bob.toString());
                s.executeUpdate();
            }
        }).join();
        offenses.load();
        assertEquals(30, config.moderation().ladder().resetAfterDays());
        assertEquals("0", status.resolve(bob, "offenses"));
        assertEquals("5m", status.resolve(bob, "next_mute"));
        config = Fakes.with(config, "moderation", Fakes.with(config.moderation(), "ladder",
                Fakes.with(config.moderation().ladder(), "resetAfterDays", 0)));
        assertEquals("4", status.resolve(bob, "offenses"));
    }

    @Test void noPlayerGivesEmptyValuesAndUnknownNamesAreNotHandled() {
        for (String name : VoiceStatus.NAMES) {
            assertEquals("", status.resolve(null, name));
        }
        assertNull(status.resolve(alice, "unknown"));
        assertNull(status.resolve(null, "unknown"));
    }
}
