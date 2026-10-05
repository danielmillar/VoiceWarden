package dev.danielmillar.voicewarden.moderation;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window count of mute-flags per player. Thread-safe. */
final class FlagTracker {

    private record Hit(long at, int weight) {
    }

    private final ConcurrentHashMap<UUID, ArrayDeque<Hit>> hits = new ConcurrentHashMap<>();

    /** Adds {@code weight} flags and returns the total inside the window, atomically per player. */
    int add(UUID player, int weight, long nowMillis, long windowMillis) {
        int[] total = new int[1];
        hits.compute(player, (k, deque) -> {
            ArrayDeque<Hit> d = deque == null ? new ArrayDeque<>() : deque;
            prune(d, nowMillis - windowMillis);
            d.addLast(new Hit(nowMillis, weight));
            for (Hit h : d) {
                total[0] += h.weight;
            }
            return d;
        });
        return total[0];
    }

    void reset(UUID player) {
        hits.remove(player);
    }

    void prune(long nowMillis, long windowMillis) {
        long cutoff = nowMillis - windowMillis;
        for (UUID player : hits.keySet()) {
            hits.computeIfPresent(player, (k, d) -> {
                prune(d, cutoff);
                return d.isEmpty() ? null : d;
            });
        }
    }

    private static void prune(ArrayDeque<Hit> d, long cutoff) {
        while (!d.isEmpty() && d.peekFirst().at < cutoff) {
            d.removeFirst();
        }
    }
}
