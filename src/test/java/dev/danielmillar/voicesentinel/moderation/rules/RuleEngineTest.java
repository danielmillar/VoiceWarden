package dev.danielmillar.voicesentinel.moderation.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {
    private static RuleDefinition rule(String id, RuleAction action, int weight, List<String> words,
                                       List<String> phrases, List<String> regex, List<String> allow) {
        return new RuleDefinition(id, "Test " + id, action, weight, true, words, phrases, regex, allow);
    }

    private static RuleDefinition words(String id, RuleAction action, int weight, String... terms) {
        return rule(id, action, weight, List.of(terms), List.of(), List.of(), List.of());
    }

    private static RuleEngine engine(RuleDefinition... rules) {
        return RuleEngine.compile(List.of(rules), List.of());
    }

    private static void assertSpans(String transcript, List<RuleMatch> matches) {
        int previousEnd = 0;
        for (RuleMatch match : matches) {
            assertTrue(match.start() >= previousEnd);
            assertTrue(match.end() > match.start());
            assertEquals(transcript.substring(match.start(), match.end()), match.matchedText());
            previousEnd = match.end();
        }
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        RuleEngine engine = engine(words("r", RuleAction.FLAG, 1, "shit"));
        assertEquals(List.of(), engine.match(null));
        assertEquals(List.of(), RuleEngine.empty().match("shit"));
        assertEquals(0, RuleEngine.empty().ruleCount());
        assertEquals(0, RuleEngine.empty().termCount());
        assertTrue(RuleEngine.empty().warnings().isEmpty());
        assertTrue(RuleEngine.compile(null, null).match("anything").isEmpty());
        assertFalse(RuleEngine.compile(null, null).warnings().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "\u2003", "--- !!! 👋", "\u0301\u0308"})
    void blankAndNonWordInputsHaveNoHits(String input) {
        assertTrue(engine(words("r", RuleAction.FLAG, 1, "shit")).match(input).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"fuck,fuck", "ＦＵＣＫ,fuck", "FÜCK,fuck", "fück,fuck", "𝓯𝓾𝓬𝓴,fuck",
            "oﬃce,office", "SHİT,shit", "①,1", "🄵🅄🄲🄺,fuck", "don't,dont",
            "don’t,dont", "don＇t,dont"})
    void normalizationKeepsOriginalOffsets(String original, String pattern) {
        String transcript = "👋 Héy: " + original + "!";
        List<RuleMatch> matches = engine(words("r", RuleAction.FLAG, 1, pattern)).match(transcript);
        assertEquals(1, matches.size());
        RuleMatch match = matches.getFirst();
        assertEquals(original, match.matchedText());
        assertEquals(transcript.indexOf(original), match.start());
        assertEquals(match.start() + original.length(), match.end());
        assertSpans(transcript, matches);
    }

    @Test
    void normalizationIsIndependentOfDefaultLocaleAndAppliedToPatterns() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            RuleEngine engine = engine(words("r", RuleAction.FLAG, 1, "İDİOT", "ＦÜＣＫ*", "DON’T"));
            assertEquals(List.of("idiot", "fucking", "don't"),
                    engine.match("idiot fucking don't").stream().map(RuleMatch::matchedText).toList());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void tokensKeepCombiningMarksInTheirOriginalSpanAndSeparateOtherPunctuation() {
        String input = "\u0301'Fu\u0308ck' / shit_off;shit1 1shit 💬";
        List<RuleMatch> matches = engine(words("r", RuleAction.FLAG, 1, "fuck", "shit", "off")).match(input);
        assertEquals(List.of("Fu\u0308ck", "shit", "off"), matches.stream().map(RuleMatch::matchedText).toList());
        assertSpans(input, matches);
    }

    @Test
    void normalizationRemovesMarksBeforeApplyingTheInternalApostropheRule() {
        String input = "don'\u0301t";
        assertEquals(input, engine(words("r", RuleAction.FLAG, 1, "dont")).match(input).getFirst().matchedText());
        assertEquals("don't", engine(words("r", RuleAction.FLAG, 1, input)).match("don't").getFirst().matchedText());
    }

    @Test
    void fullwidthWildcardPatternsAreNormalizedBeforeCompilation() {
        RuleEngine engine = engine(words("r", RuleAction.FLAG, 1, "ＦＵＣＫ＊", "＊ＡＳＳ", "＊ＳＨＩＴ＊"));
        assertEquals(3, engine.match("fucking dumbass bullshit").size());
        assertEquals(3, engine.termCount());
        assertTrue(engine.warnings().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"fuck,fuck,true", "fuck,fucking,false", "fuck*,fucking,true", "fuck*,unfuck,false",
            "*fuck,unfuck,true", "*fuck,fucker,false", "*fuck*,unfucking,true", "*fuck*,duck,false"})
    void exactPrefixSuffixAndSubstringPatterns(String pattern, String candidate, boolean expected) {
        assertEquals(expected, !engine(words("r", RuleAction.FLAG, 1, pattern)).match(candidate).isEmpty());
    }

    @Test
    void allOverlappingTrieTerminalsAreEvaluated() {
        RuleEngine engine = engine(words("low", RuleAction.FLAG, 1, "f*", "*k"),
                words("high", RuleAction.MUTE, 2, "fuck*", "*fuck"));
        assertEquals("high", engine.match("fuck").getFirst().ruleId());
    }

    @Test
    void phrasesIgnorePunctuationAndUseWildcardWordPatterns() {
        RuleEngine engine = engine(rule("r", RuleAction.MUTE, 1, List.of("kill yourself"),
                List.of("kill your self", "neck *self", "*ill your*", "dont do that"), List.of(), List.of()));
        for (String input : List.of("Kill, YOURSELF!", "kill...your/self", "neck himself", "chill yourself", "Don't do that")) {
            List<RuleMatch> matches = engine.match(input);
            assertEquals(1, matches.size(), input);
            assertEquals(input.replaceFirst("[!]$", ""), matches.getFirst().matchedText());
            assertSpans(input, matches);
        }
        assertTrue(engine.match("kill a player yourself").isEmpty());
        assertTrue(engine.match("neck someone else").isEmpty());
    }

    @Test
    void phraseCanStartWithAnyWildcardMode() {
        RuleEngine engine = engine(rule("r", RuleAction.FLAG, 1, List.of(),
                List.of("kill* your*", "*neck *self", "*foo* bar"), List.of(), List.of()));
        assertEquals(3, engine.match("killing yourself and redneck himself and afoofoo bar").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mother-fucker", "MOTHER-FUCKER", "mother-füçker"})
    void joinsExactlyOneHyphen(String input) {
        List<RuleMatch> matches = engine(words("r", RuleAction.FLAG, 1, "motherfucker")).match("hey " + input + "!");
        assertEquals(1, matches.size());
        assertEquals(input, matches.getFirst().matchedText());
        assertSpans("hey " + input + "!", matches);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mother--fucker", "mother - fucker", "mother–fucker", "mother.fucker", "mother-fuck-er"})
    void doesNotJoinOtherSeparatorsOrThreeHyphenatedTokens(String input) {
        assertTrue(engine(words("r", RuleAction.FLAG, 1, "motherfucker")).match(input).isEmpty());
    }

    @Test
    void joinedCandidatesSupportAllWordPatternModes() {
        RuleEngine engine = engine(words("r", RuleAction.FLAG, 1, "motherfuck*", "*kys", "*fuck*"));
        String transcript = "mother-fucker says K Y S then F U C K";
        List<RuleMatch> matches = engine.match(transcript);
        assertEquals(List.of("mother-fucker", "K Y S", "F U C K"), matches.stream().map(RuleMatch::matchedText).toList());
        assertSpans(transcript, matches);
    }

    @Test
    void spelledRunsIncludeSubRunsBesidePronounsAndIgnorePunctuation() {
        RuleEngine engine = engine(words("r", RuleAction.FLAG, 1, "kys", "fuck"));
        String input = "I K Y S U and F, U, C, K and 1 2 3";
        List<RuleMatch> matches = engine.match(input);
        assertEquals(List.of("K Y S", "F, U, C, K"), matches.stream().map(RuleMatch::matchedText).toList());
        assertSpans(input, matches);
        assertTrue(engine(words("digits", RuleAction.FLAG, 1, "123")).match("1 2 3").isEmpty());
    }

    @Test
    void globalAllowlistIsExactNormalizedAndProtectsAllPatternModes() {
        RuleEngine engine = RuleEngine.compile(List.of(words("r", RuleAction.FLAG, 1,
                "ass*", "*ass", "*ass*", "cock*", "hell*", "class")),
                List.of("assassin", "CLÁSS", "grass", "scunthorpe", "cocktail", "hello"));
        assertTrue(engine.match("the assassin went to class on the grass; hello, have a cocktail in Scunthorpe").isEmpty());
        assertEquals(3, engine.match("assassins cocktailing hellfire").size());
    }

    @Test
    void ruleAllowlistOnlyProtectsItsOwnRuleAndAlsoAppliesToPhrasesAndJoinedWords() {
        RuleEngine engine = engine(rule("protected", RuleAction.MUTE, 9, List.of("ass*", "motherfucker", "kys"),
                List.of("neck *self"), List.of(), List.of("assassin", "himself", "motherfucker", "kys")),
                words("unprotected", RuleAction.FLAG, 1, "ass*"));
        assertEquals("unprotected", engine.match("assassin").getFirst().ruleId());
        assertTrue(engine.match("neck himself; mother-fucker; K Y S").isEmpty());
        assertEquals(1, engine.match("neck yourself").size());
    }

    @Test
    void allowlistedConstituentTokensProtectJoinedCandidates() {
        RuleEngine global = RuleEngine.compile(List.of(words("r", RuleAction.FLAG, 1, "motherfucker", "kys")),
                List.of("mother", "y"));
        assertTrue(global.match("mother-fucker K Y S").isEmpty());
        RuleEngine local = engine(rule("r", RuleAction.FLAG, 1, List.of("motherfucker", "kys"),
                List.of(), List.of(), List.of("mother", "y")));
        assertTrue(local.match("mother-fucker K Y S").isEmpty());
    }

    @Test
    void allowlistsDoNotSuppressRegex() {
        RuleEngine engine = RuleEngine.compile(List.of(rule("r", RuleAction.FLAG, 1, List.of("hello"),
                List.of(), List.of("hello"), List.of("hello"))), List.of("hello"));
        assertEquals(1, engine.match("HELLO").size());
    }

    @Test
    void muteWinsBeforeWeightAndSpanLength() {
        RuleEngine engine = engine(rule("flag", RuleAction.FLAG, 100, List.of(), List.of("kill yourself"), List.of(), List.of()),
                words("mute", RuleAction.MUTE, 1, "yourself"));
        assertEquals("mute", engine.match("kill yourself").getFirst().ruleId());
    }

    @Test
    void weightWinsBeforeSpanLength() {
        RuleEngine engine = engine(rule("long", RuleAction.MUTE, 1, List.of(), List.of("kill yourself"), List.of(), List.of()),
                words("heavy", RuleAction.MUTE, 3, "yourself"));
        assertEquals("heavy", engine.match("kill yourself").getFirst().ruleId());
    }

    @Test
    void longerSpanWinsThenEarlierStartThenStableRuleId() {
        RuleEngine engine = engine(words("short", RuleAction.FLAG, 1, "kill"),
                rule("long", RuleAction.FLAG, 1, List.of(), List.of("kill yourself"), List.of(), List.of()));
        assertEquals("long", engine.match("kill yourself").getFirst().ruleId());
        RuleEngine earlier = engine(rule("late", RuleAction.FLAG, 1, List.of(), List.of("bb cc"), List.of(), List.of()),
                rule("early", RuleAction.FLAG, 1, List.of(), List.of("aa bb"), List.of(), List.of()));
        assertEquals("early", earlier.match("aa bb cc").getFirst().ruleId());
        RuleEngine stable = engine(words("z", RuleAction.FLAG, 1, "shit"), words("a", RuleAction.FLAG, 1, "shit"));
        assertEquals("a", stable.match("shit").getFirst().ruleId());
    }

    @Test
    void greedyOverlapResolutionKeepsDisjointWinnersInAChain() {
        RuleEngine engine = engine(rule("outer", RuleAction.MUTE, 2, List.of(), List.of("aa bb", "cc dd"), List.of(), List.of()),
                rule("middle", RuleAction.FLAG, 1, List.of(), List.of("bb cc"), List.of(), List.of()));
        assertEquals(List.of("aa bb", "cc dd"), engine.match("aa bb cc dd").stream().map(RuleMatch::matchedText).toList());
    }

    @Test
    void sameRuleSameSpanIsDeduplicatedButRepeatedDisjointWordsRemain() {
        RuleEngine engine = engine(rule("r", RuleAction.FLAG, 1, List.of("shit", "SHIT", "shit*", "*shit", "*shit*"),
                List.of("shit"), List.of("shit"), List.of()));
        List<RuleMatch> hits = engine.match("shit shit");
        assertEquals(2, hits.size());
        assertEquals(List.of(0, 5), hits.stream().map(RuleMatch::start).toList());
        assertEquals(5, engine.termCount());
    }

    @Test
    void adjacentRegexMatchesDoNotOverlapAndResultsAreSorted() {
        RuleEngine engine = engine(rule("r", RuleAction.FLAG, 1, List.of(), List.of(), List.of("bar", "foo"), List.of()));
        List<RuleMatch> matches = engine.match("foobar");
        assertEquals(List.of("foo", "bar"), matches.stream().map(RuleMatch::matchedText).toList());
        assertSpans("foobar", matches);
    }

    @Test
    void regexUsesOriginalTextUnicodeFlagsAndOffsets() {
        RuleEngine engine = engine(rule("r", RuleAction.FLAG, 1, List.of(), List.of(),
                List.of("\\bσκατα\\b", "you're", "\\b\\d{3}\\b", "fuck"), List.of()));
        String input = "👋 ΣΚΑΤΑ you're １２３ fück";
        List<RuleMatch> matches = engine.match(input);
        assertEquals(List.of("ΣΚΑΤΑ", "you're", "１２３"), matches.stream().map(RuleMatch::matchedText).toList());
        assertSpans(input, matches);
    }

    @Test
    void zeroLengthRegexHitsAreIgnored() {
        assertTrue(engine(rule("r", RuleAction.FLAG, 1, List.of(), List.of(), List.of("^", "\\b", "$"), List.of()))
                .match("anything").isEmpty());
    }

    @Test
    @Timeout(3)
    void catastrophicRegexIsBoundedDiscardsEarlierHitsAndDoesNotDisableLaterCalls() {
        RuleEngine engine = engine(rule("bad", RuleAction.MUTE, 5, List.of(), List.of(),
                List.of("hit|(?:a+)+$"), List.of()), words("safe", RuleAction.FLAG, 1, "safe"));
        String input = "hit " + "a".repeat(20_000) + "! safe";
        long start = System.nanoTime();
        List<RuleMatch> matches = assertDoesNotThrow(() -> engine.match(input));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        assertEquals(List.of("safe"), matches.stream().map(RuleMatch::ruleId).toList());
        assertEquals("bad", engine.match("hit").getFirst().ruleId());
        assertTrue(engine.warnings().isEmpty());
    }

    @Test
    @Timeout(3)
    void deeplyRecursiveRegexCannotCrashAWorker() {
        RuleEngine engine = engine(rule("bad", RuleAction.FLAG, 1, List.of(), List.of(),
                List.of("^(a|aa)+$"), List.of()));
        assertTrue(assertDoesNotThrow(() -> engine.match("a".repeat(30_000) + "!")).isEmpty());
    }

    @Test
    void invalidAndEmptyTermsAreSkippedWithWarningsWhileValidTermsCompile() {
        RuleDefinition bad = rule("bad", RuleAction.FLAG, 1, Arrays.asList("", "*", "f*ck", null),
                List.of("..."), List.of("[", "(?", "good"), List.of("two words", "*", "valid"));
        RuleDefinition empty = words("empty", RuleAction.FLAG, 1);
        RuleEngine engine = RuleEngine.compile(Arrays.asList(null, bad, empty,
                words("good", RuleAction.FLAG, 1, "shit")), Arrays.asList("", null, "foo bar", "hello"));
        assertEquals(2, engine.ruleCount());
        assertEquals(2, engine.termCount());
        assertTrue(engine.warnings().stream().anyMatch(warning -> warning.contains("invalid regex")));
        assertTrue(engine.warnings().stream().anyMatch(warning -> warning.contains("no valid terms")));
        assertEquals(2, engine.match("good shit").size());
    }

    @Test
    void disabledRulesAndDuplicateIdsDoNotInflateCounts() {
        RuleDefinition disabled = new RuleDefinition("disabled", "Disabled", RuleAction.MUTE, 4, false,
                List.of("shit"), List.of(), List.of("["), List.of());
        RuleEngine engine = engine(disabled, words("r", RuleAction.FLAG, 1, "SHIT", "shit"),
                words("r", RuleAction.MUTE, 10, "fuck"));
        assertEquals(1, engine.ruleCount());
        assertEquals(1, engine.termCount());
        assertEquals(1, engine.warnings().size());
        assertEquals(RuleAction.FLAG, engine.match("shit").getFirst().action());
        assertTrue(engine.match("fuck").isEmpty());
    }

    @Test
    void missingMetadataIsSafelyDefaultedAndMissingIdIsSkipped() {
        RuleEngine engine = engine(new RuleDefinition("sexual-harassment", null, null, 0, true,
                List.of("bad"), null, null, null), words(" ", RuleAction.FLAG, 1, "wrong"));
        RuleMatch hit = engine.match("bad").getFirst();
        assertEquals("Sexual Harassment", hit.category());
        assertEquals(RuleAction.FLAG, hit.action());
        assertEquals(1, hit.weight());
        assertEquals(3, engine.warnings().size());
    }

    @Test
    void definitionsEngineAndReturnedResultsAreImmutableSnapshots() {
        List<String> words = new ArrayList<>(List.of("shit"));
        List<String> phrases = new ArrayList<>(List.of("kill yourself"));
        List<String> regex = new ArrayList<>(List.of("hello"));
        List<String> allow = new ArrayList<>(List.of("class"));
        RuleDefinition rule = rule("r", RuleAction.FLAG, 1, words, phrases, regex, allow);
        words.clear(); phrases.clear(); regex.clear(); allow.clear();
        assertEquals(List.of("shit"), rule.words());
        assertEquals(List.of("kill yourself"), rule.phrases());
        assertEquals(List.of("hello"), rule.regex());
        assertEquals(List.of("class"), rule.allow());
        for (List<String> list : List.of(rule.words(), rule.phrases(), rule.regex(), rule.allow())) {
            assertThrows(UnsupportedOperationException.class, () -> list.add("bad"));
        }
        List<RuleDefinition> rules = new ArrayList<>(List.of(rule));
        List<String> global = new ArrayList<>(List.of("shit"));
        RuleEngine engine = RuleEngine.compile(rules, global);
        rules.clear(); global.clear();
        assertTrue(engine.match("shit").isEmpty());
        assertEquals(2, engine.match("hello kill yourself").size());
        assertThrows(UnsupportedOperationException.class, () -> engine.warnings().add("bad"));
        assertThrows(UnsupportedOperationException.class, () -> engine.match("hello").clear());
    }

    @Test
    @Timeout(10)
    void concurrentWorkersReturnIdenticalResults() throws Exception {
        RuleEngine engine = RuleEngine.compile(List.of(words("words", RuleAction.FLAG, 1, "fuck*", "*ass", "*shit*"),
                rule("phrases", RuleAction.MUTE, 2, List.of("kys"), List.of("kill yourself", "*neck *self"),
                        List.of("\\bexample\\.com\\b"), List.of("class"))), List.of("grass"));
        List<String> transcripts = List.of("You're such a fucking idiot, kill yourself.", "K Y S and shit shit",
                "Grass and class, then example.com", "mother-fucker redneck himself", "This is perfectly fine.");
        List<List<RuleMatch>> expected = transcripts.stream().map(engine::match).toList();
        try (var pool = Executors.newFixedThreadPool(12)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 24; worker++) {
                tasks.add(() -> {
                    for (int pass = 0; pass < 100; pass++) {
                        for (int i = 0; i < transcripts.size(); i++) {
                            assertEquals(expected.get(i), engine.match(transcripts.get(i)));
                        }
                    }
                    return null;
                });
            }
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
        }
    }

    @Test
    void arbitraryUnicodeAndMalformedSurrogatesNeverThrowAndAlwaysPreserveSpans() {
        RuleEngine engine = engine(words("r", RuleAction.FLAG, 1, "a*", "*b", "*c*"),
                rule("regex", RuleAction.FLAG, 1, List.of(), List.of("a b"), List.of("\\p{N}+"), List.of()));
        Random random = new Random(738291);
        for (int pass = 0; pass < 2_000; pass++) {
            StringBuilder input = new StringBuilder("a ");
            for (int i = 0; i < 30; i++) {
                input.append((char) random.nextInt(65_536));
            }
            input.append(" b c123");
            String transcript = input.toString();
            assertSpans(transcript, assertDoesNotThrow(() -> engine.match(transcript)));
        }
    }
}
