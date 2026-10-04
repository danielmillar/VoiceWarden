package dev.danielmillar.nevusvoice.stt;

import dev.danielmillar.nevusvoice.audio.AudioSegment;
import dev.danielmillar.nevusvoice.health.Metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, per-player-fair queue between the decode pool and speech-to-text workers. Producers never block: when a
 * player exceeds their share, their oldest utterance is dropped; when the queue is full, the globally oldest is
 * dropped. Utterances that waited longer than the max age are discarded on dequeue.
 */
final class SegmentQueue {

    record Batch(List<AudioSegment> segments, long generation) { }

    private volatile long generation;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final ArrayDeque<AudioSegment> queue = new ArrayDeque<>();
    private final Map<UUID, Integer> perPlayer = new HashMap<>();
    /** Mirror of {@code queue.size()} for lock-free reads (e.g. /nv stats on the main thread). */
    private final AtomicInteger size = new AtomicInteger();

    /** @return how many queued utterances were dropped to make room */
    int offer(AudioSegment segment, int maxSize, int maxPerPlayer) {
        int dropped = 0;
        lock.lock();
        try {
            if (perPlayer.getOrDefault(segment.playerId(), 0) >= maxPerPlayer) {
                for (Iterator<AudioSegment> it = queue.iterator(); it.hasNext(); ) {
                    AudioSegment queued = it.next();
                    if (queued.playerId().equals(segment.playerId())) {
                        it.remove();
                        decrement(queued.playerId());
                        dropped++;
                        break;
                    }
                }
            }
            while (queue.size() >= maxSize) {
                decrement(queue.removeFirst().playerId());
                dropped++;
            }
            queue.addLast(segment);
            perPlayer.merge(segment.playerId(), 1, Integer::sum);
            size.set(queue.size());
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
        return dropped;
    }

    /**
     * Waits up to {@code timeoutMillis} for at least one utterance, then takes up to {@code max} fresh ones.
     *
     * @return possibly empty list (timeout or only stale items)
     */
    List<AudioSegment> takeBatch(int max, long timeoutMillis, long maxAgeNanos, Metrics metrics) throws InterruptedException {
        return takeBatchSnapshot(max, timeoutMillis, maxAgeNanos, metrics).segments();
    }

    Batch takeBatchSnapshot(int max, long timeoutMillis, long maxAgeNanos, Metrics metrics) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (queue.isEmpty()) {
                if (nanos <= 0) {
                    return new Batch(List.of(), generation);
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            long now = System.nanoTime();
            List<AudioSegment> batch = new ArrayList<>(Math.min(max, queue.size()));
            while (batch.size() < max && !queue.isEmpty()) {
                AudioSegment segment = queue.removeFirst();
                decrement(segment.playerId());
                if (now - segment.endedAtNanos() > maxAgeNanos) {
                    metrics.staleDropped.increment();
                } else {
                    batch.add(segment);
                }
            }
            size.set(queue.size());
            if (!queue.isEmpty()) {
                notEmpty.signal(); // let another idle worker take the rest
            }
            return new Batch(batch, generation);
        } finally {
            lock.unlock();
        }
    }

    int size() {
        return size.get();
    }

    long generation() {
        return generation;
    }

    int clear() {
        lock.lock();
        try {
            generation++;
            int dropped = queue.size();
            queue.clear();
            perPlayer.clear();
            size.set(0);
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    private void decrement(UUID player) {
        perPlayer.computeIfPresent(player, (k, v) -> v <= 1 ? null : v - 1);
    }
}
