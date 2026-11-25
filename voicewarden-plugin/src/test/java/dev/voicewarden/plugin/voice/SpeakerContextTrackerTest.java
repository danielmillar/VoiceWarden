package dev.voicewarden.plugin.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.voicewarden.core.audio.RollingOpusBuffer;
import dev.voicewarden.core.audio.ShardedAudioPipeline;
import dev.voicewarden.core.config.TranscriptConfig;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.moderation.TranscriptBuffer;
import dev.voicewarden.core.moderation.TranscriptEntry;
import dev.voicewarden.plugin.FakePlayers;
import dev.voicewarden.plugin.ManualScheduler;
import dev.voicewarden.plugin.MutableClock;
import dev.voicewarden.plugin.scheduler.TaskHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class SpeakerContextTrackerTest {

    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final TranscriptBuffer transcripts = TranscriptBuffer.withDefaults();
    private final SpeakerContextTracker tracker = new SpeakerContextTracker(transcripts, config(4), clock);
    private final UUID overworld = UUID.randomUUID();
    private final UUID nether = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final UUID peer = UUID.randomUUID();

    private static TranscriptConfig config(int lines) {
        return new TranscriptConfig(43_200, 500, lines, 60, 48);
    }

    @Test
    void playersWithinTheRadiusInTheSameWorldAreNearby() {
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", overworld, 30, 70, 30);

        assertEquals(Map.of(peer, "Peer"), tracker.peersOf(subject, START));
    }

    @Test
    void theRadiusBoundaryIsInclusiveAndAnythingBeyondIsOutOfRange() {
        UUID edge = UUID.randomUUID();
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(edge, "Edge", overworld, 48, 64, 0);
        tracker.updatePosition(peer, "Peer", overworld, 48.5, 64, 0);

        assertEquals(Map.of(edge, "Edge"), tracker.peersOf(subject, START));
    }

    @Test
    void theSameCoordinatesInAnotherWorldAreNotNearby() {
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", nether, 0, 64, 0);

        assertEquals(Map.of(), tracker.peersOf(subject, START));
    }

    @Test
    void sharingAVoiceGroupCountsAcrossWorldsAndDistance() {
        UUID party = UUID.randomUUID();
        UUID otherParty = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", nether, 9_000, 64, 9_000);
        tracker.updatePosition(stranger, "Stranger", nether, 9_000, 64, 9_000);
        tracker.observeGroup(subject, party);
        tracker.observeGroup(peer, party);
        tracker.observeGroup(stranger, otherParty);

        assertEquals(Map.of(peer, "Peer"), tracker.peersOf(subject, START));
    }

    @Test
    void leavingAGroupStopsCountingGroupMembers() {
        UUID party = UUID.randomUUID();
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", nether, 0, 64, 0);
        tracker.observeGroup(subject, party);
        tracker.observeGroup(peer, party);
        tracker.observeGroup(subject, null);

        assertEquals(Map.of(), tracker.peersOf(subject, START));
    }

    @Test
    void groupAndPositionUpdatesDoNotOverwriteEachOther() {
        UUID party = UUID.randomUUID();
        tracker.observeGroup(subject, party);
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", nether, 0, 64, 0);
        tracker.observeGroup(peer, party);
        tracker.updatePosition(peer, "Peer", nether, 5, 64, 5);

        assertEquals(Map.of(peer, "Peer"), tracker.peersOf(subject, START));
    }

    @Test
    void stalePeersAndAStaleSubjectAreIgnored() {
        tracker.updatePosition(peer, "Peer", overworld, 1, 64, 1);
        clock.advance(Duration.ofSeconds(61));
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);

        assertEquals(Map.of(), tracker.peersOf(subject, clock.instant()));

        tracker.updatePosition(peer, "Peer", overworld, 1, 64, 1);
        assertEquals(Map.of(peer, "Peer"), tracker.peersOf(subject, clock.instant()));
        assertEquals(Map.of(), tracker.peersOf(subject, clock.instant().plusSeconds(61)));
    }

    @Test
    void forgottenPlayersAreNeitherSubjectsNorPeers() {
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", overworld, 1, 64, 1);

        tracker.forget(peer);
        assertEquals(Map.of(), tracker.peersOf(subject, START));

        tracker.updatePosition(peer, "Peer", overworld, 1, 64, 1);
        tracker.forget(subject);
        assertEquals(Map.of(), tracker.peersOf(subject, START));
        assertEquals(Map.of(), tracker.peersOf(UUID.randomUUID(), START));
    }

    @Test
    void conversationReturnsOnlyNearbyPeersLinesCappedAndChronological() {
        UUID far = UUID.randomUUID();
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", overworld, 2, 64, 2);
        tracker.updatePosition(far, "Far", overworld, 400, 64, 400);
        transcripts.append(subject, TranscriptEntry.clean(START.minusSeconds(3), "my own words"));
        transcripts.append(far, TranscriptEntry.clean(START.minusSeconds(4), "too far away"));
        for (int i = 0; i < 6; i++) {
            transcripts.append(peer, TranscriptEntry.clean(START.minusSeconds(50 - i * 5L), "peer " + i));
        }
        transcripts.append(peer, TranscriptEntry.clean(START.minusSeconds(120), "before the window"));

        List<ConversationLine> lines = tracker.recentConversation(subject, START, START);

        assertEquals(List.of("peer 2", "peer 3", "peer 4", "peer 5"), lines.stream().map(ConversationLine::text).toList());
        assertTrue(lines.stream().allMatch(line -> line.playerId().equals(peer) && line.playerName().equals("Peer")));
    }

    @Test
    void recentConversationIsAnchoredAtDetectionAndRunsUntilNow() {
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", overworld, 2, 64, 2);
        transcripts.append(peer, TranscriptEntry.clean(START.minusSeconds(55), "before detection"));
        transcripts.append(peer, TranscriptEntry.clean(START.plusSeconds(20), "while verifying"));

        List<ConversationLine> lines = tracker.recentConversation(subject, START, START.plusSeconds(30));

        assertEquals(List.of("before detection", "while verifying"), lines.stream().map(ConversationLine::text).toList());
    }

    @Test
    void zeroContextLinesDisablesTheFeature() {
        tracker.updateConfig(config(0));
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", overworld, 2, 64, 2);
        transcripts.append(peer, TranscriptEntry.clean(START.minusSeconds(5), "hello"));

        assertEquals(Map.of(), tracker.peersOf(subject, START));
        assertEquals(List.of(), tracker.recentConversation(subject, START, START));
    }

    @Test
    void refreshReadsLocationsOnlyInsideEachPlayersEntityTask() {
        FakePlayers players = new FakePlayers();
        Player alex = players.join("Alex");
        World world = mock(World.class);
        when(world.getUID()).thenReturn(overworld);
        when(alex.getLocation()).thenReturn(new Location(world, 4, 64, 4));
        DeferredEntityScheduler scheduler = new DeferredEntityScheduler();

        tracker.refresh(players.onlinePlayers(), scheduler);

        verify(alex, never()).getLocation();
        assertEquals(1, scheduler.entityTasks.size());
        scheduler.entityTasks.forEach(Runnable::run);
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        assertEquals(Map.of(alex.getUniqueId(), "Alex"), tracker.peersOf(subject, START));
    }

    @Test
    void refreshDoesNothingWhileContextIsDisabled() {
        tracker.updateConfig(config(0));
        FakePlayers players = new FakePlayers();
        players.join("Alex");
        DeferredEntityScheduler scheduler = new DeferredEntityScheduler();

        tracker.refresh(players.onlinePlayers(), scheduler);

        assertTrue(scheduler.entityTasks.isEmpty());
    }

    @Test
    void theAudioSinkForwardsGroupsAndForgetsPlayersWhoLeave() {
        UUID party = UUID.randomUUID();
        VoiceAudioSink sink = VoiceAudioSink.of(mock(ShardedAudioPipeline.class), new RollingOpusBuffer(Duration.ZERO), tracker, clock);
        tracker.updatePosition(subject, "Subject", overworld, 0, 64, 0);
        tracker.updatePosition(peer, "Peer", nether, 0, 64, 0);
        sink.voiceGroup(subject, party);
        sink.voiceGroup(peer, party);
        assertEquals(Map.of(peer, "Peer"), tracker.peersOf(subject, START));

        sink.playerLeft(peer);
        assertEquals(Map.of(), tracker.peersOf(subject, START));
    }

    private static final class DeferredEntityScheduler extends ManualScheduler {
        final List<Runnable> entityTasks = new ArrayList<>();

        @Override
        public TaskHandle runForEntity(Entity entity, Runnable task) {
            entityTasks.add(task);
            return TaskHandle.NONE;
        }
    }
}
