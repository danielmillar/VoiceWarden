package dev.voicewarden.core.moderation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TranscriptBuffer {

    public static final int DEFAULT_RETENTION_SECONDS = 43_200;
    public static final int DEFAULT_MAX_LINES_PER_PLAYER = 500;

    private final Duration retention;
    private final int maxLinesPerPlayer;
    private final Map<UUID, Deque<TranscriptEntry>> lines = new ConcurrentHashMap<>();

    public TranscriptBuffer(Duration retention, int maxLinesPerPlayer) {
        if (!retention.isPositive() || maxLinesPerPlayer < 1) {
            throw new IllegalArgumentException("retention and maxLinesPerPlayer must be positive");
        }
        this.retention = retention;
        this.maxLinesPerPlayer = maxLinesPerPlayer;
    }

    public static TranscriptBuffer withDefaults() {
        return new TranscriptBuffer(Duration.ofSeconds(DEFAULT_RETENTION_SECONDS), DEFAULT_MAX_LINES_PER_PLAYER);
    }

    public Duration retention() {
        return retention;
    }

    public int maxLinesPerPlayer() {
        return maxLinesPerPlayer;
    }

    public int maxWindowMinutes() {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (retention.getSeconds() + 59) / 60));
    }

    public void append(UUID playerId, TranscriptEntry entry) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(entry, "entry");
        lines.compute(playerId, (id, existing) -> {
            Deque<TranscriptEntry> deque = existing != null ? existing : new ArrayDeque<>();
            synchronized (deque) {
                deque.addLast(entry);
                while (deque.size() > maxLinesPerPlayer) {
                    deque.pollFirst();
                }
                evictExpired(deque, entry.timestamp());
            }
            return deque;
        });
    }

    public List<TranscriptEntry> getRecent(UUID playerId, int minutes, Instant now) {
        int clamped = Math.max(1, Math.min(minutes, maxWindowMinutes()));
        Instant windowStart = now.minus(Duration.ofMinutes(clamped));
        Instant retentionStart = now.minus(retention);
        Instant from = windowStart.isAfter(retentionStart) ? windowStart : retentionStart;
        Deque<TranscriptEntry> deque = lines.get(playerId);
        if (deque == null) {
            return List.of();
        }
        List<TranscriptEntry> out = new ArrayList<>();
        synchronized (deque) {
            evictExpired(deque, now);
            for (TranscriptEntry entry : deque) {
                if (!entry.timestamp().isBefore(from)) {
                    out.add(entry);
                }
            }
        }
        out.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
        return List.copyOf(out);
    }

    public List<ConversationLine> conversation(Map<UUID, String> speakers, Instant from, Instant to, int maxLines) {
        Objects.requireNonNull(speakers, "speakers");
        if (maxLines <= 0 || speakers.isEmpty() || to.isBefore(from)) {
            return List.of();
        }
        Instant retentionStart = to.minus(retention);
        Instant start = from.isAfter(retentionStart) ? from : retentionStart;
        List<ConversationLine> out = new ArrayList<>();
        for (Map.Entry<UUID, String> speaker : speakers.entrySet()) {
            Deque<TranscriptEntry> deque = lines.get(speaker.getKey());
            if (deque == null) {
                continue;
            }
            synchronized (deque) {
                for (TranscriptEntry entry : deque) {
                    if (!entry.timestamp().isBefore(start) && !entry.timestamp().isAfter(to)) {
                        out.add(ConversationLine.of(speaker.getKey(), speaker.getValue(), entry));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(ConversationLine::timestamp)
                .thenComparing(ConversationLine::playerName)
                .thenComparing(ConversationLine::playerId));
        return out.size() <= maxLines ? List.copyOf(out) : List.copyOf(out.subList(out.size() - maxLines, out.size()));
    }

    public int size(UUID playerId) {
        Deque<TranscriptEntry> deque = lines.get(playerId);
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            return deque.size();
        }
    }

    public void prune(Instant now) {
        for (UUID playerId : lines.keySet()) {
            lines.computeIfPresent(playerId, (id, deque) -> {
                synchronized (deque) {
                    evictExpired(deque, now);
                    return deque.isEmpty() ? null : deque;
                }
            });
        }
    }

    public void clear(UUID playerId) {
        lines.remove(playerId);
    }

    private void evictExpired(Deque<TranscriptEntry> deque, Instant now) {
        Instant cutoff = now.minus(retention);
        deque.removeIf(entry -> entry.timestamp().isBefore(cutoff));
    }
}
