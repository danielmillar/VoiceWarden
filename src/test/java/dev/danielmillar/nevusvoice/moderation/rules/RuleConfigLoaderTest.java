package dev.danielmillar.nevusvoice.moderation.rules;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuleConfigLoaderTest {
    private static RuleConfigLoader.LoadResult yaml(String text) {
        return RuleConfigLoader.loadRulesYaml(YamlConfiguration.loadConfiguration(new StringReader(text)));
    }

    @Test
    void loadsAllYamlFieldsAndKeepsDisabledRulesForCompilation() {
        RuleConfigLoader.LoadResult result = yaml("""
                allowlist: [class, grass]
                rules:
                  harassment:
                    enabled: true
                    category: Harassment
                    action: mute
                    weight: 3
                    words: [kys]
                    phrases: [kill yourself]
                    regex: ['\\bthreat\\b']
                    allow: [okay]
                  optional:
                    enabled: false
                    action: FLAG
                    words: [disabled]
                """);
        assertTrue(result.warnings().isEmpty());
        assertEquals(Set.of("class", "grass"), result.allowlist());
        assertEquals(2, result.rules().size());
        RuleDefinition rule = result.rules().getFirst();
        assertEquals("harassment", rule.id());
        assertEquals("Harassment", rule.category());
        assertEquals(RuleAction.MUTE, rule.action());
        assertEquals(3, rule.weight());
        assertTrue(rule.enabled());
        assertEquals(List.of("kys"), rule.words());
        assertEquals(List.of("kill yourself"), rule.phrases());
        assertEquals(List.of("\\bthreat\\b"), rule.regex());
        assertEquals(List.of("okay"), rule.allow());
        assertFalse(result.rules().get(1).enabled());
        assertEquals("Optional", result.rules().get(1).category());
        assertEquals(1, RuleEngine.compile(result.rules(), result.allowlist()).ruleCount());
    }

    @Test
    void unknownAndMissingActionsDefaultToFlagAndBadWeightsAreClamped() {
        RuleConfigLoader.LoadResult result = yaml("""
                rules:
                  sexual-harassment:
                    action: BAN
                    weight: 0
                    words: [bad]
                  another_rule:
                    weight: -3
                    words: [worse]
                """);
        assertEquals(4, result.warnings().size());
        assertEquals(List.of("Sexual Harassment", "Another Rule"), result.rules().stream().map(RuleDefinition::category).toList());
        for (RuleDefinition rule : result.rules()) {
            assertEquals(RuleAction.FLAG, rule.action());
            assertEquals(1, rule.weight());
        }
    }

    @Test
    void nonSectionsAndEmptyRulesAreSkippedWhileRemainingRulesSurvive() {
        RuleConfigLoader.LoadResult result = yaml("""
                rules:
                  invalid: not a section
                  empty:
                    action: FLAG
                    words: []
                  valid:
                    action: MUTE
                    words: [kys]
                """);
        assertEquals(2, result.warnings().size());
        assertEquals(List.of("valid"), result.rules().stream().map(RuleDefinition::id).toList());
    }

    @Test
    void invalidListsAndEntriesProduceWarningsAndAreSkipped() {
        RuleConfigLoader.LoadResult result = yaml("""
                allowlist: wrong
                rules:
                  mixed:
                    action: FLAG
                    words: [shit, '', 123, null]
                    phrases: not-a-list
                    regex: []
                    allow: [class, false]
                """);
        assertEquals(6, result.warnings().size());
        assertEquals(List.of("shit"), result.rules().getFirst().words());
        assertEquals(List.of("class"), result.rules().getFirst().allow());
        assertTrue(result.allowlist().isEmpty());
    }

    @Test
    void nullAndMissingYamlSectionsProduceWarnings() {
        assertFalse(RuleConfigLoader.loadRulesYaml(null).warnings().isEmpty());
        assertFalse(yaml("allowlist: [hello]").warnings().isEmpty());
        assertFalse(yaml("rules: invalid").warnings().isEmpty());
        assertTrue(yaml("rules: {}").warnings().isEmpty());
    }

    @Test
    void titleFallbackHandlesAnIdContainingOnlySeparators() {
        RuleConfigLoader.LoadResult result = yaml("""
                rules:
                  '---':
                    action: FLAG
                    words: [bad]
                """);
        assertTrue(result.warnings().isEmpty());
        assertEquals("Uncategorised", result.rules().getFirst().category());
    }

    @Test
    void invalidRegexIsACompileWarningRatherThanAnIoParsingProblem() {
        RuleConfigLoader.LoadResult result = yaml("""
                rules:
                  regex:
                    action: FLAG
                    regex: ['[', 'good']
                """);
        assertTrue(result.warnings().isEmpty());
        RuleEngine engine = RuleEngine.compile(result.rules(), result.allowlist());
        assertEquals(1, engine.warnings().size());
        assertEquals(1, engine.match("good").size());
    }

    @Test
    void wordListMergesRepeatedCaseInsensitiveHeadersAndRoutesActions() {
        RuleConfigLoader.LoadResult result = RuleConfigLoader.loadWordList(new StringReader("""
                # owner comments
                [EN-PROFANITY]
                fuck*
                kill yourself

                [en-profanity]
                shit*
                fuck*
                [EN-MUTE]
                badslur*
                [ES-PROFANITY]
                mierda
                """), Set.of());
        assertTrue(result.warnings().isEmpty());
        assertTrue(result.allowlist().isEmpty());
        assertEquals(List.of("wordlist-en-profanity", "wordlist-en-mute", "wordlist-es-profanity"),
                result.rules().stream().map(RuleDefinition::id).toList());
        assertEquals(List.of("fuck*", "kill yourself", "shit*"), result.rules().getFirst().words());
        assertEquals(RuleAction.FLAG, result.rules().getFirst().action());
        assertEquals("Profanity", result.rules().getFirst().category());
        assertEquals(RuleAction.MUTE, result.rules().get(1).action());
        assertEquals("Mute list", result.rules().get(1).category());
        assertEquals(1, result.rules().get(1).weight());
        assertTrue(result.rules().get(1).enabled());
    }

    @Test
    void wordListLoadsOnlyEnabledLanguagesCaseInsensitively() {
        RuleConfigLoader.LoadResult result = RuleConfigLoader.loadWordList(new StringReader("""
                [EN-PROFANITY]
                shit
                [ES-PROFANITY]
                mierda
                [EN-MUTE]
                badslur
                [FR-PROFANITY]
                merde
                """), Set.of(" eN "));
        assertTrue(result.warnings().isEmpty());
        assertEquals(2, result.rules().size());
        assertTrue(result.rules().stream().allMatch(rule -> rule.id().startsWith("wordlist-en-")));
    }

    @Test
    void wordListRejectsUnknownTypesMalformedHeadersAndOrphanTerms() {
        RuleConfigLoader.LoadResult result = RuleConfigLoader.loadWordList(new StringReader("""
                orphan
                [EN-UNKNOWN]
                ignored
                [not a header]
                ignored too
                [EN-MUTE]
                accepted
                """), Set.of());
        assertEquals(3, result.warnings().size());
        assertEquals(1, result.rules().size());
        assertEquals(List.of("accepted"), result.rules().getFirst().words());
    }

    @Test
    void unknownTypeStillWarnsInADisabledLanguageAndNeverLeaksIntoPreviousSection() {
        RuleConfigLoader.LoadResult result = RuleConfigLoader.loadWordList(new StringReader("""
                [EN-PROFANITY]
                good
                [ES-BAN]
                ignored
                [ES-MUTE]
                also ignored
                """), Set.of("EN"));
        assertEquals(1, result.warnings().size());
        assertEquals(List.of("good"), result.rules().getFirst().words());
    }

    @Test
    void wordListAcceptsUtf8BomWhitespaceAndNullLanguageSelection() {
        RuleConfigLoader.LoadResult result = RuleConfigLoader.loadWordList(
                new StringReader("\uFEFF  [en-profanity]  \n # comment\n \n  shit*  \n"), null);
        assertTrue(result.warnings().isEmpty());
        assertEquals(List.of("shit*"), result.rules().getFirst().words());
    }

    @Test
    void emptyWordListSectionsAreSkippedWithWarnings() {
        RuleConfigLoader.LoadResult result = RuleConfigLoader.loadWordList(new StringReader("[EN-MUTE]\n# empty\n"), Set.of());
        assertEquals(1, result.warnings().size());
        assertTrue(result.rules().isEmpty());
        assertTrue(RuleConfigLoader.loadWordList(new StringReader("# just comments\n"), Set.of()).warnings().isEmpty());
        assertFalse(RuleConfigLoader.loadWordList(null, Set.of()).warnings().isEmpty());
    }

    @Test
    void readerRemainsCallerOwnedAndIoFailuresKeepPartialResults() {
        class FailingReader extends Reader {
            private boolean first = true;
            private boolean closed;

            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                if (!first) {
                    throw new IOException("test failure");
                }
                first = false;
                String prefix = "[EN-PROFANITY]\nshit\n";
                prefix.getChars(0, prefix.length(), buffer, offset);
                return prefix.length();
            }

            @Override
            public void close() {
                closed = true;
            }
        }
        FailingReader reader = new FailingReader();
        RuleConfigLoader.LoadResult result = RuleConfigLoader.loadWordList(reader, Set.of());
        assertFalse(reader.closed);
        assertEquals(1, result.warnings().size());
        assertEquals(List.of("shit"), result.rules().getFirst().words());
    }

    @Test
    void overridesCreateAtMostTwoRulesAndFilterEmptyEntries() {
        RuleConfigLoader.LoadResult result = RuleConfigLoader.fromOverrides(
                new ArrayList<>(java.util.Arrays.asList("shit*", "", " ", null, "kill yourself")), List.of("badslur"));
        assertTrue(result.warnings().isEmpty());
        assertTrue(result.allowlist().isEmpty());
        assertEquals(2, result.rules().size());
        RuleDefinition profanity = result.rules().getFirst();
        assertEquals("config-profanity", profanity.id());
        assertEquals("Profanity", profanity.category());
        assertEquals(RuleAction.FLAG, profanity.action());
        assertEquals(List.of("shit*", "kill yourself"), profanity.words());
        RuleDefinition mute = result.rules().get(1);
        assertEquals("config-mute", mute.id());
        assertEquals("Mute list", mute.category());
        assertEquals(RuleAction.MUTE, mute.action());
        assertEquals(1, mute.weight());
        assertEquals(2, RuleEngine.compile(result.rules(), result.allowlist()).match("shit badslur").size());
        assertTrue(RuleConfigLoader.fromOverrides(null, List.of(" ")).rules().isEmpty());
        assertEquals(1, RuleConfigLoader.fromOverrides(List.of(), List.of("badslur")).rules().size());
    }

    @Test
    void loadResultAndLoadedConfigurationAreDefensiveSnapshots() {
        List<RuleDefinition> rules = new ArrayList<>();
        Set<String> allow = new LinkedHashSet<>(Set.of("hello"));
        List<String> warnings = new ArrayList<>(List.of("warning"));
        RuleConfigLoader.LoadResult result = new RuleConfigLoader.LoadResult(rules, allow, warnings);
        rules.clear(); allow.clear(); warnings.clear();
        assertEquals(Set.of("hello"), result.allowlist());
        assertEquals(List.of("warning"), result.warnings());
        assertThrows(UnsupportedOperationException.class, () -> result.rules().add(null));
        assertThrows(UnsupportedOperationException.class, () -> result.allowlist().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.warnings().clear());

        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader("""
                rules:
                  r:
                    action: FLAG
                    words: [shit]
                """));
        RuleConfigLoader.LoadResult loaded = RuleConfigLoader.loadRulesYaml(config);
        config.set("rules.r.words", List.of("changed"));
        assertEquals(List.of("shit"), loaded.rules().getFirst().words());
    }
}
