package dev.danielmillar.voicesentinel.moderation.rules;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRulesTest {
    private static RuleEngine defaults() throws IOException {
        RuleConfigLoader.LoadResult yaml;
        RuleConfigLoader.LoadResult words;
        try (var reader = new InputStreamReader(Objects.requireNonNull(DefaultRulesTest.class.getResourceAsStream("/rules.yml")),
                StandardCharsets.UTF_8)) {
            yaml = RuleConfigLoader.loadRulesYaml(YamlConfiguration.loadConfiguration(reader));
        }
        try (var reader = new InputStreamReader(Objects.requireNonNull(DefaultRulesTest.class.getResourceAsStream("/wordlist.txt")),
                StandardCharsets.UTF_8)) {
            words = RuleConfigLoader.loadWordList(reader, Set.of("EN"));
        }
        assertTrue(yaml.warnings().isEmpty(), yaml.warnings().toString());
        assertTrue(words.warnings().isEmpty(), words.warnings().toString());
        List<RuleDefinition> combined = new ArrayList<>(yaml.rules());
        combined.addAll(words.rules());
        RuleEngine engine = RuleEngine.compile(combined, yaml.allowlist());
        assertTrue(engine.warnings().isEmpty(), engine.warnings().toString());
        return engine;
    }

    @Test
    void bundledResourcesLoadWithoutWarningsWithExpandedCoverage() throws IOException {
        RuleEngine engine = defaults();
        assertEquals(20, engine.ruleCount());
        assertTrue(engine.termCount() >= 550);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "you're such a fucking idiot.|wordlist-en-profanity|FLAG",
            "That is complete bullshit.|wordlist-en-profanity|FLAG",
            "you are a nigger.|wordlist-en-mute|MUTE",
            "stop using faggot here.|wordlist-en-mute|MUTE",
            "Kill, your self!|harassment|MUTE",
            "K Y S|harassment|MUTE",
            "I will find you.|harassment|MUTE",
            "send nudes.|sexual-harassment|FLAG",
            "suck my dick.|sexual-harassment|FLAG",
            "play dot example dot net|advertising|FLAG",
            "example.com|advertising|FLAG",
            "join my server|advertising|FLAG",
            "my address is 10 Example Street.|doxxing|FLAG",
            "call 07700 900123|doxxing|FLAG",
            "call zero seven seven zero zero nine zero zero one two three|doxxing|FLAG"
    })
    void representativeSentencesMatchTheExpectedRule(String transcript, String ruleId, RuleAction action) throws IOException {
        List<RuleMatch> matches = defaults().match(transcript);
        assertEquals(1, matches.size(), matches.toString());
        assertEquals(ruleId, matches.getFirst().ruleId());
        assertEquals(action, matches.getFirst().action());
        assertEquals(transcript.substring(matches.getFirst().start(), matches.getFirst().end()), matches.getFirst().matchedText());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "What a cockwomble.|wordlist-en-profanity|FLAG",
            "You are a douchebag.|wordlist-en-profanity|FLAG",
            "GTFO.|wordlist-en-profanity|FLAG",
            "You are a jigaboo.|wordlist-en-mute|MUTE",
            "Stop saying wet back.|wordlist-en-mute|MUTE",
            "Stop saying golliwog.|wordlist-en-mute|MUTE",
            "You are a batty boy.|wordlist-en-mute|MUTE",
            "You are a troon.|wordlist-en-mute|MUTE",
            "N I G G E R|wordlist-en-mute|MUTE",
            "F A G G O T|wordlist-en-mute|MUTE",
            "N1gg3r.|obfuscated-slurs|MUTE",
            "F4gg0t.|obfuscated-slurs|MUTE",
            "N.i.g.g.e.r.|obfuscated-slurs|MUTE",
            "You should kill yourself.|harassment|MUTE",
            "Go and kill yourself.|harassment|MUTE",
            "I will kill you in real life.|harassment|MUTE",
            "I'll kill you.|threats|FLAG",
            "I am going to kill you.|threats|FLAG",
            "You are a retard.|targeted-abuse|FLAG",
            "You're a loser.|targeted-abuse|FLAG",
            "Go back to your country.|identity-abuse|FLAG",
            "You filthy Muslim.|identity-abuse|FLAG",
            "You disgusting black person.|identity-abuse|FLAG",
            "Kill all the Jews.|hate-incitement|MUTE",
            "All Muslims should die.|hate-incitement|MUTE",
            "Heil Hitler.|extremist-slogans|MUTE",
            "I will rape you.|sexual-threats|MUTE",
            "I'll leak your address.|doxxing-threats|MUTE",
            "I'm going to swat you.|doxxing-threats|MUTE",
            "Send me naked pictures.|sexual-harassment|FLAG",
            "Join our Discord.|advertising|FLAG",
            "Hello, Jeronegger.|recognition-variants|FLAG",
            "nig ga|recognition-variants|FLAG",
            "the n word|recognition-variants|FLAG",
            "fag got|recognition-variants|FLAG",
            "a fag|wordlist-en-profanity|FLAG",
            "a dyke|wordlist-en-profanity|FLAG"
    })
    void expandedTermsAndContextRulesMatchWithoutDoubleCounting(String transcript, String ruleId, RuleAction action)
            throws IOException {
        List<RuleMatch> matches = defaults().match(transcript);
        assertEquals(1, matches.size(), matches.toString());
        RuleMatch match = matches.getFirst();
        assertEquals(ruleId, match.ruleId(), matches.toString());
        assertEquals(action, match.action());
        assertEquals(transcript.substring(match.start(), match.end()), match.matchedText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "We played with a gay player and a trans player.",
            "My friend is Jewish and my neighbour is Muslim.",
            "The autistic player built a castle.",
            "The Pakistani family visited Pakistan.",
            "Dickens and Dickinson enjoyed cocktails in Scunthorpe.",
            "The cockatoo and cockerel were near the cockpit.",
            "The prickly bush had prickles.",
            "He sniggered at the sniggering raccoons.",
            "We watched Arnold Schwarzenegger.",
            "That is a bigger block, not a trigger.",
            "The fire retardant protects the fabric.",
            "The cumulative damage increased.",
            "We will meet in Cumbria.",
            "Damnation is the title of the book.",
            "The hellhound walked through the hellscape.",
            "The dirty black wool goes in this chest.",
            "The white power supply powers the computer.",
            "We chopped shiitake and shitake mushrooms.",
            "That chinking noise came from the glass.",
            "The specialist gave a speech about homophones.",
            "The homogeneity of the mixture is interesting."
    })
    void ordinaryWordsAndNeutralIdentityMentionsRemainClean(String transcript) throws IOException {
        assertTrue(defaults().match(transcript).isEmpty(), transcript);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "pornography|pornography|FLAG|1",
            "p0rn|pornography|FLAG|1",
            "hentai|pornography|FLAG|1",
            "only fans|pornography|FLAG|1",
            "sex tape|pornography|FLAG|1",
            "revenge porn|pornography|FLAG|1",
            "rape|sexual-abuse|FLAG|1",
            "raped|sexual-abuse|FLAG|1",
            "sexual assault|sexual-abuse|FLAG|1",
            "sexual violence|sexual-abuse|FLAG|1",
            "sextortion|sexual-abuse|FLAG|1",
            "child porn|child-safety|FLAG|1",
            "child pornography|child-safety|FLAG|1",
            "child sexual abuse|child-safety|FLAG|1",
            "underage nudes|child-safety|FLAG|1",
            "CSAM|child-safety|FLAG|1",
            "paedophile|child-safety|FLAG|1",
            "sexual grooming|child-safety|FLAG|1",
            "send me child porn|child-exploitation|MUTE|3",
            "trade CSAM|child-exploitation|MUTE|3",
            "I want underage porn|child-exploitation|MUTE|3",
            "share with me child pornography|child-exploitation|MUTE|3",
            "I'm looking for CSAM|child-exploitation|MUTE|3",
            "I will sexually assault you|sexual-threats|MUTE|2",
            "I'll molest you|sexual-threats|MUTE|2",
            "send nudes or I'll leak|sexual-coercion|MUTE|2",
            "give me naked pictures or I will expose|sexual-coercion|MUTE|2"
    })
    void sexualContentAndExploitationHaveTheExpectedSeverity(String transcript, String ruleId, RuleAction action,
                                                            int weight) throws IOException {
        List<RuleMatch> matches = defaults().match(transcript);
        assertEquals(1, matches.size(), matches.toString());
        RuleMatch match = matches.getFirst();
        assertEquals(ruleId, match.ruleId(), matches.toString());
        assertEquals(action, match.action());
        assertEquals(weight, match.weight());
        assertEquals(transcript.substring(match.start(), match.end()), match.matchedText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Don't share child porn.",
            "Do not send me child pornography.",
            "Never trade CSAM.",
            "Report anyone selling CSAM.",
            "Trading child porn is illegal.",
            "I reported sexual assault.",
            "We work against child sexual abuse.",
            "Do not send nudes or I'll leak.",
            "Stop saying send nudes or I will expose."
    })
    void reportingAndProhibitionRequireStaffReviewInsteadOfAutomaticMuting(String transcript) throws IOException {
        List<RuleMatch> matches = defaults().match(transcript);
        assertFalse(matches.isEmpty(), transcript);
        assertTrue(matches.stream().allMatch(match -> match.action() == RuleAction.FLAG), matches.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The grapes and rapeseed grow near Essex and Sussex.",
            "The therapist spoke about an analysis.",
            "We reached the CP checkpoint.",
            "BBC reported the latest score.",
            "We bought a DP cable.",
            "The pet groomer brushes dogs.",
            "Please do not tell your parents about their surprise party.",
            "The children are building a castle.",
            "The pediatrician uses a pedometer.",
            "This block is under age restrictions."
    })
    void ordinarySpeechDoesNotMatchSexualTerms(String transcript) throws IOException {
        assertTrue(defaults().match(transcript).isEmpty(), transcript);
    }

    @Test
    void defaultAllowlistedSentencesAreClean() throws IOException {
        RuleEngine engine = defaults();
        assertTrue(engine.match("the assassin went to class on the grass").isEmpty());
        assertTrue(engine.match("Hello! We had cocktails while reading Dickens in Scunthorpe.").isEmpty());
        assertTrue(engine.match("The prickly cockatoo sat above the cockpit, next to a cockroach.").isEmpty());
    }

    @Test
    void ordinaryNumbersAndConversationsDoNotTriggerDefaultRules() throws IOException {
        RuleEngine engine = defaults();
        for (String input : List.of("I have 64 diamonds and 32 gold.", "The coordinates are 120, 70, 230.",
                "One two three four five six.", "Let's meet near the castle and build a farm.", "We played all night.")) {
            assertTrue(engine.match(input).isEmpty(), input);
        }
    }
}
