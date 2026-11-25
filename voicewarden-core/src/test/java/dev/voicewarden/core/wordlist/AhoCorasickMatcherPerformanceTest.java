package dev.voicewarden.core.wordlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

class AhoCorasickMatcherPerformanceTest {

    private static final int LARGE_LIST = 6_000;
    private static final int SMALL_LIST = 60;
    private static final int TEXT_WORDS = 200;
    private static final int RUNS = 7;
    private static final int SCANS_PER_RUN = 2_000;
    private static final double MAX_MICROS_PER_SCAN = 1_000.0;
    private static final double MAX_SIZE_RATIO = 8.0;

    private static String randomWord(Random random) {
        int length = 4 + random.nextInt(6);
        StringBuilder word = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            word.append((char) ('a' + random.nextInt(26)));
        }
        return word.toString();
    }

    private static String wordlist(int words, long seed) {
        Random random = new Random(seed);
        StringBuilder list = new StringBuilder("[EN-PROFANITY]\n");
        for (int i = 0; i < words; i++) {
            list.append(randomWord(random));
            if (i % 10 == 0) {
                list.append(' ').append(randomWord(random));
            }
            list.append('\n');
        }
        return list.append("[EN-MUTE]\ntarget phrase\n[EN-ALLOW]\nnot a target phrase\n").toString();
    }

    private static String transcript() {
        Random random = new Random(7);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < TEXT_WORDS; i++) {
            text.append(randomWord(random)).append(i % 17 == 0 ? ",  " : " ");
        }
        return text.append("and then a target   phrase").toString();
    }

    private static double bestMicrosPerScan(AhoCorasickMatcher matcher, String text) {
        double best = Double.MAX_VALUE;
        int sink = 0;
        for (int run = 0; run < RUNS; run++) {
            long start = System.nanoTime();
            for (int i = 0; i < SCANS_PER_RUN; i++) {
                sink += matcher.match(text).matches().size();
            }
            best = Math.min(best, (System.nanoTime() - start) / 1_000.0 / SCANS_PER_RUN);
        }
        assertTrue(sink > 0);
        return best;
    }

    @Test
    void scanCostIsMicrosecondScaleAndIndependentOfListSize(TestReporter reporter) {
        WordlistConfig config = WordlistConfig.defaults();
        AhoCorasickMatcher large = AhoCorasickMatcher.compile(
                WordlistParser.parse(wordlist(LARGE_LIST, 1), config), config);
        AhoCorasickMatcher small = AhoCorasickMatcher.compile(
                WordlistParser.parse(wordlist(SMALL_LIST, 2), config), config);
        assertTrue(large.flaggedPatternCount() >= 5_000, "flagged patterns: " + large.flaggedPatternCount());

        String text = transcript();
        assertEquals("target phrase", large.match(text).matches().getLast().word());

        double smallMicros = bestMicrosPerScan(small, text);
        double largeMicros = bestMicrosPerScan(large, text);
        reporter.publishEntry("chars", Integer.toString(text.length()));
        reporter.publishEntry("smallListMicrosPerScan", String.format("%.2f", smallMicros));
        reporter.publishEntry("largeListMicrosPerScan", String.format("%.2f", largeMicros));
        reporter.publishEntry("largeListStates", Integer.toString(large.stateCount()));

        assertTrue(largeMicros < MAX_MICROS_PER_SCAN,
                "Scanning " + text.length() + " chars took " + largeMicros + "µs with " + LARGE_LIST + " words");
        assertTrue(largeMicros < Math.max(smallMicros, 1.0) * MAX_SIZE_RATIO,
                "A 100x larger list made scanning " + (largeMicros / smallMicros) + "x slower ("
                        + smallMicros + "µs vs " + largeMicros + "µs); the scan must not depend on list size");
    }
}
