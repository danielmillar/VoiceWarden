package dev.danielmillar.voicewarden.consent;

import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.config.PluginConfig.ConsentMode;
import dev.danielmillar.voicewarden.config.PluginConfig.OnDeny;
import dev.danielmillar.voicewarden.consent.ConsentService.Outcome;
import dev.danielmillar.voicewarden.consent.ConsentService.Status;
import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConsentServiceTest {
    @TempDir Path folder;
    private final UUID player = UUID.randomUUID();
    private PluginConfig.Consent settings = new PluginConfig.Consent(ConsentMode.CONFIRM, 1, OnDeny.STAY, false);
    private Database database;
    private long now = 1_000L;
    private ConsentService consent;

    @BeforeEach void open() {
        database = Fakes.database(folder);
        consent = service();
    }

    @AfterEach void close() {
        database.close();
    }

    @Test void noticeModeCountsJoiningAsConsentWithoutAsking() {
        settings = new PluginConfig.Consent(ConsentMode.NOTICE, 1, OnDeny.KICK, false);
        assertEquals(Status.NOT_REQUIRED, consent.status(player));
        assertTrue(consent.allowsProcessing(player));
        assertFalse(consent.blocksSpeech(player));
        assertFalse(consent.shouldAsk(player));
    }

    @Test void unansweredPlayerIsAskedAndNeitherProcessedNorHeard() {
        assertEquals(Status.PENDING, consent.status(player));
        assertTrue(consent.shouldAsk(player));
        assertFalse(consent.allowsProcessing(player));
        assertTrue(consent.blocksSpeech(player));
    }

    @Test void acceptAllowsProcessingAndSurvivesARestart() {
        assertEquals(Outcome.ACCEPTED, consent.answer(player, true));
        assertTrue(consent.allowsProcessing(player));
        assertFalse(consent.blocksSpeech(player));
        restart();
        assertEquals(Status.ACCEPTED, consent.status(player));
        assertFalse(consent.shouldAsk(player));
    }

    @Test void denyWithStayIsStoredBlocksSpeechAndIsNotAskedAgain() {
        assertEquals(Outcome.DENIED, consent.answer(player, false));
        restart();
        assertEquals(Status.DENIED, consent.status(player));
        assertFalse(consent.allowsProcessing(player));
        assertTrue(consent.blocksSpeech(player));
        assertFalse(consent.shouldAsk(player));
    }

    @Test void denyWhileSpeakingWithoutConsentIsHeardButNeverProcessed() {
        settings = new PluginConfig.Consent(ConsentMode.CONFIRM, 1, OnDeny.STAY, true);
        consent.answer(player, false);
        assertFalse(consent.blocksSpeech(player));
        assertFalse(consent.allowsProcessing(player));
    }

    @Test void denyWithKickKicksAndAsksAgainOnTheNextJoin() {
        settings = new PluginConfig.Consent(ConsentMode.CONFIRM, 1, OnDeny.KICK, false);
        assertEquals(Outcome.KICK, consent.answer(player, false));
        restart();
        assertTrue(consent.shouldAsk(player));
        assertEquals(Outcome.ACCEPTED, consent.answer(player, true));
        assertFalse(consent.shouldAsk(player));
    }

    @Test void switchingFromStayToKickAsksPlayersWhoDenied() {
        consent.answer(player, false);
        assertFalse(consent.shouldAsk(player));
        settings = new PluginConfig.Consent(ConsentMode.CONFIRM, 1, OnDeny.KICK, false);
        assertTrue(consent.shouldAsk(player));
    }

    @Test void raisingTheRulesVersionAsksEveryoneAgainAndStopsProcessingUntilAccepted() {
        consent.answer(player, true);
        UUID denied = UUID.randomUUID();
        consent.answer(denied, false);
        settings = new PluginConfig.Consent(ConsentMode.CONFIRM, 2, OnDeny.STAY, false);
        for (UUID id : new UUID[]{player, denied}) {
            assertEquals(Status.PENDING, consent.status(id));
            assertTrue(consent.shouldAsk(id));
            assertFalse(consent.allowsProcessing(id));
        }
        consent.answer(player, true);
        restart();
        assertEquals(Status.ACCEPTED, consent.status(player));
        assertEquals(2, consent.answer(player).orElseThrow().rulesVersion());
    }

    @Test void changingTheAnswerReplacesTheStoredOne() {
        consent.answer(player, true);
        consent.answer(player, false);
        restart();
        assertEquals(Status.DENIED, consent.status(player));
        consent.answer(player, true);
        restart();
        assertEquals(Status.ACCEPTED, consent.status(player));
    }

    @Test void answersOfPlayersNotSeenForTheRetentionAreDeletedUnlessOnline() {
        UUID returning = UUID.randomUUID();
        UUID online = UUID.randomUUID();
        for (UUID id : new UUID[]{player, returning, online}) {
            consent.answer(id, true);
        }
        long day = java.util.concurrent.TimeUnit.DAYS.toMillis(1);
        now += 20 * day;
        consent.seen(returning);
        now += 20 * day;
        assertEquals(1, consent.prune(now, 30, online::equals));
        restart();
        assertTrue(consent.answer(player).isEmpty());
        assertEquals(Status.PENDING, consent.status(player));
        assertEquals(Status.ACCEPTED, consent.status(returning));
        assertEquals(Status.ACCEPTED, consent.status(online));
        assertEquals(0, consent.prune(now, 0, id -> false));
    }

    @Test void forgetDeletesOnlyThatPlayersAnswer() {
        UUID other = UUID.randomUUID();
        consent.answer(player, true);
        consent.answer(other, true);
        assertTrue(consent.forget(player));
        restart();
        assertTrue(consent.answer(player).isEmpty());
        assertEquals(Status.ACCEPTED, consent.status(other));
    }

    @Test void withdrawingConsentClearsTheBufferedTranscript() {
        var buffer = new dev.danielmillar.voicewarden.moderation.TranscriptBuffer(
                new dev.danielmillar.voicewarden.moderation.TranscriptBuffer.Limits(java.time.Duration.ofHours(1), 10, 1_000));
        consent.onDenied(buffer::forget);
        UUID other = UUID.randomUUID();
        consent.answer(player, true);
        buffer.add(player, "Alice", java.time.Instant.EPOCH, "hello", new short[10]);
        buffer.add(other, "Bob", java.time.Instant.EPOCH, "hi", new short[10]);
        consent.answer(player, false);
        assertTrue(buffer.recentLines(player, 10).isEmpty());
        assertEquals(1, buffer.recentLines(other, 10).size());
        assertEquals(20, buffer.audioBytes());
    }

    private ConsentService service() {
        var service = new ConsentService(() -> settings, database, () -> now, Logger.getAnonymousLogger());
        service.load();
        return service;
    }

    private void restart() {
        database.close();
        database = Fakes.database(folder);
        consent = service();
    }
}
