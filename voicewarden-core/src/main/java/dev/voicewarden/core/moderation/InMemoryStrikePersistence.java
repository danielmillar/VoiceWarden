package dev.voicewarden.core.moderation;

import dev.voicewarden.api.FlagSeverity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryStrikePersistence implements StrikePersistence {

    public static final Duration DEFAULT_HISTORY = Duration.ofDays(60);

    private final Duration history;
    private final Map<UUID, Deque<Instant>> strikes = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Instant>> mutes = new ConcurrentHashMap<>();

    public InMemoryStrikePersistence() {
        this(DEFAULT_HISTORY);
    }

    public InMemoryStrikePersistence(Duration history) {
        if (!history.isPositive()) {
            throw new IllegalArgumentException("history must be positive");
        }
        this.history = history;
    }

    @Override
    public void recordStrike(UUID playerId, String playerName, FlagSeverity severity, List<String> matchedWords, String transcript, Instant now) {
        append(strikes, playerId, now);
    }

    @Override
    public int countRecentStrikes(UUID playerId, Instant since) {
        return count(strikes, playerId, since);
    }

    @Override
    public int countRecentMutes(UUID playerId, Instant since) {
        return count(mutes, playerId, since);
    }

    @Override
    public void pardonStrikes(UUID playerId) {
        strikes.remove(playerId);
    }

    public void recordMute(UUID playerId, Instant now) {
        append(mutes, playerId, now);
    }

    private void append(Map<UUID, Deque<Instant>> store, UUID playerId, Instant now) {
        Instant cutoff = now.minus(history);
        store.compute(playerId, (id, existing) -> {
            Deque<Instant> times = existing != null ? existing : new ArrayDeque<>();
            synchronized (times) {
                times.addLast(now);
                while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
                    times.pollFirst();
                }
            }
            return times;
        });
    }

    private static int count(Map<UUID, Deque<Instant>> store, UUID playerId, Instant since) {
        Deque<Instant> times = store.get(playerId);
        if (times == null) {
            return 0;
        }
        synchronized (times) {
            int count = 0;
            for (Instant time : times) {
                if (!time.isBefore(since)) {
                    count++;
                }
            }
            return count;
        }
    }
}
