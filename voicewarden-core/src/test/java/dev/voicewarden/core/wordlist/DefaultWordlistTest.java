package dev.voicewarden.core.wordlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.FlagSeverity;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultWordlistTest {

    private static final ParsedWordlist DEFAULT = WordlistParser.parseDefault(WordlistConfig.defaults());

    @Test
    void parsesCleanlyAndCoversEveryEnglishTier() {
        assertTrue(DEFAULT.warnings().isEmpty(), DEFAULT.warnings().toString());
        assertEquals(Set.of("en"), DEFAULT.languages());
        for (WordlistTier tier : WordlistTier.values()) {
            assertFalse(DEFAULT.entries("en", tier).isEmpty(), "en " + tier);
        }
    }

    @Test
    void everyAllowEntryProtectsAFlaggedTerm() {
        List<String> flagged = DEFAULT.entries().stream()
                .filter(entry -> !entry.tier().isAllow())
                .map(entry -> TextNormalizer.normalizeTerm(entry.term(), false))
                .toList();
        for (WordlistEntry allow : DEFAULT.entries(WordlistTier.ALLOW)) {
            String term = TextNormalizer.normalizeTerm(allow.term(), false);
            assertTrue(flagged.stream().anyMatch(term::contains),
                    "[" + allow.language() + "-ALLOW] " + allow.term() + " does not contain any flagged term");
        }
    }

    @Test
    void englishAllowlistHasTheClassicScunthorpeWords() {
        List<String> allow = DEFAULT.entries("en", WordlistTier.ALLOW).stream().map(WordlistEntry::term).toList();
        assertTrue(allow.containsAll(List.of("assassin", "class", "classic", "pass", "grass",
                "basement", "therapist", "snigger", "kick ass", "dickens", "retardant", "cockpit")), allow.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "The assassin passed the classic class in Scunthorpe",
        "a therapist in the basement sniggered at the scrap grass",
        "Mississippi has spicy grapes and prickly glass compasses",
        "Charles Dickens wrote about a peacock near the cockpit",
        "flame retardant foam and a raccoon in a cocoon",
        "the sophomore analyzed the document about Pakistan and Montenegro",
        "grab a teaspoon of saltwater for the cucumber soup",
        "parse the manuscript on the torpedo pedometer",
        "we had a great game, gg everyone",
    })
    void cleanEnglishSentencesStayCleanEvenInPartialMode(String sentence) {
        WordlistConfig partial = WordlistConfig.builder().partialMatch(true).build();
        AhoCorasickMatcher m = AhoCorasickMatcher.compile(WordlistParser.parseDefault(partial), partial);
        assertFalse(m.match(sentence).hasMatches(), () -> m.match(sentence).toString());
        AhoCorasickMatcher whole = AhoCorasickMatcher.compile(DEFAULT, WordlistConfig.defaults());
        assertFalse(whole.match(sentence).hasMatches(), () -> whole.match(sentence).toString());
    }

    @Test
    void flagsRealEnglishAbuseAcrossAllSeverityTiers() {
        AhoCorasickMatcher m = AhoCorasickMatcher.compile(DEFAULT, WordlistConfig.defaults());
        assertEquals(FlagSeverity.MILD, m.match("oh shit").highestSeverity());
        assertEquals(FlagSeverity.MILD, m.match("stop being a dick").highestSeverity());
        assertEquals(FlagSeverity.MILD, m.match("what a douchebag").highestSeverity());
        assertEquals(FlagSeverity.MODERATE, m.match("shut the fuck up").highestSeverity());
        assertEquals(FlagSeverity.MODERATE, m.match("go fuck yourself").highestSeverity());
        assertEquals(FlagSeverity.MODERATE, m.match("you are a cunt").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, m.match("you are a retard").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, m.match("that is retarded").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, m.match("just kill   yourself").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, m.match("get out you faggot").highestSeverity());
        assertFalse(m.match("craft a diamond hoe and kill the zombie in the nether").hasMatches());
        assertFalse(m.match("damn that crap pissed me off, stop teabagging and playing cornhole").hasMatches());
        assertFalse(m.match("we had a great game, gg everyone").hasMatches());
    }

    @Test
    void noFlaggedTermAppearsInMultipleTiers() {
        Set<String> seen = new java.util.HashSet<>();
        for (WordlistEntry entry : DEFAULT.entries()) {
            if (entry.tier().isAllow()) {
                continue;
            }
            String norm = TextNormalizer.normalizeTerm(entry.term(), false);
            assertTrue(seen.add(norm), "Duplicate flagged term across tiers: " + entry.term());
        }
    }
}
