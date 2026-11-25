package dev.voicewarden.engine;

import static dev.voicewarden.api.ConfirmationState.CONFIRMED;
import static dev.voicewarden.api.ConfirmationState.UNCONFIRMED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DoubleCheckEvaluatorTest {

    private final DoubleCheckEvaluator evaluator = new DoubleCheckEvaluator();

    @Test
    void normalizesCasePunctuationApostrophesAndWhitespace() {
        assertEquals("you are an idiot", DoubleCheckEvaluator.normalize("  YOU'RE   an... IDIOT!!  "));
        assertEquals("you are an idiot", DoubleCheckEvaluator.normalize("You’re an idiot"));
        assertEquals("i do not care", DoubleCheckEvaluator.normalize("I don't care"));
        assertEquals("i do not care", DoubleCheckEvaluator.normalize("i dont care"));
        assertEquals("the player", DoubleCheckEvaluator.normalize("the player's"));
        assertEquals("", DoubleCheckEvaluator.normalize("  ...  "));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "you are an idiot | You're an idiot!",
        "kill yourself | KILL YOURSELF.",
        "shut up | well shut up already",
        "you stupid idiots | you stupid idiot",
        "he is fucking useless | he's fuckin useless",
        "dumbass | dumb ass",
        "dumb ass | dumbass",
        "stop griefing my base | stop griefin my base",
        "i will destroy you all | i'll destroy you all",
        "you're trash at this game | youre trash at this game",
        "this whole team is complete garbage | uh this whole team is complete garbage man",
    })
    void confirmsMatchingTranscripts(String primary, String verifier) {
        assertEquals(CONFIRMED, evaluator.evaluate(primary, verifier));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "kill yourself | thank you",
        "you idiot | you",
        "shut up | what's up",
        "you are an idiot | i think it is fine",
        "kill yourself | ",
        "kill yourself | ...",
        "this whole team is complete garbage | the weather is nice today in town",
        "slur | sir",
        "fuck | duck",
        "spic | spicy",
        "tard | tardy",
        "butt | butter",
        "sin | sing",
        "the | they",
        "ass | assert",
        "rap | raped",
    })
    void rejectsNoiseHallucinationsAndUnrelatedWords(String primary, String verifier) {
        assertEquals(UNCONFIRMED, evaluator.evaluate(primary, verifier));
    }

    @Test
    void emptyPrimaryIsNeverConfirmed() {
        assertEquals(UNCONFIRMED, evaluator.evaluate("", "anything"));
        assertEquals(UNCONFIRMED, evaluator.evaluate(null, null));
    }

    @Test
    void longTranscriptsToleratesSmallDifferencesButNotHalf() {
        String primary = "you are honestly the worst player on this whole server";
        assertEquals(CONFIRMED, evaluator.evaluate(primary, "you are honestly the worst player on this server"));
        assertEquals(UNCONFIRMED, evaluator.evaluate(primary, "honestly the server is fine"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "raped | rape",
        "used | use",
        "stopped | stop",
        "kills | kill",
        "bitches | bitch",
        "parties | party",
        "hating | hate",
        "nothin | nothing",
        "rapped | rap",
        "hitting | hit",
    })
    void acceptsPluralPossessiveAndTenseVariants(String primary, String verifier) {
        assertEquals(CONFIRMED, evaluator.evaluate(primary, verifier));
    }

    @Test
    void onlyThePhraseLevelCallCatchesAMisheardFlaggedWordInALongSentence() {
        String heardByPrimary = "you are a stupid slur and i hate you";
        String heardByVerifier = "you are a stupid sir and i hate you";
        assertEquals(CONFIRMED, evaluator.evaluate(heardByPrimary, heardByVerifier),
                "sentence-level agreement tolerates one differing word");
        assertEquals(UNCONFIRMED, evaluator.evaluate("slur", heardByVerifier),
                "VERIFY_REQUEST must carry the matched phrase so the flagged word itself is checked");
        assertEquals(CONFIRMED, evaluator.evaluate("stupid slur", heardByPrimary));
    }

    @Test
    void wordOrderMatters() {
        assertEquals(UNCONFIRMED, evaluator.evaluate("yourself kill", "kill yourself"));
    }

    @Test
    void coverageIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new DoubleCheckEvaluator(0));
        assertThrows(IllegalArgumentException.class, () -> new DoubleCheckEvaluator(1.5));
    }
}
