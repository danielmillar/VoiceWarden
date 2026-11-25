package dev.voicewarden.core.wordlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WordlistParserTest {

    private static List<String> terms(ParsedWordlist parsed, String language, WordlistTier tier) {
        return parsed.entries(language, tier).stream().map(WordlistEntry::term).toList();
    }

    @Test
    void parsesAllFourTiersWithCaseInsensitiveHeaders() {
        ParsedWordlist parsed = WordlistParser.parse("""
                [en-profanity]
                damn
                [EN-Moderate]
                whore
                [ EN - MUTE ]
                kill yourself
                [En-Allow]
                class
                """);
        assertEquals(List.of("damn"), terms(parsed, "en", WordlistTier.PROFANITY));
        assertEquals(List.of("whore"), terms(parsed, "en", WordlistTier.MODERATE));
        assertEquals(List.of("kill yourself"), terms(parsed, "en", WordlistTier.MUTE));
        assertEquals(List.of("class"), terms(parsed, "en", WordlistTier.ALLOW));
        assertEquals(Set.of("en"), parsed.languages());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
    }

    @Test
    void ignoresCommentsBlankLinesAndTrimsPhrases() {
        ParsedWordlist parsed = WordlistParser.parse("﻿# header\r\n\r\n[EN-MUTE]\r\n"
                + "   # indented comment\r\n\t  kill   \t yourself  \r\n\r\n");
        assertEquals(List.of("kill yourself"), terms(parsed, "en", WordlistTier.MUTE));
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
    }

    @Test
    void dropsDuplicatesAndReportsThem() {
        ParsedWordlist parsed = WordlistParser.parse("""
                [EN-PROFANITY]
                damn
                DAMN
                damn
                [EN-MODERATE]
                damn
                """);
        assertEquals(List.of("damn"), terms(parsed, "en", WordlistTier.PROFANITY));
        assertEquals(List.of("damn"), terms(parsed, "en", WordlistTier.MODERATE));
        assertEquals(2, parsed.warnings().size(), parsed.warnings().toString());
        assertTrue(parsed.warnings().get(0).contains("line 3"));
    }

    @Test
    void caseSensitiveParsingKeepsCaseVariants() {
        ParsedWordlist parsed = WordlistParser.parse("[EN-PROFANITY]\ndamn\nDAMN\n",
                WordlistConfig.builder().caseSensitive(true).build());
        assertEquals(List.of("damn", "DAMN"), terms(parsed, "en", WordlistTier.PROFANITY));
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void reportsMalformedSectionsAndOrphanEntries() {
        ParsedWordlist parsed = WordlistParser.parse("""
                orphan
                [EN-SPICY]
                ignored
                [not a header
                [EN-PROFANITY]
                damn
                """);
        assertEquals(List.of("damn"), terms(parsed, "en", WordlistTier.PROFANITY));
        assertEquals(1, parsed.size());
        assertEquals(4, parsed.warnings().size(), parsed.warnings().toString());
        assertTrue(parsed.warnings().get(0).contains("line 1"));
        assertTrue(parsed.warnings().get(1).contains("unknown tier 'SPICY'"));
        assertTrue(parsed.warnings().get(2).contains("line 3"));
        assertTrue(parsed.warnings().get(3).contains("malformed section header"));
    }

    @Test
    void filtersLanguagesIncludingAllowSections() {
        String content = """
                [EN-PROFANITY]
                damn
                [EN-ALLOW]
                class
                [ES-PROFANITY]
                mierda
                [FR-PROFANITY]
                merde
                """;
        ParsedWordlist onlyEnglish = WordlistParser.parse(content,
                WordlistConfig.builder().enabledLanguages("EN").build());
        assertEquals(Set.of("en"), onlyEnglish.languages());
        assertEquals(2, onlyEnglish.size());

        ParsedWordlist onlySpanish = WordlistParser.parse(content,
                WordlistConfig.builder().enabledLanguages("es").build());
        assertEquals(List.of("mierda"), onlySpanish.entries().stream().map(WordlistEntry::term).toList());

        for (List<String> all : List.of(List.<String>of(), List.of("*"), List.of("en", "*"))) {
            ParsedWordlist parsed = WordlistParser.parse(content,
                    WordlistConfig.builder().enabledLanguages(all).build());
            assertEquals(Set.of("en", "es", "fr"), parsed.languages(), all.toString());
        }
    }

    @Test
    void mergesConfigOverridesUnderTheConfigLanguageRegardlessOfFilter() {
        WordlistConfig config = WordlistConfig.builder()
                .enabledLanguages("de")
                .extraProfanityWords(List.of("heck", "  "))
                .extraModerateWords(List.of("noob trash"))
                .extraMuteWords(List.of("  dox   you "))
                .extraAllowWords(List.of("checkmate"))
                .build();
        ParsedWordlist parsed = WordlistParser.parse("[EN-PROFANITY]\ndamn\n", config);
        String lang = WordlistParser.CONFIG_LANGUAGE;
        assertEquals(List.of("heck"), terms(parsed, lang, WordlistTier.PROFANITY));
        assertEquals(List.of("noob trash"), terms(parsed, lang, WordlistTier.MODERATE));
        assertEquals(List.of("dox you"), terms(parsed, lang, WordlistTier.MUTE));
        assertEquals(List.of("checkmate"), terms(parsed, lang, WordlistTier.ALLOW));
        assertFalse(parsed.languages().contains("en"));
        assertEquals(4, parsed.size());
    }

    @Test
    void emptyContentParsesToNothing() {
        ParsedWordlist parsed = WordlistParser.parse("");
        assertEquals(0, parsed.size());
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void configDefaultsMatchThePlan() {
        WordlistConfig config = WordlistConfig.defaults();
        assertFalse(config.partialMatch());
        assertFalse(config.caseSensitive());
        assertTrue(config.allLanguagesEnabled());
        assertTrue(config.isLanguageEnabled("ja"));
        WordlistConfig english = config.toBuilder().enabledLanguages(List.of(" EN ", "en")).build();
        assertEquals(List.of("en"), english.enabledLanguages());
        assertFalse(english.isLanguageEnabled("es"));
    }
}
