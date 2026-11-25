package dev.voicewarden.plugin.voice;

import dev.voicewarden.core.config.TranscriptConfig;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.moderation.TranscriptBuffer;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public final class SpeakerContextTracker {

    public static final long REFRESH_TICKS = 20L;

    public record SpeakerLocation(
            @Nullable UUID worldId,
            double x,
            double y,
            double z,
            @Nullable UUID groupId,
            @Nullable String playerName,
            long updatedAtMs) {

        boolean hasPosition() {
            return worldId != null;
        }

        SpeakerLocation withGroup(@Nullable UUID group, long now) {
            return new SpeakerLocation(worldId, x, y, z, group, playerName, now);
        }

        SpeakerLocation withPosition(String name, UUID world, double newX, double newY, double newZ, long now) {
            return new SpeakerLocation(world, newX, newY, newZ, groupId, name, now);
        }
    }

    private static final SpeakerLocation UNKNOWN = new SpeakerLocation(null, 0, 0, 0, null, null, 0L);

    private final TranscriptBuffer transcripts;
    private final Clock clock;
    private final Map<UUID, SpeakerLocation> speakers = new ConcurrentHashMap<>();
    private volatile TranscriptConfig config;

    public SpeakerContextTracker(TranscriptBuffer transcripts, TranscriptConfig config, Clock clock) {
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void updateConfig(TranscriptConfig next) {
        this.config = Objects.requireNonNull(next, "next");
    }

    public void observeGroup(UUID playerId, @Nullable UUID groupId) {
        SpeakerLocation current = speakers.get(playerId);
        if (current != null && Objects.equals(current.groupId(), groupId)) {
            return;
        }
        long now = clock.millis();
        speakers.compute(playerId, (id, existing) -> (existing == null ? UNKNOWN : existing).withGroup(groupId, now));
    }

    public void updatePosition(UUID playerId, String playerName, UUID worldId, double x, double y, double z) {
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(worldId, "worldId");
        long now = clock.millis();
        speakers.compute(playerId, (id, existing) ->
                (existing == null ? UNKNOWN : existing).withPosition(playerName, worldId, x, y, z, now));
    }

    public void capture(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        updatePosition(player.getUniqueId(), player.getName(), world.getUID(), location.getX(), location.getY(), location.getZ());
    }

    public void refresh(Collection<? extends Player> online, TaskScheduler scheduler) {
        if (!config.contextEnabled()) {
            return;
        }
        for (Player player : online) {
            scheduler.runForEntity(player, () -> capture(player));
        }
    }

    public void forget(UUID playerId) {
        speakers.remove(playerId);
    }

    public Map<UUID, String> peersOf(UUID subjectId, Instant now) {
        TranscriptConfig current = config;
        SpeakerLocation subject = speakers.get(subjectId);
        if (!current.contextEnabled() || subject == null) {
            return Map.of();
        }
        long freshAfter = now.toEpochMilli() - Duration.ofSeconds(current.contextWindowSeconds()).toMillis();
        if (subject.updatedAtMs() < freshAfter) {
            return Map.of();
        }
        double radiusSquared = (double) current.contextRadiusBlocks() * current.contextRadiusBlocks();
        Map<UUID, String> peers = new HashMap<>();
        for (Map.Entry<UUID, SpeakerLocation> entry : speakers.entrySet()) {
            SpeakerLocation peer = entry.getValue();
            if (entry.getKey().equals(subjectId) || peer.updatedAtMs() < freshAfter) {
                continue;
            }
            if (sameGroup(subject, peer) || within(subject, peer, radiusSquared)) {
                peers.put(entry.getKey(), peer.playerName() != null ? peer.playerName() : entry.getKey().toString().substring(0, 8));
            }
        }
        return peers;
    }

    public List<ConversationLine> conversation(UUID subjectId, Instant from, Instant to) {
        TranscriptConfig current = config;
        if (!current.contextEnabled()) {
            return List.of();
        }
        return transcripts.conversation(peersOf(subjectId, to), from, to, current.contextLines());
    }

    public List<ConversationLine> recentConversation(UUID subjectId, Instant detectedAt, Instant now) {
        Instant from = detectedAt.minusSeconds(config.contextWindowSeconds());
        return conversation(subjectId, from, now.isBefore(detectedAt) ? detectedAt : now);
    }

    private static boolean sameGroup(SpeakerLocation subject, SpeakerLocation peer) {
        return subject.groupId() != null && subject.groupId().equals(peer.groupId());
    }

    private static boolean within(SpeakerLocation subject, SpeakerLocation peer, double radiusSquared) {
        if (!subject.hasPosition() || !subject.worldId().equals(peer.worldId())) {
            return false;
        }
        double dx = subject.x() - peer.x();
        double dy = subject.y() - peer.y();
        double dz = subject.z() - peer.z();
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }
}
