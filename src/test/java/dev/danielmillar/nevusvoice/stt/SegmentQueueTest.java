package dev.danielmillar.nevusvoice.stt;

import dev.danielmillar.nevusvoice.audio.AudioSegment;
import dev.danielmillar.nevusvoice.health.Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SegmentQueueTest {
    private final SegmentQueue queue = new SegmentQueue();
    private final Metrics metrics = new Metrics();
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);

    @Test void playerCapDropsThatPlayersOldestRatherThanAnotherPlayersItem() throws Exception {
        assertEquals(0, queue.offer(segment(bob, "b1", false), 10, 2));
        queue.offer(segment(alice, "a1", false), 10, 2);
        queue.offer(segment(alice, "a2", false), 10, 2);
        assertEquals(1, queue.offer(segment(alice, "a3", false), 10, 2));
        assertEquals(List.of("b1", "a2", "a3"), names(take(10)));
    }

    @Test void globalCapDropsGloballyOldest() throws Exception {
        queue.offer(segment(alice, "a1", false), 2, 10);
        queue.offer(segment(bob, "b1", false), 2, 10);
        assertEquals(1, queue.offer(segment(alice, "a2", false), 2, 10));
        assertEquals(List.of("b1", "a2"), names(take(10)));
    }

    @Test void loweredGlobalCapDropsEnoughItemsToFit() throws Exception {
        for (int i = 0; i < 5; i++) queue.offer(segment(alice, "a" + i, false), 10, 10);
        assertEquals(4, queue.offer(segment(bob, "b", false), 2, 10));
        assertEquals(List.of("a4", "b"), names(take(10)));
    }

    @Test void staleItemsAreSkippedCountedAndDoNotConsumeBatchCapacity() throws Exception {
        queue.offer(segment(alice, "old1", true), 10, 10);
        queue.offer(segment(bob, "fresh", false), 10, 10);
        queue.offer(segment(alice, "old2", true), 10, 10);
        assertEquals(List.of("fresh"), names(take(2)));
        assertEquals(2, metrics.staleDropped.sum());
        assertEquals(0, queue.size());
        assertEquals(0, queue.offer(segment(alice, "next", false), 10, 1));
    }

    @Test void batchHonorsMaximumAndKeepsRemainder() throws Exception {
        for (int i = 0; i < 5; i++) queue.offer(segment(alice, "a" + i, false), 10, 10);
        assertEquals(List.of("a0", "a1"), names(take(2)));
        assertEquals(3, queue.size());
        assertEquals(List.of("a2", "a3", "a4"), names(take(10)));
        assertTrue(take(1).isEmpty());
    }

    @Test void waitingConsumerIsSignaledByOffer() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        try {
            Future<List<AudioSegment>> future = executor.submit(() -> {
                entered.countDown();
                return queue.takeBatch(1, 2_000, Duration.ofHours(1).toNanos(), metrics);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            AudioSegment offered = segment(alice, "hello", false);
            queue.offer(offered, 10, 10);
            assertEquals(List.of(offered), future.get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test void concurrentProducersAndConsumersPreserveAllAccounting() throws Exception {
        int producers = 4;
        int each = 2_000;
        AtomicLong dropped = new AtomicLong();
        AtomicLong taken = new AtomicLong();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(producers);
        var executor = Executors.newFixedThreadPool(producers + 2);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int producer = 0; producer < producers; producer++) {
                int p = producer;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < each; i++) {
                            dropped.addAndGet(queue.offer(segment(new UUID(0, p + 1), p + ":" + i, i % 7 == 0), 64, 8));
                        }
                    } finally { finished.countDown(); }
                    return null;
                }));
            }
            for (int consumer = 0; consumer < 2; consumer++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    while (finished.getCount() > 0 || queue.size() > 0) {
                        for (AudioSegment segment : queue.takeBatch(5, 10, Duration.ofHours(1).toNanos(), metrics)) {
                            assertTrue(seen.add(segment.playerName()), "duplicate consumption");
                            taken.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(0, queue.size());
            assertEquals(producers * each, taken.get() + dropped.get() + metrics.staleDropped.sum());
            // No stale player counters should remain after the queue is drained.
            assertEquals(0, queue.offer(segment(alice, "after", false), 1, 1));
            assertEquals(List.of("after"), names(take(1)));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private List<AudioSegment> take(int max) throws InterruptedException {
        return queue.takeBatch(max, 0, Duration.ofHours(1).toNanos(), metrics);
    }
    private static List<String> names(List<AudioSegment> batch) { return batch.stream().map(AudioSegment::playerName).toList(); }
    private static AudioSegment segment(UUID id, String name, boolean stale) {
        return new AudioSegment(id, name, new short[320], 16_000, Instant.EPOCH,
                System.nanoTime() - (stale ? Duration.ofDays(1).toNanos() : 0), null, false);
    }
}
