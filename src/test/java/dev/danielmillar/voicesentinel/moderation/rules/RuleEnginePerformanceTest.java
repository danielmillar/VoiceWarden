package dev.danielmillar.voicesentinel.moderation.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuleEnginePerformanceTest {
    @Test
    void tenThousandVariedTranscriptsAgainstTwoThousandTermsTakeWellUnderTwoSeconds() {
        List<String> terms = new ArrayList<>(1990);
        for (int i = 0; i < 1200; i++) {
            terms.add("blocked" + i);
        }
        for (int i = 0; i < 390; i++) {
            terms.add("pre" + i + "*");
            terms.add("*suf" + i);
        }
        for (int i = 0; i < 10; i++) {
            terms.add("*mid" + i + "*");
        }
        List<String> phrases = new ArrayList<>(10);
        for (int i = 0; i < 5; i++) {
            phrases.add("phrase" + i + " target");
            phrases.add("wild" + i + "* target");
        }
        RuleEngine engine = RuleEngine.compile(List.of(new RuleDefinition("large", "Large test", RuleAction.FLAG, 1, true,
                terms, phrases, List.of(), List.of())), Set.of("grass", "class"));
        assertEquals(2000, engine.termCount());
        assertTrue(engine.warnings().isEmpty());

        List<String> inputs = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            String sentence = switch (i % 5) {
                case 0 -> "We walked through the grass and said BLOCKED" + i % 1200 + " before going back to class.";
                case 1 -> "Could you please stop saying pre" + i % 390 + "extra when we are playing together today?";
                case 2 -> "That is normal text followed by extrasuf" + i % 390 + " then we continue our conversation.";
                case 3 -> "Héllo, I saw amid" + i % 10 + "z and phrase" + i % 5 + " target while collecting diamonds.";
                default -> "We want to build a castle but wild" + i % 5 + "card target was mentioned near spawn.";
            };
            inputs.add(sentence);
        }
        // Warm the JIT separately; measure matching only, excluding compilation and fixture creation.
        for (int i = 0; i < 10_000; i++) {
            engine.match(inputs.get(i));
        }
        int totalMatches = 0;
        long start = System.nanoTime();
        for (String input : inputs) {
            totalMatches += engine.match(input).size();
        }
        long elapsed = System.nanoTime() - start;
        assertEquals(12_000, totalMatches);
        System.out.printf("RuleEngine benchmark: 10,000 varied transcripts, 2,000 terms, %.1f ms%n", elapsed / 1_000_000.0);
        // 1.5 seconds leaves meaningful headroom below the requested two-second ceiling.
        assertTrue(elapsed < TimeUnit.MILLISECONDS.toNanos(1500),
                "10,000 matches took " + elapsed / 1_000_000 + " ms");
    }
}
