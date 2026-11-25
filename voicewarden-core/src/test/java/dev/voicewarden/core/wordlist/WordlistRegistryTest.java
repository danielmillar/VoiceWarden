package dev.voicewarden.core.wordlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WordlistRegistryTest {

    private static AhoCorasickMatcher compile(String list) {
        return AhoCorasickMatcher.compile(WordlistParser.parse(list), WordlistConfig.defaults());
    }

    @Test
    void startsEmptyAndSwapsAtomicallyOnReload() {
        WordlistRegistry registry = new WordlistRegistry();
        assertFalse(registry.match("damn").hasMatches());
        AhoCorasickMatcher first = registry.current();
        AhoCorasickMatcher next = compile("[EN-PROFANITY]\ndamn\n");
        assertSame(first, registry.reload(next));
        assertSame(next, registry.current());
        assertEquals(List.of("damn"), registry.match("damn").matchedWords());
    }

    @Test
    void concurrentReadersAlwaysSeeACompleteMatcherDuringReloads() throws Exception {
        AhoCorasickMatcher a = compile("[EN-PROFANITY]\nalpha\n");
        AhoCorasickMatcher b = compile("[EN-PROFANITY]\nbeta\n");
        WordlistRegistry registry = new WordlistRegistry(a);
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> readers = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                readers.add(pool.submit(() -> {
                    int reads = 0;
                    do {
                        int hits = registry.match("alpha beta").matches().size();
                        if (hits != 1) {
                            throw new AssertionError("Torn read: " + hits + " matches");
                        }
                        if (reads++ == 0) {
                            started.countDown();
                        }
                    } while (running.get());
                    return reads;
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 2_000; i++) {
                registry.reload(i % 2 == 0 ? b : a);
            }
            running.set(false);
            for (Future<Integer> reader : readers) {
                assertTrue(reader.get(5, TimeUnit.SECONDS) > 0);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
