package dev.voicewarden.plugin.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.core.config.MutesConfig;
import dev.voicewarden.core.storage.InMemoryFallbackStore;
import dev.voicewarden.core.storage.StrikeRecord;
import dev.voicewarden.plugin.FakePlayers;
import dev.voicewarden.plugin.ManualScheduler;
import dev.voicewarden.plugin.MutableClock;
import dev.voicewarden.plugin.TestMessages;
import dev.voicewarden.plugin.consent.ConsentView;
import dev.voicewarden.plugin.mute.MuteMirror;
import dev.voicewarden.plugin.mute.NativeMuteService;
import dev.voicewarden.plugin.mute.StrikeCountCache;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

class VoiceWardenExpansionTest {

    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryFallbackStore storage = new InMemoryFallbackStore("server-1");
    private final Map<UUID, String> consentStates = new HashMap<>();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final NativeMuteService mutes = new NativeMuteService(
            new MutesConfig(MutesConfig.Enforcement.NATIVE, false,
                    new MutesConfig.Block(true, true, MutesConfig.GroupBlock.ALL, List.of(), false), MutesConfig.Indicator.NONE, true, 30),
            storage, scheduler, new FakePlayers(), TestMessages.renderer(), event -> { }, MuteMirror.NONE,
            new StrikeCountCache((id, since) -> storage.countActiveStrikes(id, since, false), scheduler::runAsync, clock,
                    Duration.ofMinutes(10), Duration.ofSeconds(30)),
            id -> true, clock, "server-1", Logger.getLogger("test"));
    private final VoiceWardenExpansion expansion = new VoiceWardenExpansion(mutes, new ConsentView() {
        @Override
        public boolean isMonitoringAllowed(UUID playerId) {
            return true;
        }

        @Override
        public String placeholderStatus(UUID playerId) {
            return consentStates.getOrDefault(playerId, "pending");
        }
    }, "1.0.0");

    private static OfflinePlayer player(UUID id) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    void identityIsPersistentVoicewarden() {
        assertEquals("voicewarden", expansion.getIdentifier());
        assertTrue(expansion.persist());
        assertEquals("1.0.0", expansion.getVersion());
    }

    @Test
    void timedMute() {
        UUID id = UUID.randomUUID();
        mutes.mutePlayer(id, "Steve", Duration.ofMinutes(90), "toxic", "Mod").join();
        clock.advance(Duration.ofMinutes(30));
        OfflinePlayer steve = player(id);
        assertEquals("true", expansion.onRequest(steve, "muted"));
        assertEquals("1h", expansion.onRequest(steve, "mute_remaining"));
        assertEquals("toxic", expansion.onRequest(steve, "mute_reason"));
    }

    @Test
    void permanentMute() {
        UUID id = UUID.randomUUID();
        mutes.mutePlayer(id, "Steve", null, "spam", "Mod").join();
        OfflinePlayer steve = player(id);
        assertEquals("true", expansion.onRequest(steve, "muted"));
        assertEquals("Permanent", expansion.onRequest(steve, "mute_remaining"));
        assertEquals("spam", expansion.onRequest(steve, "mute_reason"));
    }

    @Test
    void unmutedAndExpired() {
        UUID id = UUID.randomUUID();
        OfflinePlayer steve = player(id);
        assertEquals("false", expansion.onRequest(steve, "muted"));
        assertEquals("", expansion.onRequest(steve, "mute_remaining"));
        assertEquals("", expansion.onRequest(steve, "mute_reason"));

        mutes.mutePlayer(id, "Steve", Duration.ofMinutes(1), "r", "Mod").join();
        clock.advance(Duration.ofMinutes(1));
        assertEquals("false", expansion.onRequest(steve, "muted"));
        assertEquals("", expansion.onRequest(steve, "mute_remaining"));
    }

    @Test
    void strikesCountOnlyTheRollingWindowFromMemory() {
        UUID id = UUID.randomUUID();
        storage.recordStrike(StrikeRecord.unsaved(id, "Steve", FlagSeverity.MILD, "a", "a", START.minus(Duration.ofMinutes(20)), "server-1"));
        storage.recordStrike(StrikeRecord.unsaved(id, "Steve", FlagSeverity.MILD, "a", "a", START.minus(Duration.ofMinutes(2)), "server-1"));
        storage.recordStrike(StrikeRecord.unsaved(id, "Steve", FlagSeverity.SEVERE, "b", "b", START, "server-1"));
        assertEquals("2", expansion.onRequest(player(id), "strikes"));
        assertEquals("0", expansion.onRequest(player(UUID.randomUUID()), "strikes"));
    }

    @Test
    void consentStates() {
        UUID id = UUID.randomUUID();
        for (String state : List.of("accepted", "denied", "pending", "outdated", "disabled")) {
            consentStates.put(id, state);
            assertEquals(state, expansion.onRequest(player(id), "consent"));
        }
    }

    @Test
    void unknownPlaceholderAndMissingPlayer() {
        assertNull(expansion.onRequest(player(UUID.randomUUID()), "nope"));
        assertEquals("", expansion.onRequest(null, "muted"));
    }
}
