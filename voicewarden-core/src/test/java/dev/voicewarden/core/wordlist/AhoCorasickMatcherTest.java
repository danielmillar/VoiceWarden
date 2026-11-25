package dev.voicewarden.core.wordlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.FlagSeverity;
import java.util.List;
import org.junit.jupiter.api.Test;

class AhoCorasickMatcherTest {

    private static final String LIST = """
            [EN-PROFANITY]
            ass
            damn
            shit
            [EN-MODERATE]
            piece of shit
            cunt
            [EN-MUTE]
            kill yourself
            [EN-ALLOW]
            class
            kick ass
            scunthorpe
            """;

    private static AhoCorasickMatcher matcher(String list, WordlistConfig config) {
        return AhoCorasickMatcher.compile(WordlistParser.parse(list, config), config);
    }

    private static AhoCorasickMatcher matcher(WordlistConfig config) {
        return matcher(LIST, config);
    }

    private static List<String> words(WordlistMatchResult result) {
        return result.matches().stream().map(WordMatch::word).toList();
    }

    private static void assertSpansPointAtText(String text, WordlistMatchResult result) {
        for (WordMatch match : result.matches()) {
            String slice = text.substring(match.startIndex(), match.endIndex());
            assertEquals(TextNormalizer.normalizeTerm(match.word(), false),
                    TextNormalizer.normalizeTerm(slice, false), "span " + match);
        }
    }

    @Test
    void wholeWordModeIgnoresEmbeddedWords() {
        AhoCorasickMatcher m = matcher(WordlistConfig.defaults());
        assertFalse(m.match("passing the classic assessment in Scunthorpe").hasMatches());
        WordlistMatchResult result = m.match("damn, what an ass!");
        assertEquals(List.of("damn", "ass"), words(result));
        assertEquals(FlagSeverity.MILD, result.highestSeverity());
        assertSpansPointAtText("damn, what an ass!", result);
    }

    @Test
    void partialModeFindsEmbeddedWordsAndAllowlistSuppressesScunthorpe() {
        AhoCorasickMatcher m = matcher(WordlistConfig.builder().partialMatch(true).build());
        assertEquals(List.of("ass"), words(m.match("badass")));
        assertFalse(m.match("first class").hasMatches());
        assertFalse(m.match("Scunthorpe United").hasMatches());
        assertEquals(List.of("cunt"), words(m.match("scunt")));
        WordlistMatchResult mixed = m.match("classy assassin");
        assertEquals(List.of("ass", "ass"), words(mixed));
        assertEquals(7, mixed.matches().get(0).startIndex());
        assertEquals(List.of("ass"), mixed.matchedWords());
    }

    @Test
    void allowlistPhraseSuppressesWholeWordMatchesInside() {
        AhoCorasickMatcher m = matcher(WordlistConfig.defaults());
        assertFalse(m.match("you kick ass").hasMatches());
        assertFalse(m.match("you KICK    ass!").hasMatches());
        assertEquals(List.of("ass"), words(m.match("kick that ass")));
    }

    @Test
    void allowMatchThatFailsItsOwnBoundaryDoesNotSuppress() {
        AhoCorasickMatcher m = matcher(WordlistConfig.defaults());
        WordlistMatchResult result = m.match("sidekick ass");
        assertEquals(List.of("ass"), words(result));
        assertEquals(9, result.matches().get(0).startIndex());
        assertEquals(List.of("ass"), words(m.match("subclass ass")));
    }

    @Test
    void allowlistOverridesEveryTierIncludingEqualSpans() {
        String list = """
                [EN-PROFANITY]
                heck
                [EN-MODERATE]
                darn
                [EN-MUTE]
                gosh
                [EN-ALLOW]
                heck
                darn
                gosh
                """;
        AhoCorasickMatcher m = matcher(list, WordlistConfig.defaults());
        assertFalse(m.match("heck darn gosh").hasMatches());
        assertEquals(0, m.flaggedPatternCount());
        assertEquals(3, m.allowPatternCount());
    }

    @Test
    void allowlistSuppressesOnlyContainedMatches() {
        AhoCorasickMatcher m = matcher("""
                [EN-MUTE]
                bad word
                [EN-ALLOW]
                not bad
                """, WordlistConfig.defaults());
        WordlistMatchResult result = m.match("not bad word");
        assertEquals(List.of("bad word"), words(result));
        assertEquals(FlagSeverity.SEVERE, result.highestSeverity());
    }

    @Test
    void multiWordPhrasesRespectBoundariesAndCollapsedWhitespace() {
        AhoCorasickMatcher m = matcher(WordlistConfig.defaults());
        String text = "  you\tpiece   of  shit ";
        WordlistMatchResult result = m.match(text);
        assertEquals(List.of("piece of shit", "shit"), words(result));
        WordMatch phrase = result.matches().get(0);
        assertEquals(6, phrase.startIndex());
        assertEquals(text.indexOf("shit") + 4, phrase.endIndex());
        assertEquals(FlagSeverity.MODERATE, result.highestSeverity());
        assertSpansPointAtText(text, result);

        assertEquals(List.of("shit"), words(m.match("masterpiece of shit")));
        assertFalse(m.match("piece of shitake").hasMatches());
        assertFalse(m.match("skill yourselves").hasMatches());
    }

    @Test
    void shorterPatternIsReportedWhenLongerOneFailsItsBoundary() {
        AhoCorasickMatcher m = matcher("""
                [EN-PROFANITY]
                shit
                [EN-MODERATE]
                bullshit
                """, WordlistConfig.defaults());
        WordlistMatchResult result = m.match("xbullshit and shit");
        assertEquals(List.of("shit"), words(result));
        assertEquals(14, result.matches().get(0).startIndex());
        AhoCorasickMatcher partial = matcher("""
                [EN-PROFANITY]
                shit
                [EN-MODERATE]
                bullshit
                """, WordlistConfig.builder().partialMatch(true).build());
        WordlistMatchResult overlapping = partial.match("bullshit");
        assertEquals(List.of("bullshit", "shit"), words(overlapping));
        assertEquals(FlagSeverity.MODERATE, overlapping.highestSeverity());
        assertEquals(List.of("bullshit", "shit"), overlapping.matchedWords());
    }

    @Test
    void caseInsensitiveByDefaultAndCaseSensitiveWhenConfigured() {
        AhoCorasickMatcher insensitive = matcher(WordlistConfig.defaults());
        assertEquals(List.of("damn"), words(insensitive.match("DaMn")));
        AhoCorasickMatcher sensitive = matcher("""
                [EN-PROFANITY]
                Damn
                shit
                """, WordlistConfig.builder().caseSensitive(true).build());
        assertFalse(sensitive.match("damn SHIT").hasMatches());
        assertEquals(List.of("Damn", "shit"), words(sensitive.match("Damn shit")));
        assertTrue(sensitive.caseSensitive());
    }

    @Test
    void caseFoldingKeepsIndicesAlignedWithOriginalText() {
        AhoCorasickMatcher m = matcher(WordlistConfig.defaults());
        String text = "İİ ﬃ ẞ DAMN";
        WordlistMatchResult result = m.match(text);
        assertEquals(List.of("damn"), words(result));
        assertEquals(text.indexOf("DAMN"), result.matches().get(0).startIndex());
        assertEquals(text.length(), result.matches().get(0).endIndex());
        assertSpansPointAtText(text, result);
    }

    @Test
    void punctuationAndDigitsActAsExpectedBoundaries() {
        AhoCorasickMatcher m = matcher(WordlistConfig.defaults());
        assertEquals(List.of("damn", "shit", "ass"), words(m.match("\"damn\"...shit-ass?")));
        assertFalse(m.match("damn2").hasMatches());
        assertFalse(m.match("3shit").hasMatches());
    }

    @Test
    void supportsCyrillicArabicAndJapanese() {
        String list = """
                [RU-PROFANITY]
                бля
                [RU-ALLOW]
                корабля
                [AR-MUTE]
                اقتل نفسك
                [AR-PROFANITY]
                خرا
                [AR-ALLOW]
                خراب
                [JA-MUTE]
                死ね
                [JA-PROFANITY]
                バカ
                [JA-ALLOW]
                バカンス
                """;
        AhoCorasickMatcher m = matcher(list, WordlistConfig.defaults());

        WordlistMatchResult russian = m.match("Ну БЛЯ, опять");
        assertEquals(List.of("бля"), words(russian));
        assertEquals("ru", russian.matches().get(0).language());
        assertFalse(m.match("с корабля на бал").hasMatches());

        String arabic = "لا تقل اقتل  نفسك";
        WordlistMatchResult arResult = m.match(arabic);
        assertEquals(List.of("اقتل نفسك"), words(arResult));
        assertEquals(FlagSeverity.SEVERE, arResult.highestSeverity());
        assertSpansPointAtText(arabic, arResult);
        assertFalse(m.match("خراب").hasMatches());

        WordlistMatchResult japanese = m.match("お前はバカだ、死ね");
        assertEquals(List.of("バカ", "死ね"), words(japanese));
        assertEquals(3, japanese.matches().get(0).startIndex());
        assertEquals(FlagSeverity.SEVERE, japanese.highestSeverity());
        assertFalse(m.match("夏のバカンスに行く").hasMatches());
    }

    @Test
    void unspacedScriptExemptionDoesNotLeakIntoSpacedScripts() {
        AhoCorasickMatcher m = matcher("""
                [EN-PROFANITY]
                ass
                [RU-PROFANITY]
                бля
                [AR-PROFANITY]
                خرا
                """, WordlistConfig.defaults());
        assertFalse(m.match("glass").hasMatches());
        assertFalse(m.match("рубля").hasMatches());
        assertFalse(m.match("خرافة").hasMatches());
        assertEquals(List.of("ass"), words(m.match("ass日本")));
    }

    @Test
    void surrogatePairsAreBoundariesWhenTheyAreNotLetters() {
        AhoCorasickMatcher m = matcher(WordlistConfig.defaults());
        String text = "😀damn😀";
        WordlistMatchResult result = m.match(text);
        assertEquals(List.of("damn"), words(result));
        assertEquals(2, result.matches().get(0).startIndex());
        assertFalse(m.match("𝐀damn").hasMatches());
    }

    @Test
    void highestSeverityWinsWhenTheSameTermIsInSeveralTiers() {
        AhoCorasickMatcher m = matcher("""
                [EN-PROFANITY]
                troll
                [DE-MUTE]
                troll
                """, WordlistConfig.defaults());
        WordlistMatchResult result = m.match("troll");
        assertEquals(1, result.matches().size());
        assertEquals(FlagSeverity.SEVERE, result.highestSeverity());
        assertEquals("de", result.matches().get(0).language());
    }

    @Test
    void configOverridesAreMatchedAndAllowWordsOverrideFileEntries() {
        WordlistConfig config = WordlistConfig.builder()
                .extraProfanityWords(List.of("noob"))
                .extraMuteWords(List.of("dox you"))
                .extraAllowWords(List.of("damn it"))
                .build();
        AhoCorasickMatcher m = matcher(config);
        WordlistMatchResult result = m.match("noob, i will dox you. damn it. damn");
        assertEquals(List.of("noob", "dox you", "damn"), words(result));
        assertEquals(WordlistParser.CONFIG_LANGUAGE, result.matches().get(1).language());
        assertEquals(FlagSeverity.SEVERE, result.highestSeverity());
        assertEquals(List.of("noob", "dox you", "damn"), result.matchedWords());
    }

    @Test
    void languageFilterLimitsWhichListsMatch() {
        String list = """
                [EN-PROFANITY]
                damn
                [ES-PROFANITY]
                mierda
                """;
        WordlistConfig english = WordlistConfig.builder().enabledLanguages("en").build();
        AhoCorasickMatcher m = matcher(list, english);
        assertEquals(List.of("damn"), words(m.match("damn mierda")));
    }

    @Test
    void degenerateInputsReturnTheEmptyResult() {
        AhoCorasickMatcher m = matcher(WordlistConfig.defaults());
        assertSame(WordlistMatchResult.EMPTY, m.match(""));
        assertSame(WordlistMatchResult.EMPTY, m.match(null));
        assertSame(WordlistMatchResult.EMPTY, m.match("     \t\n "));
        assertSame(WordlistMatchResult.EMPTY, m.match("!!!???..."));
        WordlistMatchResult none = AhoCorasickMatcher.empty().match("damn");
        assertFalse(none.hasMatches());
        assertNull(none.highestSeverity());
        assertTrue(none.matchedWords().isEmpty());
        AhoCorasickMatcher onlyAllow = matcher("[EN-ALLOW]\nclass\n", WordlistConfig.defaults());
        assertSame(WordlistMatchResult.EMPTY, onlyAllow.match("class"));
    }

    @Test
    void repeatedAndAdjacentMatchesAreAllReported() {
        AhoCorasickMatcher m = matcher(WordlistConfig.defaults());
        WordlistMatchResult result = m.match("damn damn  damn");
        assertEquals(3, result.matches().size());
        assertEquals(List.of("damn"), result.matchedWords());
        assertEquals(List.of(0, 5, 11), result.matches().stream().map(WordMatch::startIndex).toList());
    }
}
