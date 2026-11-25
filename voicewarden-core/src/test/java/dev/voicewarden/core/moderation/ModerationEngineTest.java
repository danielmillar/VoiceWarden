package dev.voicewarden.core.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ModerationEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Map<FlagSeverity, List<VoiceAction>> PLAN_ESCALATION = Map.of(
            FlagSeverity.MILD, List.of(VoiceAction.REPORT, VoiceAction.WARN, VoiceAction.MUTE),
            FlagSeverity.MODERATE, List.of(VoiceAction.WARN, VoiceAction.MUTE),
            FlagSeverity.SEVERE, List.of(VoiceAction.MUTE));
    private static final MuteLadderSettings PLAN_LADDER = new MuteLadderSettings(true, new TreeMap<>(Map.of(
            1, Duration.ofMinutes(5),
            2, Duration.ofMinutes(15),
            3, Duration.ofMinutes(30),
            4, Duration.ofHours(1),
            5, Duration.ofHours(6))), Duration.ofHours(24), Duration.ofDays(30), Duration.ofMinutes(60));

    private final InMemoryStrikePersistence persistence = new InMemoryStrikePersistence();

    private ModerationEngine engine(boolean requireConfirmation, boolean autoMuteBuiltin, CustomCommandSettings commands) {
        return engine(PLAN_ESCALATION, requireConfirmation, autoMuteBuiltin, commands);
    }

    private ModerationEngine engine(
            Map<FlagSeverity, List<VoiceAction>> escalation, boolean requireConfirmation, boolean autoMuteBuiltin, CustomCommandSettings commands) {
        ModerationSettings settings = new ModerationSettings(3, Duration.ofMinutes(10), escalation, requireConfirmation, autoMuteBuiltin);
        return ModerationEngine.create(settings, PLAN_LADDER, commands, persistence);
    }

    private ModerationEngine defaultEngine() {
        return engine(true, true, CustomCommandSettings.none());
    }

    private static Detection detection(String utterance, DetectionPhase phase, ConfirmationState state, String transcript, MatchedWord... words) {
        return new Detection(PLAYER, "Steve", utterance, phase, transcript, List.of(words), state);
    }

    private static Detection confirmed(String utterance, DetectionPhase phase, MatchedWord... words) {
        return detection(utterance, phase, ConfirmationState.CONFIRMED, "some words here", words);
    }

    private static MatchedWord mild(String word) {
        return new MatchedWord(word, FlagSeverity.MILD);
    }

    private static MatchedWord moderate(String word) {
        return new MatchedWord(word, FlagSeverity.MODERATE);
    }

    private static MatchedWord severe(String word) {
        return new MatchedWord(word, FlagSeverity.SEVERE);
    }

    @Test
    void noMatchesIsIgnored() {
        ModerationDecision decision = defaultEngine().evaluate(confirmed("u1", DetectionPhase.FINAL), NOW);
        assertEquals(ModerationDecision.Outcome.IGNORED, decision.outcome());
        assertFalse(decision.staffAlert());
        assertEquals(0, persistence.countRecentStrikes(PLAYER, NOW.minusSeconds(1)));
    }

    @Test
    void unconfirmedNeverPunishesWhenConfirmationIsRequired() {
        CustomCommandSettings commands = new CustomCommandSettings(
                Map.of("slur", List.of("kick {player}")), List.of("mute-cmd {player}"), List.of("warn-cmd {player}"), true, true, true);
        ModerationEngine engine = engine(true, true, commands);

        for (int i = 0; i < 10; i++) {
            ModerationDecision decision = engine.evaluate(
                    detection("u" + i, DetectionPhase.LIVE, ConfirmationState.UNCONFIRMED, "slur", severe("slur")), NOW);
            assertEquals(ModerationDecision.Outcome.UNCONFIRMED, decision.outcome());
            assertTrue(decision.staffAlertUnconfirmed());
            assertTrue(decision.discordAlertUnconfirmed());
            assertFalse(decision.staffAlert());
            assertFalse(decision.discordAlert());
            assertFalse(decision.warn());
            assertFalse(decision.mute());
            assertFalse(decision.strikeRecorded());
            assertEquals(Optional.empty(), decision.action());
            assertEquals(Optional.empty(), decision.muteDuration());
            assertTrue(decision.commandPlan().isEmpty());
            assertFalse(decision.shouldDropActiveUtterance());
            assertEquals(0, decision.strikeCount());
        }
        assertEquals(0, persistence.countRecentStrikes(PLAYER, NOW.minusSeconds(1)));

        ModerationDecision backstop = engine.evaluate(
                detection("u0", DetectionPhase.FINAL, ConfirmationState.CONFIRMED, "slur", severe("slur")), NOW);
        assertEquals(ModerationDecision.Outcome.ACTED, backstop.outcome());
        assertEquals(1, backstop.strikeCount());
        assertEquals(List.of("kick Steve"), backstop.commandPlan().commands());
    }

    @ParameterizedTest
    @CsvSource({
        "UNCONFIRMED, true, UNCONFIRMED",
        "UNCONFIRMED, false, ACTED",
        "CONFIRMED, true, ACTED",
        "CONFIRMED, false, ACTED",
        "UNCHECKED, true, ACTED",
        "UNCHECKED, false, ACTED"
    })
    void confirmationMatrix(ConfirmationState state, boolean requireConfirmation, ModerationDecision.Outcome expected) {
        ModerationEngine engine = engine(requireConfirmation, true, CustomCommandSettings.none());
        ModerationDecision decision = engine.evaluate(detection("u1", DetectionPhase.FINAL, state, "x", severe("x")), NOW);
        assertEquals(expected, decision.outcome());
        assertEquals(expected == ModerationDecision.Outcome.ACTED, decision.mute());
        assertEquals(expected == ModerationDecision.Outcome.ACTED, decision.staffAlert());
        assertEquals(expected == ModerationDecision.Outcome.UNCONFIRMED, decision.staffAlertUnconfirmed());
    }

    @Test
    void highestMatchedTierWins() {
        ModerationDecision decision = defaultEngine().evaluate(confirmed("u1", DetectionPhase.FINAL, mild("darn"), severe("slur"), moderate("crap")), NOW);
        assertEquals(Optional.of(FlagSeverity.SEVERE), decision.severity());
        assertEquals(Optional.of(VoiceAction.MUTE), decision.action());
        assertEquals(List.of("darn", "slur", "crap"), decision.matchedWords());
    }

    @Test
    void mildLadderStepsEveryFlagThresholdStrikes() {
        ModerationEngine engine = defaultEngine();
        List<VoiceAction> actions = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            actions.add(engine.evaluate(confirmed("u" + i, DetectionPhase.FINAL, mild("darn")), NOW.plusSeconds(i)).action().orElseThrow());
        }
        assertEquals(List.of(
                VoiceAction.REPORT, VoiceAction.REPORT,
                VoiceAction.WARN, VoiceAction.WARN, VoiceAction.WARN,
                VoiceAction.MUTE, VoiceAction.MUTE), actions);
    }

    @Test
    void moderateLadderWarnsThenMutes() {
        ModerationEngine engine = defaultEngine();
        ModerationDecision first = engine.evaluate(confirmed("u1", DetectionPhase.FINAL, moderate("crap")), NOW);
        assertEquals(Optional.of(VoiceAction.WARN), first.action());
        assertTrue(first.warn());
        engine.evaluate(confirmed("u2", DetectionPhase.FINAL, moderate("crap")), NOW);
        ModerationDecision third = engine.evaluate(confirmed("u3", DetectionPhase.FINAL, moderate("crap")), NOW);
        assertEquals(Optional.of(VoiceAction.MUTE), third.action());
        assertEquals(3, third.strikeCount());
    }

    @Test
    void strikesOutsideTheWindowDoNotEscalate() {
        ModerationEngine engine = defaultEngine();
        engine.evaluate(confirmed("u1", DetectionPhase.FINAL, mild("darn")), NOW);
        engine.evaluate(confirmed("u2", DetectionPhase.FINAL, mild("darn")), NOW);
        ModerationDecision later = engine.evaluate(confirmed("u3", DetectionPhase.FINAL, mild("darn")), NOW.plus(Duration.ofMinutes(11)));
        assertEquals(1, later.strikeCount());
        assertEquals(Optional.of(VoiceAction.REPORT), later.action());
    }

    @Test
    void liveMuteDropsTheUtteranceAndFinalPassNeverPunishesAgain() {
        ModerationEngine engine = defaultEngine();
        ModerationDecision live = engine.evaluate(confirmed("u1", DetectionPhase.LIVE, severe("slur")), NOW);
        assertTrue(live.mute());
        assertTrue(live.shouldDropActiveUtterance());
        assertEquals(Optional.of(Duration.ofMinutes(5)), live.muteDuration());

        ModerationDecision finalPass = engine.evaluate(confirmed("u1", DetectionPhase.FINAL, severe("slur"), mild("darn")), NOW.plusSeconds(2));
        assertEquals(ModerationDecision.Outcome.DUPLICATE, finalPass.outcome());
        assertFalse(finalPass.mute());
        assertFalse(finalPass.staffAlert());
        assertFalse(finalPass.strikeRecorded());
        assertEquals(1, persistence.countRecentStrikes(PLAYER, NOW.minusSeconds(1)));
    }

    @Test
    void finalArrivingBeforeLiveIsAlsoDeduplicated() {
        ModerationEngine engine = defaultEngine();
        ModerationDecision finalPass = engine.evaluate(confirmed("u1", DetectionPhase.FINAL, severe("slur")), NOW);
        assertFalse(finalPass.shouldDropActiveUtterance());
        ModerationDecision live = engine.evaluate(confirmed("u1", DetectionPhase.LIVE, severe("slur")), NOW);
        assertEquals(ModerationDecision.Outcome.DUPLICATE, live.outcome());
        assertFalse(live.shouldDropActiveUtterance());
        assertEquals(1, persistence.countRecentStrikes(PLAYER, NOW.minusSeconds(1)));
    }

    @Test
    void finalPassEscalatesWithoutASecondStrike() {
        CustomCommandSettings commands = new CustomCommandSettings(Map.of(), List.of("mute-cmd {player}"), List.of("warn-cmd {player}"), true, true, true);
        ModerationEngine engine = engine(true, true, commands);
        ModerationDecision live = engine.evaluate(confirmed("u1", DetectionPhase.LIVE, mild("darn")), NOW);
        assertEquals(Optional.of(VoiceAction.REPORT), live.action());
        assertEquals(List.of("warn-cmd Steve"), live.commandPlan().commands());

        ModerationDecision finalPass = engine.evaluate(confirmed("u1", DetectionPhase.FINAL, mild("darn"), severe("slur")), NOW.plusSeconds(1));
        assertEquals(ModerationDecision.Outcome.ACTED, finalPass.outcome());
        assertEquals(Optional.of(VoiceAction.MUTE), finalPass.action());
        assertFalse(finalPass.strikeRecorded());
        assertEquals(1, finalPass.strikeCount());
        assertTrue(finalPass.mute());
        assertFalse(finalPass.shouldDropActiveUtterance());
        assertEquals(List.of("mute-cmd Steve"), finalPass.commandPlan().commands());
        assertEquals(1, persistence.countRecentStrikes(PLAYER, NOW.minusSeconds(1)));
    }

    @Test
    void escalationThatWouldRepeatTheSameActionIsADuplicate() {
        Map<FlagSeverity, List<VoiceAction>> warnOnly = Map.of(
                FlagSeverity.MILD, List.of(VoiceAction.WARN),
                FlagSeverity.MODERATE, List.of(VoiceAction.WARN));
        ModerationEngine engine = engine(warnOnly, true, true, CustomCommandSettings.none());
        assertTrue(engine.evaluate(confirmed("u1", DetectionPhase.LIVE, mild("darn")), NOW).warn());
        ModerationDecision finalPass = engine.evaluate(confirmed("u1", DetectionPhase.FINAL, moderate("crap")), NOW);
        assertEquals(ModerationDecision.Outcome.DUPLICATE, finalPass.outcome());
        assertFalse(finalPass.warn());

        ModerationDecision repeat = engine.evaluate(confirmed("u1", DetectionPhase.FINAL, moderate("crap")), NOW);
        assertEquals(ModerationDecision.Outcome.DUPLICATE, repeat.outcome());
        assertEquals(Optional.of(FlagSeverity.MODERATE), engine.strikes().verdict(PLAYER, "u1", NOW).map(UtteranceVerdict::severity));
    }

    @Test
    void concurrentLiveAndFinalPassesRecordExactlyOneStrike() throws Exception {
        ModerationEngine engine = defaultEngine();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ModerationDecision>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            DetectionPhase phase = i % 2 == 0 ? DetectionPhase.LIVE : DetectionPhase.FINAL;
            futures.add(pool.submit(() -> {
                start.await();
                return engine.evaluate(confirmed("shared", phase, severe("slur")), NOW);
            }));
        }
        start.countDown();
        int acted = 0;
        for (Future<ModerationDecision> future : futures) {
            if (future.get().acted()) {
                acted++;
            }
        }
        pool.shutdown();
        assertEquals(1, acted);
        assertEquals(1, persistence.countRecentStrikes(PLAYER, NOW.minusSeconds(1)));
    }

    @Test
    void muteDurationFollowsTheLadder() {
        ModerationEngine engine = defaultEngine();
        persistence.recordMute(PLAYER, NOW.minus(Duration.ofDays(2)));
        persistence.recordMute(PLAYER, NOW.minus(Duration.ofDays(1)));
        persistence.recordMute(PLAYER, NOW.minus(Duration.ofDays(31)));
        ModerationDecision decision = engine.evaluate(confirmed("u1", DetectionPhase.FINAL, severe("slur")), NOW);
        assertEquals(Optional.of(Duration.ofMinutes(30)), decision.muteDuration());
    }

    @Test
    void keywordCommandsComeFirstAndFireOncePerSession() {
        CustomCommandSettings commands = new CustomCommandSettings(
                Map.of("griefer", List.of("say {player} step one", "jail {player}")),
                List.of("mute-cmd {player}"),
                List.of("warn-cmd {player}"),
                true, true, true);
        ModerationEngine engine = engine(true, true, commands);

        ModerationDecision first = engine.evaluate(
                detection("u1", DetectionPhase.FINAL, ConfirmationState.CONFIRMED, "you GRIEFER", severe("slur")), NOW);
        assertEquals(List.of("say Steve step one", "jail Steve"), first.commandPlan().commands());
        assertEquals(List.of("griefer"), first.commandPlan().firedKeywords());
        assertFalse(first.commandPlan().muteCommandsRan());

        ModerationDecision second = engine.evaluate(
                detection("u2", DetectionPhase.FINAL, ConfirmationState.CONFIRMED, "griefer again", severe("slur")), NOW);
        assertEquals(List.of("mute-cmd Steve"), second.commandPlan().commands());

        ModerationDecision third = engine.evaluate(
                detection("u3", DetectionPhase.FINAL, ConfirmationState.CONFIRMED, "griefer", mild("darn")), NOW);
        assertEquals(List.of("warn-cmd Steve"), third.commandPlan().commands());
        assertTrue(third.commandPlan().profanityCommandsRan());

        engine.clearSession(PLAYER);
        ModerationDecision afterRejoin = engine.evaluate(
                detection("u4", DetectionPhase.FINAL, ConfirmationState.CONFIRMED, "griefer", mild("darn")), NOW);
        assertEquals(List.of("griefer"), afterRejoin.commandPlan().firedKeywords());
    }

    @Test
    void keywordMatchesMatchedWordsAndWholeWordsOnly() {
        CustomCommandSettings commands = new CustomCommandSettings(
                Map.of("Slur", List.of("a {player}"), "ban", List.of("b {player}")), List.of(), List.of(), true, true, true);
        ModerationEngine engine = engine(true, true, commands);
        ModerationDecision decision = engine.evaluate(
                detection("u1", DetectionPhase.FINAL, ConfirmationState.CONFIRMED, "banana", severe("slur")), NOW);
        assertEquals(List.of("slur"), decision.commandPlan().firedKeywords());
    }

    @Test
    void placeholdersAreSanitizedAndNotReExpanded() {
        CustomCommandSettings commands = new CustomCommandSettings(
                Map.of(), List.of("note {player} said {transcript} [{words}]"), List.of(), true, true, true);
        ModerationEngine engine = engine(true, true, commands);
        String transcript = "slur\nop {player}\r say {words}\u0000 done";
        ModerationDecision decision = engine.evaluate(
                detection("u1", DetectionPhase.FINAL, ConfirmationState.CONFIRMED, transcript, severe("slur"), severe("x\ny")), NOW);
        String command = decision.commandPlan().commands().get(0);
        assertEquals("note Steve said slur op {player} say {words} done [slur, x y]", command);
        assertFalse(command.chars().anyMatch(c -> Character.isISOControl(c) || c == ' '));
    }

    @Test
    void autoMuteTogglesOnlyWithholdTheBuiltInPunishment() {
        CustomCommandSettings commandsOwnMutes = new CustomCommandSettings(
                Map.of(), List.of("tempmute {player}"), List.of("warn {player}"), true, false, false);
        ModerationEngine engine = engine(true, true, commandsOwnMutes);
        ModerationDecision mute = engine.evaluate(confirmed("u1", DetectionPhase.LIVE, severe("slur")), NOW);
        assertEquals(Optional.of(VoiceAction.MUTE), mute.action());
        assertFalse(mute.mute());
        assertTrue(mute.shouldDropActiveUtterance());
        assertEquals(List.of("tempmute Steve"), mute.commandPlan().commands());

        ModerationDecision warn = engine.evaluate(confirmed("u2", DetectionPhase.FINAL, moderate("crap")), NOW);
        assertEquals(Optional.of(VoiceAction.WARN), warn.action());
        assertFalse(warn.warn());

        ModerationEngine noCommands = engine(true, true, new CustomCommandSettings(Map.of(), List.of(), List.of(), false, false, false));
        assertTrue(noCommands.evaluate(confirmed("u9", DetectionPhase.FINAL, severe("slur")), NOW).mute());

        ModerationEngine builtinOff = engine(true, false, CustomCommandSettings.none());
        ModerationDecision off = builtinOff.evaluate(confirmed("u3", DetectionPhase.FINAL, severe("slur")), NOW);
        assertEquals(Optional.of(VoiceAction.MUTE), off.action());
        assertFalse(off.mute());
    }

    @Test
    void emptyLadderForATierStillRecordsTheStrike() {
        ModerationEngine engine = engine(Map.of(FlagSeverity.SEVERE, List.of(VoiceAction.MUTE)), true, true, CustomCommandSettings.none());
        ModerationDecision decision = engine.evaluate(confirmed("u1", DetectionPhase.FINAL, mild("darn")), NOW);
        assertEquals(ModerationDecision.Outcome.ACTED, decision.outcome());
        assertEquals(Optional.empty(), decision.action());
        assertTrue(decision.strikeRecorded());
        assertTrue(decision.staffAlert());
    }

    @Test
    void pardonResetsEscalation() {
        ModerationEngine engine = defaultEngine();
        for (int i = 0; i < 3; i++) {
            engine.evaluate(confirmed("u" + i, DetectionPhase.FINAL, mild("darn")), NOW);
        }
        engine.pardon(PLAYER);
        ModerationDecision decision = engine.evaluate(confirmed("u9", DetectionPhase.FINAL, mild("darn")), NOW);
        assertEquals(1, decision.strikeCount());
        assertEquals(Optional.of(VoiceAction.REPORT), decision.action());
    }

    @Test
    void defaultThresholdAutoWarnsAndAutoMutesAcrossEveryTier() {
        ModerationSettings settings = new ModerationSettings(2, Duration.ofMinutes(10), PLAN_ESCALATION, true, true, true);
        ModerationEngine mildEngine = ModerationEngine.create(settings, PLAN_LADDER, CustomCommandSettings.none(), new InMemoryStrikePersistence());
        assertEquals(VoiceAction.REPORT, mildEngine.evaluate(confirmed("m1", DetectionPhase.FINAL, mild("damn")), NOW).action().orElseThrow());
        ModerationDecision m2 = mildEngine.evaluate(confirmed("m2", DetectionPhase.FINAL, mild("damn")), NOW);
        assertEquals(VoiceAction.WARN, m2.action().orElseThrow());
        assertTrue(m2.warn());
        assertEquals(VoiceAction.WARN, mildEngine.evaluate(confirmed("m3", DetectionPhase.FINAL, mild("damn")), NOW).action().orElseThrow());
        ModerationDecision m4 = mildEngine.evaluate(confirmed("m4", DetectionPhase.FINAL, mild("damn")), NOW);
        assertEquals(VoiceAction.MUTE, m4.action().orElseThrow());
        assertTrue(m4.mute());
        assertEquals(Optional.of(Duration.ofMinutes(5)), m4.muteDuration());

        ModerationEngine modEngine = ModerationEngine.create(settings, PLAN_LADDER, CustomCommandSettings.none(), new InMemoryStrikePersistence());
        ModerationDecision mod1 = modEngine.evaluate(confirmed("o1", DetectionPhase.FINAL, moderate("cunt")), NOW);
        assertEquals(VoiceAction.WARN, mod1.action().orElseThrow());
        assertTrue(mod1.warn());
        ModerationDecision mod2 = modEngine.evaluate(confirmed("o2", DetectionPhase.FINAL, moderate("cunt")), NOW);
        assertEquals(VoiceAction.MUTE, mod2.action().orElseThrow());
        assertTrue(mod2.mute());

        ModerationSettings warnOff = new ModerationSettings(2, Duration.ofMinutes(10), PLAN_ESCALATION, true, false, true);
        ModerationEngine quietWarn = ModerationEngine.create(warnOff, PLAN_LADDER, CustomCommandSettings.none(), new InMemoryStrikePersistence());
        ModerationDecision suppressed = quietWarn.evaluate(confirmed("q1", DetectionPhase.FINAL, moderate("cunt")), NOW);
        assertEquals(VoiceAction.WARN, suppressed.action().orElseThrow());
        assertFalse(suppressed.warn());
    }
}
