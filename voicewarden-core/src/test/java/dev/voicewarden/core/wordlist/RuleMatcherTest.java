package dev.voicewarden.core.wordlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.FlagSeverity;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleMatcherTest {

    private static final ParsedRules DEFAULT_RULES = RulesParser.parseDefault();
    private static final RuleMatcher DEFAULT_MATCHER = RuleMatcher.compile(
            WordlistParser.parseDefault(WordlistConfig.defaults()),
            WordlistConfig.defaults(),
            DEFAULT_RULES);

    @Test
    void bundledRulesParseCleanlyAndCompile() {
        assertTrue(DEFAULT_RULES.warnings().isEmpty(), DEFAULT_RULES.warnings().toString());
        assertFalse(DEFAULT_RULES.allowlist().isEmpty());
        assertTrue(DEFAULT_RULES.enabledRules().size() >= 15);
        assertTrue(DEFAULT_MATCHER.ruleCount() >= 15);
        assertTrue(DEFAULT_MATCHER.regexPatternCount() >= 15);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "join my server right now",
        "come to our server tonight",
        "check out play.rivalmc.net",
        "join discord.gg/abc1234",
        "go to coolserver dot com",
        "our invite is discord dot gg"
    })
    void catchesWrittenAndSpokenAdvertising(String text) {
        WordlistMatchResult result = DEFAULT_MATCHER.match(text);
        assertTrue(result.hasMatches(), "Expected advertising hit for: " + text);
        assertEquals(FlagSeverity.MILD, result.highestSeverity());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "watch the tutorial on youtube.com",
        "I saw that on youtube dot com",
        "check minecraft dot net for the patch",
        "download the mod from modrinth.com",
        "look at github.com or wikipedia.org"
    })
    void allowsCommonBenignDomains(String text) {
        assertFalse(DEFAULT_MATCHER.match(text).hasMatches(), () -> DEFAULT_MATCHER.match(text).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "his ip is 203.0.113.42",
        "email me at player@example.com",
        "send it to steve at gmail dot com",
        "call 555-123-4567 right now",
        "my number is (555) 123-4567",
        "dial 555-1234",
        "5551234567",
        "five five five zero one nine nine",
        "my address is 123 oak street",
        "her address is right next door"
    })
    void catchesWrittenAndSpokenDoxxingPii(String text) {
        WordlistMatchResult result = DEFAULT_MATCHER.match(text);
        assertTrue(result.hasMatches(), "Expected doxxing hit for: " + text);
        assertEquals(FlagSeverity.MILD, result.highestSeverity());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "we are playing on minecraft 1.21.4 today",
        "my coordinates are 120 64 350",
        "meet at x 1200 y 64 z -3500",
        "go to one hundred twenty sixty four three hundred fifty",
        "connect locally on 127.0.0.1",
        "my router is 192.168.1.1"
    })
    void doesNotFlagMinecraftVersionsCoordinatesOrLoopbackIpsAsDoxxing(String text) {
        assertFalse(DEFAULT_MATCHER.match(text).hasMatches(), () -> DEFAULT_MATCHER.match(text).toString());
    }

    @Test
    void catchesDoxxingThreatsHarassmentAndContractionsAsSevere() {
        assertEquals(FlagSeverity.SEVERE, DEFAULT_MATCHER.match("I'll dox you tonight").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, DEFAULT_MATCHER.match("I'm gonna swat you").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, DEFAULT_MATCHER.match("I'll kill you in real life").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, DEFAULT_MATCHER.match("kill all jews").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, DEFAULT_MATCHER.match("all immigrants should die").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, DEFAULT_MATCHER.match("blood and soil").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, DEFAULT_MATCHER.match("I will rape you").highestSeverity());
    }

    @Test
    void catchesModerateHarassmentAndIdentityAbuseWhileLeavingNeutralIdentitiesClean() {
        assertEquals(FlagSeverity.MODERATE, DEFAULT_MATCHER.match("send me nudes").highestSeverity());
        assertEquals(FlagSeverity.MODERATE, DEFAULT_MATCHER.match("that's so gay").highestSeverity());
        assertEquals(FlagSeverity.MODERATE, DEFAULT_MATCHER.match("you worthless immigrant").highestSeverity());
        assertEquals(FlagSeverity.MODERATE, DEFAULT_MATCHER.match("dirty black player").highestSeverity());
        assertEquals(FlagSeverity.MILD, DEFAULT_MATCHER.match("you're worthless").highestSeverity());
        assertEquals(FlagSeverity.MILD, DEFAULT_MATCHER.match("you don't belong here").highestSeverity());
        assertEquals(FlagSeverity.MILD, DEFAULT_MATCHER.match("I'll kill you in pvp").highestSeverity());

        assertFalse(DEFAULT_MATCHER.match("she is gay and my friend is muslim").hasMatches());
        assertFalse(DEFAULT_MATCHER.match("he is transgender and plays minecraft").hasMatches());
        assertFalse(DEFAULT_MATCHER.match("happy jewish holiday everyone").hasMatches());
    }

    @Test
    void excludeRegexGuardsPreventMutingReportsOrCondemnations() {
        assertEquals(FlagSeverity.SEVERE,
                DEFAULT_MATCHER.match("send me nudes or I will leak your pictures").highestSeverity());
        assertEquals(FlagSeverity.MODERATE,
                DEFAULT_MATCHER.match("never send me nudes or they will leak it").highestSeverity());

        assertEquals(FlagSeverity.SEVERE,
                DEFAULT_MATCHER.match("trade underage nudes with me").highestSeverity());
        assertEquals(FlagSeverity.MILD,
                DEFAULT_MATCHER.match("report anyone who tries to share underage nudes").highestSeverity());
    }

    @Test
    void catchesObfuscatedSlursAndRecognitionVariantsWithoutScunthorpeFalsePositives() {
        assertEquals(FlagSeverity.SEVERE, DEFAULT_MATCHER.match("stop saying n.i.g.g.e.r").highestSeverity());
        assertEquals(FlagSeverity.SEVERE, DEFAULT_MATCHER.match("f.a.g.g.o.t").highestSeverity());
        assertEquals(FlagSeverity.MILD, DEFAULT_MATCHER.match("he said the n word").highestSeverity());
        assertEquals(FlagSeverity.MILD, DEFAULT_MATCHER.match("nig ger").highestSeverity());
        assertEquals(FlagSeverity.MILD, DEFAULT_MATCHER.match("stop saying nibba").highestSeverity());
        assertFalse(DEFAULT_MATCHER.match("Arnold Schwarzenegger sniggered in Scunthorpe").hasMatches());
    }

    @Test
    void prefilterNeverSuppressesAMatchingDefaultRegex() {
        List<String> probes = List.of(
                "203.0.113.42", "bob@example.com", "steve at gmail dot com", "555-123-4567",
                "555-1234", "5551234567", "five five five zero one nine nine",
                "discord.gg/abc123", "play.rival.net", "rival dot net",
                "send me nudes or I will leak them", "share underage nudes",
                "kill all jews", "all muslims should die",
                "dirty immigrant", "worthless black player",
                "n.i.g.g.e.r", "f.a.g.g.o.t", "k.i.k.e");
        for (RuleDefinition rule : DEFAULT_RULES.enabledRules()) {
            for (String raw : rule.regex()) {
                Pattern pattern = Pattern.compile(raw, RulesParser.REGEX_FLAGS);
                RegexPrefilter prefilter = RegexPrefilter.analyze(raw);
                for (String probe : probes) {
                    if (pattern.matcher(probe).find()) {
                        String lower = probe.toLowerCase(Locale.ROOT);
                        boolean digit = probe.chars().anyMatch(c -> c >= '0' && c <= '9');
                        boolean dot = probe.indexOf('.') >= 0;
                        boolean at = probe.indexOf('@') >= 0;
                        assertTrue(prefilter.mightMatch(lower, digit, dot, at),
                                "Prefilter missed probe '" + probe + "' for regex: " + raw);
                    }
                }
            }
        }
    }

    @Test
    void regexBudgetAbortsPathologicalBacktrackingWithoutHanging() {
        ParsedRules pathological = RulesParser.parse("""
                rules:
                  slow:
                    enabled: true
                    severity: MILD
                    regex:
                      - '(a+)+$'
                """);
        RuleMatcher matcher = RuleMatcher.compile(ParsedWordlist.EMPTY, WordlistConfig.defaults(), pathological, Duration.ofMillis(10));
        long start = System.nanoTime();
        WordlistMatchResult result = matcher.match("a".repeat(35) + "!");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertFalse(result.hasMatches());
        assertTrue(elapsedMs < 500, "Pathological regex took too long: " + elapsedMs + " ms");
    }

    @Test
    void parserReportsInvalidRegexUnknownSeverityAndBrokenYaml() {
        ParsedRules broken = RulesParser.parse("rules: [unclosed");
        assertEquals(1, broken.warnings().size());

        ParsedRules badRules = RulesParser.parse("""
                rules:
                  bad-sev:
                    severity: EXTREME
                    words: [foo]
                  bad-regex:
                    severity: MILD
                    regex:
                      - '[unclosed'
                      - 'valid'
                """);
        assertEquals(2, badRules.warnings().size(), badRules.warnings().toString());
        assertEquals(1, badRules.rules().size());
        assertEquals(List.of("valid"), badRules.rules().getFirst().regex());
    }

    @Test
    void prefilterDoesNotDropQuantifiedLettersOrMultiCharEscapes() {
        ParsedRules rules = RulesParser.parse("""
                rules:
                  stretched:
                    enabled: true
                    severity: MILD
                    regex:
                      - 'f+u+c+k+'
                      - 'fo+bar'
                      - 'ca{2,4}b'
                      - '\\x41\\x42'
                """);
        RuleMatcher matcher = RuleMatcher.compile(ParsedWordlist.EMPTY, WordlistConfig.defaults(), rules);
        assertTrue(matcher.match("ffuucckk").hasMatches());
        assertTrue(matcher.match("foooobar").hasMatches());
        assertTrue(matcher.match("caaaab").hasMatches());
        assertTrue(matcher.match("AB").hasMatches());
    }

    @Test
    void perRuleAllowDoesNotSuppressOtherRulesOrGlobalWordlist() {
        ParsedWordlist wordlist = WordlistParser.parse("[EN-MUTE]\nfrell\n", WordlistConfig.defaults());
        ParsedRules rules = RulesParser.parse("""
                rules:
                  rule-a:
                    enabled: true
                    severity: MILD
                    words: [zorp]
                    allow: [safe zorp, safe frell]
                  rule-b:
                    enabled: true
                    severity: SEVERE
                    words: [zorp]
                """);
        RuleMatcher matcher = RuleMatcher.compile(wordlist, WordlistConfig.defaults(), rules);
        WordlistMatchResult zorpResult = matcher.match("safe zorp");
        assertTrue(zorpResult.hasMatches());
        assertEquals(FlagSeverity.SEVERE, zorpResult.highestSeverity());
        assertTrue(matcher.match("safe frell").hasMatches());
    }

    @Test
    void unicodeCaseExpansionDoesNotShiftRuleAllowOffsets() {
        ParsedRules rules = RulesParser.parse("""
                rules:
                  domains:
                    enabled: true
                    severity: MILD
                    regex:
                      - 'example\\.com'
                    allow:
                      - 'safe.example.com'
                """);
        RuleMatcher matcher = RuleMatcher.compile(ParsedWordlist.EMPTY, WordlistConfig.defaults(), rules);
        assertFalse(matcher.match("\u0130\u0130 visit safe.example.com today").hasMatches());
        assertTrue(matcher.match("\u0130\u0130 visit bad.example.com today").hasMatches());
    }
}
