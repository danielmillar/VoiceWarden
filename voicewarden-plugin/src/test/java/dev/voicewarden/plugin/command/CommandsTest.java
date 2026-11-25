package dev.voicewarden.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.core.audio.AudioPipelineStats;
import dev.voicewarden.core.config.ConfigLoader;
import dev.voicewarden.core.config.VoiceReportConfig;
import dev.voicewarden.core.config.VoiceWardenConfig;
import dev.voicewarden.core.discord.DiscordWebhookClient;
import dev.voicewarden.core.discord.MuteWebhookPayload;
import dev.voicewarden.core.discord.TestTarget;
import dev.voicewarden.core.discord.WebhookKind;
import dev.voicewarden.core.discord.WebhookResult;
import dev.voicewarden.core.moderation.ModerationEngine;
import dev.voicewarden.core.storage.InMemoryFallbackStore;
import dev.voicewarden.core.storage.StorageProvider;
import dev.voicewarden.core.storage.StrikeRecord;
import dev.voicewarden.core.wordlist.AhoCorasickMatcher;
import dev.voicewarden.core.wordlist.WordlistConfig;
import dev.voicewarden.core.wordlist.WordlistParser;
import dev.voicewarden.plugin.FakePlayers;
import dev.voicewarden.plugin.ManualScheduler;
import dev.voicewarden.plugin.MutableClock;
import dev.voicewarden.plugin.TestMessages;
import dev.voicewarden.plugin.consent.ConsentManager;
import dev.voicewarden.plugin.mute.KnownPlayer;
import dev.voicewarden.plugin.mute.MuteMirror;
import dev.voicewarden.plugin.mute.NativeMuteService;
import dev.voicewarden.plugin.mute.StrikeCountCache;
import dev.voicewarden.plugin.pipeline.ConfigBridges;
import dev.voicewarden.plugin.pipeline.StorageStrikePersistence;
import dev.voicewarden.plugin.pipeline.VoiceModerationPipeline;
import dev.voicewarden.plugin.report.VoiceReportManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CommandsTest {

    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakePlayers players = new FakePlayers();
    private final InMemoryFallbackStore storage = new InMemoryFallbackStore("server-1");
    private final VoiceWardenConfig config = ConfigLoader.defaultConfig();
    private final DiscordWebhookClient discord = mock(DiscordWebhookClient.class);
    private final VoiceModerationPipeline pipeline = mock(VoiceModerationPipeline.class);
    private final UpdateChecker updates = mock(UpdateChecker.class);
    private final ConsentManager consent = mock(ConsentManager.class);
    private final VoiceReportManager reports = mock(VoiceReportManager.class);
    private final Command command = mock(Command.class);
    private final Player admin = players.join("Admin");
    private final Player target = players.join("Target");
    private final Player regular = players.join("Regular");
    private final CommandSender console = mock(CommandSender.class);
    private final List<String> backends = new ArrayList<>(List.of("Simple Voice Chat"));

    private AdminRuntime.ReloadResult reloadResult = AdminRuntime.ReloadResult.ok(List.of());
    private NativeMuteService mutes;
    private ModerationEngine moderation;
    private VoiceWardenCommand vw;

    @BeforeEach
    void setUp() {
        lenient().when(admin.hasPermission(Replies.ADMIN_PERMISSION)).thenReturn(true);
        lenient().when(console.hasPermission(any(String.class))).thenReturn(true);
        lenient().when(console.getName()).thenReturn("CONSOLE");
        lenient().when(discord.sendMute(any())).thenReturn(CompletableFuture.completedFuture(WebhookResult.skipped(WebhookKind.MUTE, "off")));
        lenient().when(discord.sendTest(any())).thenAnswer(call -> CompletableFuture.completedFuture(WebhookResult.combined("test",
                List.of(WebhookResult.sent(WebhookKind.FLAG, 204, 1), WebhookResult.skipped(WebhookKind.MUTE, "mute webhook is disabled")))));
        lenient().when(pipeline.status()).thenReturn(new VoiceModerationPipeline.Status(true, "ENGLISH", "1.0", 12.5, true,
                1, 2, 0, 10, 3, 1, 1, 0));
        StrikeCountCache strikes = new StrikeCountCache((id, since) -> storage.countActiveStrikes(id, since, true), Runnable::run,
                clock, Duration.ofMinutes(10), Duration.ofSeconds(30));
        mutes = new NativeMuteService(config.mutes(), storage, scheduler, players, TestMessages.renderer(), event -> { }, MuteMirror.NONE,
                strikes, id -> true, clock, "server-1", Logger.getLogger("test"));
        moderation = ConfigBridges.moderation(config, new StorageStrikePersistence(storage, "server-1", true));
        vw = new VoiceWardenCommand(runtime(), players, TestMessages.renderer(), scheduler, clock, Logger.getLogger("test"));
    }

    private AdminRuntime runtime() {
        WordlistConfig words = WordlistConfig.defaults();
        AhoCorasickMatcher matcher = AhoCorasickMatcher.compile(WordlistParser.parse("[EN-MUTE]\nzorblax\n", words), words);
        return new AdminRuntime() {
            @Override
            public NativeMuteService mutes() {
                return mutes;
            }

            @Override
            public ModerationEngine moderation() {
                return moderation;
            }

            @Override
            public DiscordWebhookClient discord() {
                return discord;
            }

            @Override
            public StorageProvider storage() {
                return storage;
            }

            @Override
            public StorageStatus storageStatus() {
                return new StorageStatus("SQLITE", true, 0, "");
            }

            @Override
            public VoiceModerationPipeline pipeline() {
                return pipeline;
            }

            @Override
            public Optional<AudioPipelineStats> audioStats() {
                return Optional.of(new AudioPipelineStats(100, 100, 0, 0, 5, 1, 1, 0, 0, 0, 0, 0, 0, 0));
            }

            @Override
            public String engineState() {
                return "running";
            }

            @Override
            public List<String> voiceBackends() {
                return backends;
            }

            @Override
            public AhoCorasickMatcher wordlist() {
                return matcher;
            }

            @Override
            public Duration defaultMuteDuration() {
                return Duration.ofMinutes(60);
            }

            @Override
            public UpdateChecker updates() {
                return updates;
            }

            @Override
            public ConsentManager consent() {
                return consent;
            }

            @Override
            public ReloadResult reload() {
                return reloadResult;
            }
        };
    }

    private static List<String> said(CommandSender sender) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    private static String last(CommandSender sender) {
        List<String> lines = said(sender);
        return lines.getLast();
    }

    private void run(CommandSender sender, String... args) {
        vw.onCommand(sender, command, "vw", args);
    }

    @Test
    void helpAndUnknownSubcommands() {
        run(regular);
        assertTrue(said(regular).getFirst().contains("VoiceWarden"));
        run(regular, "nope");
        assertTrue(last(regular).contains("Unknown subcommand"));
    }

    @Test
    void staffCommandsNeedTheirPermissionOrAdmin() {
        for (String sub : List.of("reload", "stats", "vcmute", "unvcmute", "pardon", "history", "mutes", "discordtest", "test", "debug", "consent", "updatecheck")) {
            clearInvocations(regular);
            run(regular, sub, "Target");
            assertTrue(last(regular).contains("HOLD UP! You can't do that."), sub);
        }
        when(regular.hasPermission("voicewarden.mutes")).thenReturn(true);
        run(regular, "mutes");
        assertTrue(last(regular).contains("Nobody is voice-muted."));
        when(regular.hasPermission("voicewarden.stats")).thenReturn(true);
        run(regular, "stats");
        assertTrue(said(regular).stream().anyMatch(line -> line.contains("Engine: connected, profile ENGLISH")));
    }

    @Test
    void vcmuteMutesOnlinePlayersAndSendsTheMuteWebhook() {
        run(admin, "vcmute", "Target", "30m", "being", "rude");

        assertTrue(mutes.isMuted(target.getUniqueId()));
        assertEquals("being rude", mutes.getActiveMute(target.getUniqueId()).orElseThrow().reason());
        assertEquals("Admin", mutes.getActiveMute(target.getUniqueId()).orElseThrow().moderator());
        assertEquals(START.plus(Duration.ofMinutes(30)), mutes.getActiveMute(target.getUniqueId()).orElseThrow().expiresAt());
        assertTrue(last(admin).contains("Voice-muted Target for 30m."));
        ArgumentCaptor<MuteWebhookPayload> payload = ArgumentCaptor.forClass(MuteWebhookPayload.class);
        verify(discord).sendMute(payload.capture());
        assertFalse(payload.getValue().isUnmute());

        run(admin, "vcmute", "Target");
        assertTrue(last(admin).contains("already voice-muted"));
    }

    @Test
    void vcmuteAcceptsOfflineNamesBedrockPrefixesUuidsAndPermanentMutes() {
        KnownPlayer offline = players.addOffline("Sleeper");
        run(console, "vcmute", "Sleeper");
        assertEquals(START.plus(Duration.ofMinutes(60)), mutes.getActiveMute(offline.id()).orElseThrow().expiresAt());

        KnownPlayer bedrock = players.addOffline(".Pocket");
        run(console, "vcmute", "Pocket", "perm");
        assertTrue(mutes.getActiveMute(bedrock.id()).orElseThrow().isPermanent());

        run(console, "vcmute", regular.getUniqueId().toString(), "1h");
        assertTrue(mutes.isMuted(regular.getUniqueId()));

        run(console, "vcmute", "Nobody");
        assertTrue(last(console).contains("No player called Nobody was found."));
        run(console, "vcmute", "Target", "soon");
        assertTrue(last(console).contains("soon is not a duration"));
        assertFalse(mutes.isMuted(target.getUniqueId()));
    }

    @Test
    void unvcmuteWorksOfflineAndSendsTheUnmuteWebhook() {
        KnownPlayer offline = players.addOffline("Sleeper");
        mutes.mutePlayer(offline.id(), offline.name(), Duration.ofHours(1), "test", "Admin");

        run(console, "unvcmute", "Sleeper");
        assertFalse(mutes.isMuted(offline.id()));
        assertTrue(last(console).contains("Removed the voice mute from Sleeper."));
        ArgumentCaptor<MuteWebhookPayload> payload = ArgumentCaptor.forClass(MuteWebhookPayload.class);
        verify(discord).sendMute(payload.capture());
        assertTrue(payload.getValue().isUnmute());
        assertEquals("CONSOLE", payload.getValue().unmutedBy());

        run(console, "unvcmute", "Sleeper");
        assertTrue(last(console).contains("is not voice-muted"));
    }

    @Test
    void pardonClearsStrikesAndUnmutes() {
        storage.recordStrike(StrikeRecord.unsaved(target.getUniqueId(), "Target", FlagSeverity.SEVERE, "zorblax", "zorblax", START, "server-1"));
        mutes.mutePlayer(target.getUniqueId(), "Target", Duration.ofHours(1), "test", "Admin");

        run(admin, "pardon", "Target");

        assertFalse(mutes.isMuted(target.getUniqueId()));
        assertEquals(0, storage.countActiveStrikes(target.getUniqueId(), START.minusSeconds(60), true));
        assertTrue(said(admin).stream().anyMatch(line -> line.contains("Pardoned Target")));
        verify(discord).sendMute(any());
    }

    @Test
    void historyListsPastMutes() {
        mutes.mutePlayer(target.getUniqueId(), "Target", Duration.ofMinutes(15), "first offence", "Admin");
        mutes.unmutePlayer(target.getUniqueId(), "Admin");
        mutes.mutePlayer(target.getUniqueId(), "Target", null, "second offence", "Admin");

        run(console, "history", "Target", "5");
        List<String> lines = said(console);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Target") && line.contains("2 mutes shown")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Muted for 15m by Admin: first offence")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Muted for good by Admin: second offence · active")));

        run(console, "history", "Target", "lots");
        assertTrue(last(console).contains("lots is not a number"));
        run(console, "history", "Regular");
        assertTrue(last(console).contains("No voice mutes recorded for Regular"));
    }

    @Test
    void mutesListsActiveMutes() {
        mutes.mutePlayer(target.getUniqueId(), "Target", Duration.ofMinutes(15), "rude", "Admin");
        run(console, "mutes");
        List<String> lines = said(console);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Voice mutes") && line.contains("Muted: 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Target:") && line.contains("rude, by Admin")));
    }

    @Test
    void discordtestReportsEveryPart() {
        run(console, "discordtest", "flag");
        verify(discord).sendTest(TestTarget.FLAG);
        List<String> lines = said(console);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Discord webhook test finished")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("mute webhook is disabled")));

        run(console, "discordtest", "everything");
        assertTrue(last(console).contains("Usage: /vw discordtest <all|flag|mute>"));
        run(console, "discordtest");
        assertTrue(last(console).contains("Usage"));
    }

    @Test
    void selfTestChecksEverySubsystemAndCanTestWebhooks() {
        backends.clear();
        run(console, "test", "webhook");
        List<String> lines = said(console);
        assertTrue(lines.contains(" ✔ Speech engine: connected, profile ENGLISH, version 1.0, 12.5 ms latency"));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(" ✔ Audio pipeline:")));
        assertTrue(lines.contains(" ✔ Storage: SQLITE connected"));
        assertTrue(lines.contains(" ✘ Voice chat: no Simple Voice Chat or PlasmoVoice hook is active"));
        assertTrue(lines.contains(" ✔ Word list: 1 flagged patterns, 0 allowed patterns"));
        verify(discord).sendTest(TestTarget.ALL);
    }

    @Test
    void debugTogglesTheStream() {
        when(pipeline.toggleDebug(admin, target.getUniqueId())).thenReturn(true, false);
        run(admin, "debug", "Target");
        assertTrue(last(admin).contains("Streaming live transcripts for Target"));
        run(admin, "debug", "Target");
        assertTrue(last(admin).contains("Stopped streaming transcripts for Target"));
        run(admin, "debug");
        assertTrue(last(admin).contains("Usage: /vw debug <player>"));
    }

    @Test
    void consentAdminCommandInspectsAndResets() {
        when(consent.placeholderStatus(target.getUniqueId())).thenReturn("denied");
        when(consent.reset(target.getUniqueId())).thenReturn(CompletableFuture.completedFuture(null));

        run(admin, "consent", "Target");
        assertTrue(last(admin).contains("Target's voice consent is denied."));

        run(admin, "consent", "Target", "reset");
        verify(consent).reset(target.getUniqueId());
        assertTrue(last(admin).contains("Reset voice consent for Target."));

        run(admin, "consent", "Target", "invalid");
        assertTrue(last(admin).contains("Usage: /vw consent <player> [reset]"));
    }

    @Test
    void updatecheckReportsTheResult() {
        when(updates.check()).thenReturn(CompletableFuture.completedFuture(
                new UpdateChecker.Result(UpdateChecker.Status.UPDATE_AVAILABLE, "1.0.0", "1.1.0", "")));
        run(console, "updatecheck");
        assertTrue(last(console).contains("VoiceWarden 1.1.0 is available.") && last(console).contains("You are running 1.0.0."));
        when(updates.check()).thenReturn(CompletableFuture.completedFuture(
                new UpdateChecker.Result(UpdateChecker.Status.FAILED, "1.0.0", "", "offline")));
        run(console, "updatecheck");
        assertTrue(last(console).contains("OOPS! The update check failed.") && last(console).contains("offline"));
    }

    @Test
    void reloadReportsSuccessNotesAndFailures() {
        reloadResult = AdminRuntime.ReloadResult.ok(List.of("Restart the server to connect with them."));
        run(console, "reload");
        List<String> lines = said(console);
        assertTrue(lines.contains("RELOADED! Configuration reloaded."));
        assertTrue(lines.contains("NOTE Restart the server to connect with them."));

        reloadResult = AdminRuntime.ReloadResult.failed("bad yaml");
        run(console, "reload");
        assertTrue(last(console).contains("OOPS! The reload failed") && last(console).contains("bad yaml"));
    }

    @Test
    void tabCompletionFollowsPermissions() {
        assertEquals(List.of("help"), vw.onTabComplete(regular, command, "vw", new String[] {""}));
        List<String> all = vw.onTabComplete(admin, command, "vw", new String[] {""});
        assertEquals(13, all.size());
        assertEquals(List.of("debug", "discordtest"), vw.onTabComplete(admin, command, "vw", new String[] {"d"}));
        assertEquals(List.of("reset"), vw.onTabComplete(admin, command, "vw", new String[] {"consent", "Target", ""}));
        assertEquals(List.of("Target"), vw.onTabComplete(admin, command, "vw", new String[] {"vcmute", "ta"}));
        assertEquals(List.of("all", "flag", "mute"), vw.onTabComplete(admin, command, "vw", new String[] {"discordtest", ""}));
        assertEquals(List.of("webhook"), vw.onTabComplete(admin, command, "vw", new String[] {"test", ""}));
        assertTrue(vw.onTabComplete(admin, command, "vw", new String[] {"vcmute", "Target", ""}).contains("perm"));
        assertEquals(List.of(), vw.onTabComplete(regular, command, "vw", new String[] {"vcmute", ""}));

        mutes.mutePlayer(target.getUniqueId(), "Target", Duration.ofMinutes(5), "x", "Admin");
        assertEquals(List.of("Target"), vw.onTabComplete(admin, command, "vw", new String[] {"unvcmute", ""}));
    }

    @Test
    void voiceConsentNeedsNoPermissionAndCompletesChoices() {
        VoiceConsentCommand consentCommand = new VoiceConsentCommand(consent, TestMessages.renderer(), scheduler);
        consentCommand.onCommand(regular, command, "vconsent", new String[] {"accept"});
        verify(consent).onCommand(regular, "accept");
        consentCommand.onCommand(regular, command, "vconsent", new String[0]);
        verify(consent).onCommand(regular, null);
        consentCommand.onCommand(console, command, "vconsent", new String[0]);
        assertTrue(last(console).contains("Only players"));
        assertEquals(List.of("accept", "deny"), consentCommand.onTabComplete(regular, command, "vconsent", new String[] {""}));
        assertEquals(List.of("deny"), consentCommand.onTabComplete(regular, command, "vconsent", new String[] {"d"}));
    }

    @Test
    void reportVoiceSupportsGuiAndCommandModes() {
        when(regular.hasPermission(ReportVoiceCommand.PERMISSION)).thenReturn(true);
        when(reports.config()).thenReturn(reportConfig(VoiceReportConfig.Mode.COMMAND));
        ReportVoiceCommand report = new ReportVoiceCommand(reports, mutes::resolveTarget, players, TestMessages.renderer(), scheduler);
        KnownPlayer targetKnown = new KnownPlayer(target.getUniqueId(), "Target");

        report.onCommand(regular, command, "rvoice", new String[0]);
        verify(reports).openReportMenu(regular);
        report.onCommand(regular, command, "rvoice", new String[] {"Target", "10"});
        verify(reports).submit(regular, targetKnown, 10);
        report.onCommand(regular, command, "rvoice", new String[] {"Target"});
        verify(reports).submit(eq(regular), eq(targetKnown), isNull());
        report.onCommand(regular, command, "rvoice", new String[] {"Target", "ten"});
        assertTrue(last(regular).contains("ten is not a number"));
        report.onCommand(regular, command, "rvoice", new String[] {"Ghost"});
        assertTrue(last(regular).contains("No player called Ghost was found."));

        when(reports.config()).thenReturn(reportConfig(VoiceReportConfig.Mode.GUI));
        report.onCommand(regular, command, "rvoice", new String[] {"Target"});
        verify(reports).openWindowMenu(regular, targetKnown);

        assertEquals(List.of("Admin", "Target"), report.onTabComplete(regular, command, "rvoice", new String[] {""}));
        assertEquals(List.of("1", "10", "15"), report.onTabComplete(regular, command, "rvoice", new String[] {"Target", "1"}));

        Player stranger = players.join("Stranger");
        report.onCommand(stranger, command, "rvoice", new String[] {"Target"});
        assertTrue(last(stranger).contains("HOLD UP! You can't do that."));
        report.onCommand(console, command, "rvoice", new String[] {"Target"});
        assertTrue(last(console).contains("Only players"));
    }

    @Test
    void viewReportOpensPlayersAndReportIdsIncludingFallbackIds() {
        when(reports.viewReport(any(), anyLong())).thenReturn(CompletableFuture.completedFuture(true));
        when(regular.hasPermission(VoiceReportManager.ALERTS_PERMISSION)).thenReturn(true);
        ViewReportCommand view = new ViewReportCommand(reports, mutes::resolveTarget, players, TestMessages.renderer(), scheduler);
        KnownPlayer targetKnown = new KnownPlayer(target.getUniqueId(), "Target");

        view.onCommand(regular, command, "vr", new String[] {"Target", "10"});
        verify(reports).viewTranscript(regular, targetKnown, 10);
        view.onCommand(regular, command, "vr", new String[] {"Target"});
        verify(reports).viewTranscript(eq(regular), eq(targetKnown), isNull());
        view.onCommand(regular, command, "vr", new String[] {"#5"});
        verify(reports).viewReport(regular, 5L);
        view.onCommand(regular, command, "vr", new String[] {"-3"});
        verify(reports).viewReport(regular, -3L);
        view.onCommand(regular, command, "vr", new String[] {"#-4"});
        verify(reports).viewReport(regular, -4L);
        view.onCommand(regular, command, "vr", new String[] {"12"});
        verify(reports).viewReport(regular, 12L);

        KnownPlayer numeric = players.addOffline("1234");
        view.onCommand(regular, command, "vr", new String[] {"1234"});
        verify(reports).viewTranscript(eq(regular), eq(numeric), isNull());
        verify(reports, never()).viewReport(regular, 1234L);

        when(reports.viewReport(regular, 99L)).thenReturn(CompletableFuture.completedFuture(false));
        view.onCommand(regular, command, "vr", new String[] {"#99"});
        assertTrue(last(regular).contains("Voice report #99 was not found"));
        view.onCommand(regular, command, "vr", new String[] {"#abc"});
        assertTrue(last(regular).contains("#abc is not a number"));
        view.onCommand(regular, command, "vr", new String[0]);
        assertTrue(last(regular).contains("Usage: /viewreport"));

        Player stranger = players.join("Stranger");
        view.onCommand(stranger, command, "vr", new String[] {"Target"});
        assertTrue(last(stranger).contains("HOLD UP! You can't do that."));
        assertEquals(List.of(), view.onTabComplete(stranger, command, "vr", new String[] {""}));
        assertEquals(List.of("Target"), view.onTabComplete(regular, command, "vr", new String[] {"T"}));
    }

    @Test
    void reportInboxListsAndMarksReportsIncludingFallbackIds() {
        when(reports.markReviewed(any(CommandSender.class), anyLong())).thenReturn(CompletableFuture.completedFuture(true));
        ReportInboxCommand inbox = new ReportInboxCommand(reports, TestMessages.renderer(), scheduler);

        inbox.onCommand(console, command, "rinbox", new String[0]);
        verify(reports).openInbox(console);
        inbox.onCommand(console, command, "rinbox", new String[] {"review", "-2"});
        verify(reports).markReviewed(console, -2L);
        inbox.onCommand(console, command, "rinbox", new String[] {"review", "#7"});
        verify(reports).markReviewed(console, 7L);

        when(reports.markReviewed(console, 8L)).thenReturn(CompletableFuture.completedFuture(false));
        inbox.onCommand(console, command, "rinbox", new String[] {"review", "8"});
        assertTrue(last(console).contains("#8 was not found or is already reviewed"));
        inbox.onCommand(console, command, "rinbox", new String[] {"review", "x"});
        assertTrue(last(console).contains("x is not a number"));
        inbox.onCommand(console, command, "rinbox", new String[] {"open"});
        assertTrue(last(console).contains("Usage: /reportinbox [review <id>]"));

        inbox.onCommand(regular, command, "rinbox", new String[0]);
        assertTrue(last(regular).contains("HOLD UP! You can't do that."));
        verify(reports, never()).openInbox(regular);
        assertEquals(List.of("review"), inbox.onTabComplete(console, command, "rinbox", new String[] {"r"}));
        assertEquals(List.of(), inbox.onTabComplete(regular, command, "rinbox", new String[] {""}));
    }

    @Test
    void reportIdParsing() {
        assertEquals(5L, Replies.parseReportId("#5").getAsLong());
        assertEquals(-5L, Replies.parseReportId("-5").getAsLong());
        assertEquals(-5L, Replies.parseReportId("#-5").getAsLong());
        assertTrue(Replies.parseReportId("five").isEmpty());
        assertTrue(Replies.looksLikeReportId("#1"));
        assertTrue(Replies.looksLikeReportId("-1"));
        assertFalse(Replies.looksLikeReportId("-"));
        assertFalse(Replies.looksLikeReportId("Steve"));
    }

    private static VoiceReportConfig reportConfig(VoiceReportConfig.Mode mode) {
        return new VoiceReportConfig(true, mode, 5, 1, 30, List.of(1, 5, 10, 15), 60, 3, false, "", true, 45);
    }
}
