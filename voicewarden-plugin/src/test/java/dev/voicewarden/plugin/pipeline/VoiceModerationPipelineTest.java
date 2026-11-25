package dev.voicewarden.plugin.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.event.VoiceFlagEvent;
import dev.voicewarden.api.event.VoiceStrikeEvent;
import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.core.audio.AcousticAbuseEvent;
import dev.voicewarden.core.audio.FinalUtterance;
import dev.voicewarden.core.audio.LiveAudioChunk;
import dev.voicewarden.core.audio.MicSpamConfig;
import dev.voicewarden.core.audio.ShardedAudioPipeline;
import dev.voicewarden.core.config.ConfigFiles;
import dev.voicewarden.core.config.ConfigLoader;
import dev.voicewarden.core.config.VoiceWardenConfig;
import dev.voicewarden.core.discord.DiscordWebhookClient;
import dev.voicewarden.core.discord.FlagWebhookPayload;
import dev.voicewarden.core.discord.MicSpamWebhookPayload;
import dev.voicewarden.core.discord.MuteWebhookPayload;
import dev.voicewarden.core.discord.WebhookKind;
import dev.voicewarden.core.discord.WebhookResult;
import dev.voicewarden.core.ipc.EngineClient;
import dev.voicewarden.core.ipc.IpcMessage;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.moderation.InMemoryStrikePersistence;
import dev.voicewarden.core.moderation.TranscriptBuffer;
import dev.voicewarden.core.moderation.TranscriptEntry;
import dev.voicewarden.core.storage.InMemoryFallbackStore;
import dev.voicewarden.core.wordlist.AhoCorasickMatcher;
import dev.voicewarden.core.wordlist.WordlistConfig;
import dev.voicewarden.core.wordlist.WordlistParser;
import dev.voicewarden.core.wordlist.WordlistRegistry;
import dev.voicewarden.plugin.FakePlayers;
import dev.voicewarden.plugin.ManualScheduler;
import dev.voicewarden.plugin.MutableClock;
import dev.voicewarden.plugin.TestMessages;
import dev.voicewarden.plugin.mute.MuteMirror;
import dev.voicewarden.plugin.mute.NativeMuteService;
import dev.voicewarden.plugin.mute.StrikeCountCache;
import dev.voicewarden.plugin.voice.SpeakerContextTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VoiceModerationPipelineTest {

    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");
    private static final String WORDLIST = "[EN-MUTE]\nzorblax\nblorpa\nblorper\n[EN-PROFANITY]\nfrell\n";
    private static final long UTTERANCE = 7L;

    private final MutableClock clock = new MutableClock(START);
    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakePlayers players = new FakePlayers();
    private final InMemoryFallbackStore storage = new InMemoryFallbackStore("server-1");
    private final InMemoryStrikePersistence strikes = new InMemoryStrikePersistence();
    private final TranscriptBuffer transcripts = TranscriptBuffer.withDefaults();
    private final List<Event> events = new ArrayList<>();
    private final List<String> consoleCommands = new ArrayList<>();
    private final DiscordWebhookClient discord = mock(DiscordWebhookClient.class);
    private final EngineClient engine = mock(EngineClient.class);
    private final ShardedAudioPipeline audio = mock(ShardedAudioPipeline.class);
    private final Player speaker = players.join("Speaker");
    private final Player staff = players.join("Staff");
    private final UUID speakerId = speaker.getUniqueId();
    private final short[] pcm = {1, 2, 3, 4, 5, 6, 7, 8};

    private boolean cancelFlags;
    private NativeMuteService mutes;
    private StrikeCountCache strikeCounts;
    private SpeakerContextTracker speakers;
    private VoiceModerationPipeline pipeline;

    private static VoiceWardenConfig config(boolean doubleCheck) {
        String yaml = ConfigFiles.defaultConfigTemplate()
                .replace("appeal_url: \"\"", "appeal_url: \"https://example.org/appeal\"")
                .replace("mute_commands: []", "mute_commands: [\"say muted {player}\"]");
        if (!doubleCheck) {
            yaml = yaml.replace("    enabled: true\n    # Hits the verifier", "    enabled: false\n    # Hits the verifier");
        }
        return ConfigLoader.loadConfig(yaml).value();
    }

    @BeforeEach
    void setUp() {
        when(staff.hasPermission(VoiceModerationPipeline.ALERTS_PERMISSION)).thenReturn(true);
        when(engine.isConnected()).thenReturn(true);
        when(engine.engineProfile()).thenReturn("ENGLISH");
        when(engine.engineVersion()).thenReturn("test");
        when(engine.sendVerifyRequest(any(), anyLong(), anyString(), any())).thenReturn(true);
        when(discord.sendFlag(any())).thenReturn(CompletableFuture.completedFuture(WebhookResult.skipped(WebhookKind.FLAG, "test")));
        when(discord.sendMute(any())).thenReturn(CompletableFuture.completedFuture(WebhookResult.skipped(WebhookKind.MUTE, "test")));
        when(discord.sendMicSpam(any())).thenReturn(CompletableFuture.completedFuture(WebhookResult.skipped(WebhookKind.FLAG, "test")));
        build(config(true));
    }

    private void build(VoiceWardenConfig config) {
        strikeCounts = new StrikeCountCache((id, since) -> 0, Runnable::run, clock, Duration.ofMinutes(10), Duration.ofSeconds(30));
        speakers = new SpeakerContextTracker(transcripts, config.transcripts(), clock);
        mutes = new NativeMuteService(config.mutes(), storage, scheduler, players, TestMessages.renderer(), events::add, MuteMirror.NONE,
                strikeCounts, id -> true, clock, "server-1", Logger.getLogger("test"));
        WordlistConfig words = WordlistConfig.defaults();
        WordlistRegistry registry = new WordlistRegistry(AhoCorasickMatcher.compile(WordlistParser.parse(WORDLIST, words), words));
        pipeline = new VoiceModerationPipeline(
                VoiceModerationPipeline.Settings.from(config),
                new VoiceModerationPipeline.Services(ConfigBridges.moderation(config, strikes), discord),
                scheduler, players, TestMessages.renderer(), registry, transcripts, speakers, strikeCounts, mutes, events::add,
                event -> {
                    events.add(event);
                    if (cancelFlags && event instanceof VoiceFlagEvent flag) {
                        flag.setCancelled(true);
                    }
                },
                () -> false, consoleCommands::add, clock, Logger.getLogger("test"));
        pipeline.bindAudio(audio);
        pipeline.onEngineReady(engine, 42L);
    }

    private static IpcMessage.TranscriptResult live(UUID player, String text) {
        return new IpcMessage.TranscriptResult(player, UTTERANCE, false, true, false, ConfirmationState.UNCHECKED, text, "en", 120);
    }

    private static IpcMessage.TranscriptResult finalResult(UUID player, String text) {
        return new IpcMessage.TranscriptResult(player, UTTERANCE, true, false, false, ConfirmationState.UNCHECKED, text, "en", 300);
    }

    private static IpcMessage.TranscriptResult verification(UUID player, ConfirmationState state, String text) {
        return new IpcMessage.TranscriptResult(player, UTTERANCE, true, false, true, state, text, "en", 200);
    }

    private void speak() {
        pipeline.onLiveChunk(new LiveAudioChunk(speakerId, UTTERANCE, 0, pcm, false));
    }

    private static List<String> said(CommandSender player) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    private <T extends Event> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void confirmedLiveHitDropsUtteranceMutesAppealsAndRunsEveryAction() {
        speak();
        verify(engine).sendLiveChunk(any());

        pipeline.onTranscript(live(speakerId, "you zorblax"));
        verify(engine).sendVerifyRequest(eq(speakerId), eq(UTTERANCE), eq("zorblax"), eq(pcm));
        assertFalse(mutes.isMuted(speakerId), "nothing happens before Double Check answers");

        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "you zorblax"));

        verify(audio).dropUtterance(speakerId, UTTERANCE);
        verify(engine).sendDropUtterance(speakerId, UTTERANCE);
        assertTrue(mutes.isMuted(speakerId));
        assertEquals("VoiceWarden", mutes.getActiveMute(speakerId).orElseThrow().moderator());
        assertEquals(VoiceModerationPipeline.AUTO_MUTE_REASON, mutes.getActiveMute(speakerId).orElseThrow().reason());
        assertFalse(mutes.getActiveMute(speakerId).orElseThrow().reason().contains("zorblax"));
        assertEquals(List.of("say muted Speaker"), consoleCommands);
        assertTrue(said(speaker).stream().anyMatch(line -> line.contains("▸ ᴀᴘᴘᴇᴀʟ")));
        assertTrue(said(staff).stream().anyMatch(line -> line.contains("Speaker") && line.contains("zorblax") && line.contains("mute")));
        assertEquals(1, strikeCounts.count(speakerId));

        ArgumentCaptor<FlagWebhookPayload> flag = ArgumentCaptor.forClass(FlagWebhookPayload.class);
        verify(discord).sendFlag(flag.capture());
        assertEquals(ConfirmationState.CONFIRMED, flag.getValue().confirmationState());
        assertEquals(pcm.length, flag.getValue().pcm16kAudio().length);
        ArgumentCaptor<MuteWebhookPayload> mute = ArgumentCaptor.forClass(MuteWebhookPayload.class);
        verify(discord).sendMute(mute.capture());
        assertFalse(mute.getValue().isUnmute());
        assertEquals(pcm.length, mute.getValue().pcm16kAudio().length);

        VoiceFlagEvent flagEvent = eventsOf(VoiceFlagEvent.class).getFirst();
        assertTrue(flagEvent.isLiveDetection());
        assertEquals(ConfirmationState.CONFIRMED, flagEvent.getConfirmationState());
        assertEquals(FlagSeverity.SEVERE, eventsOf(VoiceStrikeEvent.class).getFirst().getSeverity());
    }

    @Test
    void flagAndMuteCardsShareOneSnapshotOfNearbyConversationAnchoredAtDetection() {
        UUID world = UUID.randomUUID();
        UUID nearbyId = UUID.randomUUID();
        UUID farId = UUID.randomUUID();
        speakers.updatePosition(speakerId, "Speaker", world, 0, 64, 0);
        speakers.updatePosition(nearbyId, "Nearby", world, 10, 64, 10);
        speakers.updatePosition(farId, "Far", world, 500, 64, 500);
        transcripts.append(nearbyId, TranscriptEntry.clean(START.minusSeconds(50), "who took my diamonds"));
        transcripts.append(farId, TranscriptEntry.clean(START.minusSeconds(30), "nobody can hear me"));
        transcripts.append(speakerId, TranscriptEntry.clean(START.minusSeconds(20), "it was not me"));

        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        clock.advance(Duration.ofSeconds(25));
        transcripts.append(nearbyId, TranscriptEntry.clean(clock.instant().minusSeconds(1), "calm down"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "you zorblax"));

        ArgumentCaptor<FlagWebhookPayload> flag = ArgumentCaptor.forClass(FlagWebhookPayload.class);
        verify(discord).sendFlag(flag.capture());
        ArgumentCaptor<MuteWebhookPayload> mute = ArgumentCaptor.forClass(MuteWebhookPayload.class);
        verify(discord).sendMute(mute.capture());
        List<ConversationLine> context = flag.getValue().contextLines();
        assertEquals(List.of("who took my diamonds", "calm down"), context.stream().map(ConversationLine::text).toList());
        assertTrue(context.stream().allMatch(line -> line.playerName().equals("Nearby")));
        assertEquals(context, mute.getValue().contextLines());
    }

    @Test
    void unconfirmedFlagCardCarriesNearbyConversation() {
        UUID world = UUID.randomUUID();
        UUID nearbyId = UUID.randomUUID();
        speakers.updatePosition(speakerId, "Speaker", world, 0, 64, 0);
        speakers.updatePosition(nearbyId, "Nearby", world, 3, 64, 3);
        transcripts.append(nearbyId, TranscriptEntry.clean(START.minusSeconds(5), "say that again"));

        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "you sore backs"));

        ArgumentCaptor<FlagWebhookPayload> flag = ArgumentCaptor.forClass(FlagWebhookPayload.class);
        verify(discord).sendFlag(flag.capture());
        assertTrue(flag.getValue().unconfirmed());
        assertEquals(List.of("say that again"), flag.getValue().contextLines().stream().map(ConversationLine::text).toList());
    }

    @Test
    void contextLinesZeroSendsNoNearbyConversation() {
        build(ConfigLoader.loadConfig(ConfigFiles.defaultConfigTemplate().replace("context_lines: 4", "context_lines: 0")).value());
        UUID world = UUID.randomUUID();
        UUID nearbyId = UUID.randomUUID();
        speakers.updatePosition(speakerId, "Speaker", world, 0, 64, 0);
        speakers.updatePosition(nearbyId, "Nearby", world, 3, 64, 3);
        transcripts.append(nearbyId, TranscriptEntry.clean(START.minusSeconds(5), "say that again"));

        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "you zorblax"));

        ArgumentCaptor<FlagWebhookPayload> flag = ArgumentCaptor.forClass(FlagWebhookPayload.class);
        verify(discord).sendFlag(flag.capture());
        assertTrue(flag.getValue().contextLines().isEmpty());
    }

    @Test
    void rejectedDoubleCheckOnlyAlertsStaffAsUnconfirmed() {
        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "you sore backs"));

        assertFalse(mutes.isMuted(speakerId));
        assertEquals(0, strikes.countRecentStrikes(speakerId, START.minusSeconds(60)));
        assertTrue(consoleCommands.isEmpty());
        assertTrue(eventsOf(VoiceStrikeEvent.class).isEmpty());
        verify(audio, never()).dropUtterance(any(), anyLong());
        verify(discord, never()).sendMute(any());
        verify(speaker, never()).sendMessage(any(Component.class));
        assertTrue(said(staff).stream().anyMatch(line -> line.contains("unconfirmed, no action")));
        ArgumentCaptor<FlagWebhookPayload> flag = ArgumentCaptor.forClass(FlagWebhookPayload.class);
        verify(discord).sendFlag(flag.capture());
        assertTrue(flag.getValue().unconfirmed());
        assertEquals(null, flag.getValue().actionTaken());
        assertEquals(1, pipeline.status().unconfirmed());
    }

    @Test
    void verifierTextThatStillMatchesTheWordlistConfirms() {
        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "zorblax indeed"));

        assertTrue(mutes.isMuted(speakerId));
    }

    @Test
    void verifierNonRhoticWordlistVariantCorroboratesHit() {
        speak();
        pipeline.onTranscript(live(speakerId, "blorpa"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "blorper"));

        assertTrue(mutes.isMuted(speakerId));
    }

    @Test
    void verifierNonWordlistWordDoesNotCorroborateSimilarStem() {
        speak();
        pipeline.onTranscript(live(speakerId, "blorper"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "blorpah"));

        assertFalse(mutes.isMuted(speakerId));
        assertEquals(1, pipeline.status().unconfirmed());
    }

    @Test
    void blankEngineFailureResultCountsAsUnconfirmed() {
        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCHECKED, ""));

        assertFalse(mutes.isMuted(speakerId));
        assertEquals(1, pipeline.status().unconfirmed());
    }

    @Test
    void liveAndFinalPassesForOneUtteranceAreVerifiedAndPunishedOnce() {
        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        pipeline.onTranscript(live(speakerId, "you zorblax and"));
        pipeline.onFinalUtterance(new FinalUtterance(speakerId, UTTERANCE, pcm, 100));
        pipeline.onTranscript(finalResult(speakerId, "you zorblax and more"));
        verify(engine, times(1)).sendVerifyRequest(any(), anyLong(), anyString(), any());

        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "zorblax"));
        pipeline.onTranscript(finalResult(speakerId, "you zorblax and more"));

        verify(engine, times(1)).sendVerifyRequest(any(), anyLong(), anyString(), any());
        assertEquals(1, eventsOf(VoiceFlagEvent.class).size());
        assertEquals(1, eventsOf(VoiceStrikeEvent.class).size());
        verify(discord, times(1)).sendMute(any());
    }

    @Test
    void newWordsFoundWhileAVerifyIsInFlightAreVerifiedAfterIt() {
        speak();
        pipeline.onTranscript(live(speakerId, "frell"));
        pipeline.onTranscript(live(speakerId, "frell zorblax"));
        verify(engine).sendVerifyRequest(speakerId, UTTERANCE, "frell", pcm);
        verify(engine, never()).sendVerifyRequest(eq(speakerId), eq(UTTERANCE), eq("frell zorblax"), any());

        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "frell"));
        verify(engine).sendVerifyRequest(eq(speakerId), eq(UTTERANCE), eq("frell zorblax"), any());

        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "frell zorblax"));
        assertTrue(mutes.isMuted(speakerId));
    }

    @Test
    void cancelledFlagEventStopsModeration() {
        cancelFlags = true;
        speak();
        pipeline.onTranscript(live(speakerId, "zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "zorblax"));

        assertEquals(1, eventsOf(VoiceFlagEvent.class).size());
        assertFalse(mutes.isMuted(speakerId));
        assertTrue(eventsOf(VoiceStrikeEvent.class).isEmpty());
        verify(discord, never()).sendFlag(any());
        assertTrue(consoleCommands.isEmpty());
    }

    @Test
    void flagEventFiresOnThePlayersSchedulerWithTheRightThreadFlag() {
        speak();
        pipeline.onTranscript(live(speakerId, "zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "zorblax"));

        assertTrue(eventsOf(VoiceFlagEvent.class).getFirst().isAsynchronous());
        assertTrue(scheduler.entityTasksRun() > 0);
    }

    @Test
    void offlinePlayerIsStillModeratedWithAnAsyncEvent() {
        UUID ghost = UUID.randomUUID();
        pipeline.onLiveChunk(new LiveAudioChunk(ghost, UTTERANCE, 0, pcm, false));
        pipeline.onTranscript(live(ghost, "zorblax"));
        pipeline.onTranscript(verification(ghost, ConfirmationState.CONFIRMED, "zorblax"));

        assertTrue(eventsOf(VoiceFlagEvent.class).getFirst().isAsynchronous());
        assertTrue(mutes.isMuted(ghost));
    }

    @Test
    void retiredPlayerFallsBackToTheAsyncPath() {
        scheduler.retire(speaker);
        speak();
        pipeline.onTranscript(live(speakerId, "zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "zorblax"));

        assertEquals(1, eventsOf(VoiceFlagEvent.class).size());
        assertTrue(mutes.isMuted(speakerId));
    }

    @Test
    void withoutDoubleCheckHitsAreActedOnImmediatelyAsUnchecked() {
        build(config(false));
        speak();
        pipeline.onTranscript(live(speakerId, "zorblax"));

        verify(engine, never()).sendVerifyRequest(any(), anyLong(), anyString(), any());
        assertTrue(mutes.isMuted(speakerId));
        assertEquals(ConfirmationState.UNCHECKED, eventsOf(VoiceFlagEvent.class).getFirst().getConfirmationState());
    }

    @Test
    void refusedVerifyRequestSettlesAsUnconfirmed() {
        when(engine.sendVerifyRequest(any(), anyLong(), anyString(), any())).thenReturn(false);
        speak();
        pipeline.onTranscript(live(speakerId, "zorblax"));

        assertFalse(mutes.isMuted(speakerId));
        assertEquals(1, pipeline.status().unconfirmed());
        assertEquals(0, pipeline.status().pendingVerifications());
    }

    @Test
    void unansweredVerificationTimesOutAsUnconfirmed() {
        speak();
        pipeline.onTranscript(live(speakerId, "zorblax"));
        assertEquals(1, pipeline.status().pendingVerifications());

        clock.advance(VoiceModerationPipeline.VERIFY_TIMEOUT.plusSeconds(1));
        pipeline.sweep();

        assertEquals(0, pipeline.status().pendingVerifications());
        assertFalse(mutes.isMuted(speakerId));
        assertEquals(1, pipeline.status().unconfirmed());
    }

    @Test
    void engineLossSettlesPendingVerifications() {
        speak();
        pipeline.onTranscript(live(speakerId, "zorblax"));
        pipeline.onEngineLost(42L, "crashed");

        assertEquals(0, pipeline.status().pendingVerifications());
        assertFalse(pipeline.status().engineConnected());
        assertEquals(1, pipeline.status().unconfirmed());
    }

    @Test
    void mildWordWarnsThePlayer() {
        build(config(false));
        speak();
        pipeline.onTranscript(live(speakerId, "oh frell"));
        pipeline.onTranscript(new IpcMessage.TranscriptResult(speakerId, 8L, false, true, false, ConfirmationState.UNCHECKED,
                "frell again", "en", 100));
        pipeline.onTranscript(new IpcMessage.TranscriptResult(speakerId, 9L, false, true, false, ConfirmationState.UNCHECKED,
                "frell thrice", "en", 100));

        assertTrue(said(speaker).stream().anyMatch(line -> line.contains("Keep voice chat respectful") && !line.contains("frell")));
        assertFalse(mutes.isMuted(speakerId));

        pipeline.onTranscript(new IpcMessage.TranscriptResult(speakerId, 10L, false, true, false, ConfirmationState.UNCHECKED,
                "frell four", "en", 100));
        assertTrue(mutes.isMuted(speakerId));
    }

    @Test
    void everyFinalTranscriptIsKeptForReports() {
        pipeline.onTranscript(finalResult(speakerId, "hello there"));
        pipeline.onTranscript(new IpcMessage.TranscriptResult(speakerId, 8L, true, false, false, ConfirmationState.UNCHECKED,
                "oh frell", "en", 100));
        pipeline.onTranscript(live(speakerId, "partial words"));
        pipeline.onTranscript(finalResult(speakerId, "   "));

        List<TranscriptEntry> lines = transcripts.getRecent(speakerId, 5, clock.instant());
        assertEquals(2, lines.size());
        assertEquals(TranscriptEntry.clean(START, "hello there"), lines.get(0));
        assertEquals(TranscriptEntry.flagged(START, "oh frell", FlagSeverity.MILD), lines.get(1));
        verify(engine, never()).sendVerifyRequest(any(), anyLong(), anyString(), any());
    }

    @Test
    void liveRejectionIsReverifiedOnceByTheFinalPass() {
        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "you sore backs"));
        pipeline.onTranscript(live(speakerId, "you zorblax now"));
        verify(engine, times(1)).sendVerifyRequest(any(), anyLong(), anyString(), any());

        short[] full = {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9};
        pipeline.onFinalUtterance(new FinalUtterance(speakerId, UTTERANCE, full, 200));
        pipeline.onTranscript(finalResult(speakerId, "you zorblax now"));
        verify(engine).sendVerifyRequest(speakerId, UTTERANCE, "zorblax", full);

        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "you zorblax now"));
        assertTrue(mutes.isMuted(speakerId));
        pipeline.onTranscript(finalResult(speakerId, "you zorblax now"));
        verify(engine, times(2)).sendVerifyRequest(any(), anyLong(), anyString(), any());
    }

    @Test
    void hitWithoutAudioIsNeverPunishedUnverified() {
        pipeline.onTranscript(live(speakerId, "zorblax"));

        verify(engine, never()).sendVerifyRequest(any(), anyLong(), anyString(), any());
        assertFalse(mutes.isMuted(speakerId));
        assertEquals(1, pipeline.status().unconfirmed());
        assertTrue(said(staff).stream().anyMatch(line -> line.contains("unconfirmed")));
    }

    @Test
    void debugViewersSeeTranscriptsUntilTheyToggleOff() {
        CommandSender console = mock(CommandSender.class);
        assertTrue(pipeline.toggleDebug(console, speakerId));
        assertTrue(pipeline.toggleDebug(staff, speakerId));
        pipeline.onTranscript(live(speakerId, "frell"));

        List<String> consoleLines = said(console);
        assertTrue(consoleLines.getFirst().contains("DEBUG live 120ms"));
        assertTrue(consoleLines.getFirst().contains("matched: frell"));
        assertTrue(said(staff).stream().anyMatch(line -> line.contains("DEBUG live")));

        assertFalse(pipeline.toggleDebug(console, speakerId));
        pipeline.removeViewer(staff.getUniqueId());
        assertEquals(0, pipeline.status().debugTargets());
    }

    @Test
    void liveAudioIsCappedAtTheUtteranceLimit() {
        VoiceWardenConfig config = config(true);
        pipeline.update(new VoiceModerationPipeline.Settings(true, "", 10, "server-1", MicSpamConfig.defaults()), pipeline.services());
        pipeline.onLiveChunk(new LiveAudioChunk(speakerId, UTTERANCE, 0, pcm, false));
        pipeline.onLiveChunk(new LiveAudioChunk(speakerId, UTTERANCE, 1, pcm, false));
        pipeline.onTranscript(live(speakerId, "zorblax"));

        ArgumentCaptor<short[]> sent = ArgumentCaptor.forClass(short[].class);
        verify(engine).sendVerifyRequest(eq(speakerId), eq(UTTERANCE), eq("zorblax"), sent.capture());
        assertEquals(10, sent.getValue().length);
        assertTrue(config.audio().maxUtteranceSamples16k() > 10);
    }

    @Test
    void finalUtteranceReplacesLiveAudioAndIsForwarded() {
        speak();
        assertEquals(1, pipeline.status().liveBuffers());
        FinalUtterance utterance = new FinalUtterance(speakerId, UTTERANCE, pcm, 100);
        pipeline.onFinalUtterance(utterance);

        verify(engine).sendFinalUtterance(utterance, false);
        assertEquals(0, pipeline.status().liveBuffers());
        assertEquals(1, pipeline.status().cachedUtterances());

        pipeline.onPlayerQuit(speakerId);
        assertEquals(0, pipeline.status().cachedUtterances());
    }

    @Test
    void expiredAudioIsSweptSoMemoryDoesNotLeak() {
        speak();
        pipeline.onFinalUtterance(new FinalUtterance(UUID.randomUUID(), 1L, pcm, 100));
        clock.advance(VoiceModerationPipeline.FINAL_AUDIO_TTL.plusSeconds(1));
        pipeline.sweep();

        assertEquals(0, pipeline.status().liveBuffers());
        assertEquals(0, pipeline.status().cachedUtterances());
    }

    @Test
    void unrelatedVerifierHitDoesNotAuthorizeSeverePunishment() {
        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "you frell"));

        assertFalse(mutes.isMuted(speakerId));
        assertEquals(1, pipeline.status().unconfirmed());
        assertEquals(0, strikes.countRecentStrikes(speakerId, START.minusSeconds(60)));
    }

    @Test
    void partialVerifierCorroborationPunishesOnlyCorroboratedSeverity() {
        speak();
        pipeline.onTranscript(live(speakerId, "frell zorblax"));
        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "only frell here"));

        assertFalse(mutes.isMuted(speakerId));
        VoiceFlagEvent flagEvent = eventsOf(VoiceFlagEvent.class).getFirst();
        assertEquals(FlagSeverity.MILD, flagEvent.getSeverity());
        assertEquals(List.of("frell"), flagEvent.getMatchedWords());
    }

    @Test
    void finalArrivingWhileLiveVerifyIsPendingStillRetriesWhenLiveIsRejected() {
        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        short[] full = {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9};
        pipeline.onFinalUtterance(new FinalUtterance(speakerId, UTTERANCE, full, 200));
        pipeline.onTranscript(finalResult(speakerId, "you zorblax"));
        verify(engine, times(1)).sendVerifyRequest(any(), anyLong(), anyString(), any());

        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "you sore backs"));
        verify(engine).sendVerifyRequest(speakerId, UTTERANCE, "zorblax", full);

        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "you zorblax"));
        assertTrue(mutes.isMuted(speakerId));
    }

    @Test
    void lateLiveVerifyResponseAfterTimeoutDoesNotSettleNewerFinalVerify() {
        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        clock.advance(VoiceModerationPipeline.VERIFY_TIMEOUT.plusSeconds(1));
        pipeline.sweep();
        assertFalse(mutes.isMuted(speakerId));

        short[] full = {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9};
        pipeline.onFinalUtterance(new FinalUtterance(speakerId, UTTERANCE, full, 200));
        pipeline.onTranscript(finalResult(speakerId, "you zorblax"));
        assertEquals(1, pipeline.status().pendingVerifications());

        pipeline.onTranscript(verification(speakerId, ConfirmationState.UNCONFIRMED, "late live miss"));
        assertEquals(1, pipeline.status().pendingVerifications());

        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "you zorblax"));
        assertEquals(0, pipeline.status().pendingVerifications());
        assertTrue(mutes.isMuted(speakerId));
    }

    @Test
    void quitWhileVerifyIsInFlightStillPreservesAudioForDiscordWebhooks() {
        speak();
        pipeline.onTranscript(live(speakerId, "you zorblax"));
        pipeline.onPlayerQuit(speakerId);

        pipeline.onTranscript(verification(speakerId, ConfirmationState.CONFIRMED, "you zorblax"));

        ArgumentCaptor<MuteWebhookPayload> mute = ArgumentCaptor.forClass(MuteWebhookPayload.class);
        verify(discord).sendMute(mute.capture());
        assertEquals(pcm.length, mute.getValue().pcm16kAudio().length);
    }

    @Test
    void entitySchedulerRetiredCallbackStillModeratesPlayer() {
        ManualScheduler retiringScheduler = new ManualScheduler() {
            @Override
            public dev.voicewarden.plugin.scheduler.TaskHandle runForEntity(
                    org.bukkit.entity.Entity entity, Runnable task, Runnable retired) {
                if (retired != null) {
                    retired.run();
                }
                return new dev.voicewarden.plugin.scheduler.TaskHandle() {
                    @Override
                    public void cancel() {
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                };
            }
        };
        VoiceWardenConfig cfg = config(false);
        NativeMuteService localMutes = new NativeMuteService(cfg.mutes(), storage, retiringScheduler, players, TestMessages.renderer(),
                events::add, MuteMirror.NONE, strikeCounts, id -> true, clock, "server-1", Logger.getLogger("test"));
        WordlistConfig words = WordlistConfig.defaults();
        WordlistRegistry registry = new WordlistRegistry(AhoCorasickMatcher.compile(WordlistParser.parse(WORDLIST, words), words));
        VoiceModerationPipeline localPipeline = new VoiceModerationPipeline(
                VoiceModerationPipeline.Settings.from(cfg),
                new VoiceModerationPipeline.Services(ConfigBridges.moderation(cfg, strikes), discord),
                retiringScheduler, players, TestMessages.renderer(), registry, transcripts,
                new SpeakerContextTracker(transcripts, cfg.transcripts(), clock), strikeCounts, localMutes, events::add,
                events::add, () -> false, consoleCommands::add, clock, Logger.getLogger("test"));
        localPipeline.onLiveChunk(new LiveAudioChunk(speakerId, UTTERANCE, 0, pcm, false));
        localPipeline.onTranscript(live(speakerId, "zorblax"));

        assertTrue(localMutes.isMuted(speakerId));
        assertEquals(1, eventsOf(VoiceFlagEvent.class).size());
    }

    private static VoiceWardenConfig micSpamConfig(String escalation, boolean enabled, boolean notifyDiscord) {
        String yaml = ConfigFiles.defaultConfigTemplate()
                .replace("    escalation: [warn, mute]\n", "    escalation: " + escalation + "\n")
                .replace("  mic_spam:\n    enabled: true\n", "  mic_spam:\n    enabled: " + enabled + "\n")
                .replace("    notify_discord: true\n", "    notify_discord: " + notifyDiscord + "\n");
        VoiceWardenConfig config = ConfigLoader.loadConfig(yaml).value();
        assertEquals(enabled, config.audio().micSpam().enabled());
        assertEquals(notifyDiscord, config.audio().micSpam().notifyDiscord());
        return config;
    }

    private AcousticAbuseEvent earrape() {
        return new AcousticAbuseEvent(speakerId, AcousticAbuseEvent.Kind.CLIPPING, 31_000, 0.42, 800, pcm);
    }

    @Test
    void micSpamWarnAlertsStaffWarnsThePlayerAndPostsTheClipToDiscord() {
        pipeline.onAcousticAbuse(earrape());

        assertFalse(mutes.isMuted(speakerId));
        assertTrue(said(staff).stream().anyMatch(line -> line.equals("MIC SPAM! Speaker is clipping or too loud. · clipping · warn")),
                said(staff).toString());
        assertTrue(said(speaker).stream().anyMatch(line -> line.contains("TOO LOUD!") && line.contains("microphone")),
                said(speaker).toString());
        ArgumentCaptor<MicSpamWebhookPayload> payload = ArgumentCaptor.forClass(MicSpamWebhookPayload.class);
        verify(discord).sendMicSpam(payload.capture());
        assertEquals("Speaker", payload.getValue().playerName());
        assertEquals(VoiceAction.WARN, payload.getValue().actionTaken());
        assertEquals(AcousticAbuseEvent.Kind.CLIPPING, payload.getValue().kind());
        assertEquals(pcm.length, payload.getValue().pcm16kAudio().length);
        verify(discord, never()).sendMute(any());
        verify(discord, never()).sendFlag(any());
        assertEquals(1, pipeline.status().flags());
    }

    @Test
    void micSpamDefaultEscalationWarnsFirstThenMutesOnRepeatWithinTheWindow() {
        pipeline.onAcousticAbuse(earrape());
        assertFalse(mutes.isMuted(speakerId));
        assertTrue(said(speaker).stream().anyMatch(line -> line.contains("TOO LOUD!")));

        clock.advance(Duration.ofSeconds(16));
        pipeline.sweep();
        pipeline.onAcousticAbuse(earrape());

        assertTrue(mutes.isMuted(speakerId));
        assertEquals(VoiceModerationPipeline.MIC_SPAM_MUTE_REASON, mutes.getActiveMute(speakerId).orElseThrow().reason());
        verify(discord, times(2)).sendMicSpam(any());
        verify(discord, times(1)).sendMute(any());
    }

    @Test
    void micSpamEscalationWindowResetsAfterQuietPeriod() {
        pipeline.onAcousticAbuse(earrape());
        assertFalse(mutes.isMuted(speakerId));

        clock.advance(Duration.ofMinutes(6));
        pipeline.sweep();
        pipeline.onAcousticAbuse(earrape());

        assertFalse(mutes.isMuted(speakerId));
        assertEquals(2, said(speaker).stream().filter(line -> line.contains("TOO LOUD!")).count());
        verify(discord, never()).sendMute(any());
    }

    @Test
    void micSpamReportOnlyAlertsStaff() {
        build(micSpamConfig("[report]", true, true));

        pipeline.onAcousticAbuse(earrape());

        assertTrue(said(staff).stream().anyMatch(line -> line.contains("MIC SPAM!") && line.endsWith("report")));
        verify(speaker, never()).sendMessage(any(Component.class));
        assertFalse(mutes.isMuted(speakerId));
        verify(discord).sendMicSpam(any());
    }

    @Test
    void micSpamMuteUsesTheConfiguredDurationAndReasonAndPostsTheMuteWebhook() {
        build(micSpamConfig("[mute]", true, true));

        pipeline.onAcousticAbuse(earrape());

        assertTrue(mutes.isMuted(speakerId));
        assertEquals(VoiceModerationPipeline.MIC_SPAM_MUTE_REASON, mutes.getActiveMute(speakerId).orElseThrow().reason());
        assertEquals(VoiceModerationPipeline.AUTO_MODERATOR, mutes.getActiveMute(speakerId).orElseThrow().moderator());
        assertEquals(START.plus(Duration.ofMinutes(5)), mutes.getActiveMute(speakerId).orElseThrow().expiresAt());
        assertTrue(said(staff).stream().anyMatch(line -> line.contains("MUTED") && line.contains("Speaker")));
        ArgumentCaptor<MuteWebhookPayload> mute = ArgumentCaptor.forClass(MuteWebhookPayload.class);
        verify(discord).sendMute(mute.capture());
        assertEquals(VoiceModerationPipeline.MIC_SPAM_MUTE_REASON, mute.getValue().mute().reason());
        assertEquals(pcm.length, mute.getValue().pcm16kAudio().length);
        verify(discord).sendMicSpam(any());
    }

    @Test
    void micSpamCooldownActsOncePerBurstEvenIfTheAudioSessionWasRebuilt() {
        build(micSpamConfig("[warn]", true, true));
        pipeline.onAcousticAbuse(earrape());
        pipeline.onAcousticAbuse(earrape());
        clock.advance(Duration.ofSeconds(14));
        pipeline.sweep();
        pipeline.onAcousticAbuse(earrape());

        verify(discord, times(1)).sendMicSpam(any());

        clock.advance(Duration.ofSeconds(1));
        pipeline.sweep();
        pipeline.onAcousticAbuse(earrape());

        verify(discord, times(2)).sendMicSpam(any());
        assertEquals(2, said(speaker).stream().filter(line -> line.contains("TOO LOUD!")).count());
    }

    @Test
    void micSpamFromAnAlreadyMutedPlayerIsIgnored() {
        mutes.mutePlayer(speakerId, "Speaker", Duration.ofMinutes(10), "earlier", "Staff").join();

        pipeline.onAcousticAbuse(earrape());

        verify(discord, never()).sendMicSpam(any());
        verify(staff, never()).sendMessage(any(Component.class));
        assertEquals("earlier", mutes.getActiveMute(speakerId).orElseThrow().reason());
    }

    @Test
    void micSpamDisabledDoesNothingAndDiscordCanBeTurnedOff() {
        build(micSpamConfig("[warn]", false, true));
        pipeline.onAcousticAbuse(earrape());
        verify(discord, never()).sendMicSpam(any());
        verify(staff, never()).sendMessage(any(Component.class));

        build(micSpamConfig("[warn]", true, false));
        pipeline.onAcousticAbuse(earrape());
        verify(discord, never()).sendMicSpam(any());
        assertTrue(said(staff).stream().anyMatch(line -> line.contains("MIC SPAM!")));
    }
}
