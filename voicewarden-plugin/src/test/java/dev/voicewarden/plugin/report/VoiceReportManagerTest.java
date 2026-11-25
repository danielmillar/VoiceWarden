package dev.voicewarden.plugin.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.core.audio.AudioFormat;
import dev.voicewarden.core.audio.OpusAudioDecoder;
import dev.voicewarden.core.audio.RollingOpusBuffer;
import dev.voicewarden.core.config.TranscriptConfig;
import dev.voicewarden.core.config.VoiceReportConfig;
import dev.voicewarden.core.discord.ReportWebhookPayload;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.moderation.TranscriptBuffer;
import dev.voicewarden.core.moderation.TranscriptEntry;
import dev.voicewarden.core.storage.InMemoryFallbackStore;
import dev.voicewarden.core.storage.VoiceReportRecord;
import dev.voicewarden.plugin.FakePlayers;
import dev.voicewarden.plugin.ManualScheduler;
import dev.voicewarden.plugin.MutableClock;
import dev.voicewarden.plugin.TestMessages;
import dev.voicewarden.plugin.mute.KnownPlayer;
import dev.voicewarden.plugin.report.VoiceReportManager.Status;
import dev.voicewarden.plugin.scheduler.TaskHandle;
import dev.voicewarden.plugin.voice.SpeakerContextTracker;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VoiceReportManagerTest {

    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakePlayers players = new FakePlayers();
    private final InMemoryFallbackStore storage = new InMemoryFallbackStore("server-1");
    private static final String WEBHOOK = "https://discord.com/api/webhooks/1/token";
    private static final short FAKE_SAMPLE = 1200;

    private final TranscriptBuffer buffer = TranscriptBuffer.withDefaults();
    private final RollingOpusBuffer recentAudio = new RollingOpusBuffer(Duration.ZERO);
    private final AtomicInteger decodersCreated = new AtomicInteger();
    private Supplier<OpusAudioDecoder> decoders = () -> {
        decodersCreated.incrementAndGet();
        return new ConstantDecoder();
    };
    private final SpeakerContextTracker speakers = new SpeakerContextTracker(buffer, new TranscriptConfig(43_200, 500, 4, 60, 48), clock);
    private final UUID world = UUID.randomUUID();
    private final List<ReportWebhookPayload> webhooks = new ArrayList<>();
    private final RecordingMenus menus = new RecordingMenus();

    private final Player reporter = players.join("Reporter");
    private final Player target = players.join("Target");
    private final KnownPlayer targetKnown = new KnownPlayer(target.getUniqueId(), "Target");

    private static VoiceReportConfig config(boolean enabled, int cooldown, int maxConcurrent, boolean fullTranscript) {
        return new VoiceReportConfig(enabled, VoiceReportConfig.Mode.COMMAND, 5, 1, 30, List.of(1, 5, 10, 15), cooldown, maxConcurrent,
                fullTranscript, "", true, 45);
    }

    private static VoiceReportConfig audioConfig(boolean includeAudio) {
        return new VoiceReportConfig(true, VoiceReportConfig.Mode.COMMAND, 5, 1, 30, List.of(1, 5, 10, 15), 0, 10, false, WEBHOOK,
                includeAudio, 45);
    }

    private VoiceReportManager manager(VoiceReportConfig config) {
        return manager(config, scheduler);
    }

    private VoiceReportManager manager(VoiceReportConfig config, ManualScheduler scheduler) {
        return new VoiceReportManager(config, buffer, recentAudio, decoders, speakers, storage, payload -> {
            webhooks.add(payload);
            return CompletableFuture.completedFuture(null);
        }, scheduler, players, TestMessages.renderer(), menus, clock, ZoneOffset.UTC, "server-1", Logger.getLogger("test"));
    }

    private void said(Player player, String text, Duration ago) {
        buffer.append(player.getUniqueId(), TranscriptEntry.clean(clock.instant().minus(ago), text));
    }

    private void spoke(Player player, int frames, Duration ago) {
        long start = clock.millis() - ago.toMillis();
        for (int i = 0; i < frames; i++) {
            recentAudio.append(player.getUniqueId(), new byte[] {1, 2, 3}, 0, 3, start + i * 20L);
        }
    }

    @Test
    void audioOnlyReportIsSubmittedWithAClipWhenNothingWasTranscribed() {
        VoiceReportManager reports = manager(audioConfig(true));
        spoke(target, 50, Duration.ofSeconds(5));

        VoiceReportManager.Outcome outcome = reports.submit(reporter, targetKnown, 5).join();

        assertEquals(Status.SUBMITTED, outcome.status());
        assertEquals("", outcome.report().transcriptSnapshot());
        assertEquals(1, webhooks.size());
        ReportWebhookPayload sent = webhooks.get(0);
        assertTrue(sent.hasAudio());
        assertEquals(50 * AudioFormat.OPUS_FRAME_20MS_48K / AudioFormat.DECIMATION_FACTOR, sent.pcm16kAudio().length);
        assertEquals(FAKE_SAMPLE, sent.pcm16kAudio()[sent.pcm16kAudio().length / 2], 2);
    }

    @Test
    void transcriptReportsCarryTheRecentClipToo() {
        VoiceReportManager reports = manager(audioConfig(true));
        said(target, "hello there", Duration.ofSeconds(3));
        spoke(target, 10, Duration.ofSeconds(3));

        assertEquals(Status.SUBMITTED, reports.submit(reporter, targetKnown, 5).join().status());

        assertTrue(webhooks.get(0).hasAudio());
        assertEquals(1, decodersCreated.get());
    }

    @Test
    void audioIsNotCapturedWithoutAWebhookOrWithIncludeAudioOff() {
        VoiceReportManager noWebhook = manager(config(true, 0, 10, false));
        spoke(target, 10, Duration.ofSeconds(1));
        assertEquals(0, recentAudio.playerCount());
        assertEquals(Status.NO_TRANSCRIPT, noWebhook.submit(reporter, targetKnown, 5).join().status());

        VoiceReportManager audioOff = manager(audioConfig(false));
        spoke(target, 10, Duration.ofSeconds(1));
        assertEquals(Status.NO_TRANSCRIPT, audioOff.submit(reporter, targetKnown, 5).join().status());

        said(target, "words", Duration.ofSeconds(1));
        assertEquals(Status.SUBMITTED, audioOff.submit(reporter, targetKnown, 5).join().status());
        assertFalse(webhooks.get(0).hasAudio());
        assertEquals(0, decodersCreated.get());
    }

    @Test
    void reloadingWithAudioDisabledDropsBufferedAudio() {
        VoiceReportManager reports = manager(audioConfig(true));
        spoke(target, 10, Duration.ofSeconds(1));
        assertEquals(1, recentAudio.playerCount());

        reports.updateConfig(audioConfig(false));

        assertEquals(0, recentAudio.playerCount());
        assertEquals(Duration.ZERO, recentAudio.retention());
    }

    @Test
    void audioOlderThanTheBufferWindowDoesNotJustifyAReport() {
        VoiceReportManager reports = manager(audioConfig(true));
        spoke(target, 10, Duration.ofSeconds(2));
        clock.advance(Duration.ofSeconds(60));

        assertEquals(Status.NO_TRANSCRIPT, reports.submit(reporter, targetKnown, 5).join().status());
        reports.pruneAudio();
        assertEquals(0, recentAudio.playerCount());
    }

    @Test
    void decodingRunsOnTheAsyncWorkerNotTheCallingThread() {
        DeferredAsyncScheduler deferred = new DeferredAsyncScheduler();
        VoiceReportManager reports = manager(audioConfig(true), deferred);
        spoke(target, 20, Duration.ofSeconds(2));

        CompletableFuture<VoiceReportManager.Outcome> pending = reports.submit(reporter, targetKnown, 5);

        assertFalse(pending.isDone());
        assertEquals(0, decodersCreated.get());
        deferred.drainAsync();
        assertEquals(Status.SUBMITTED, pending.join().status());
        assertEquals(1, decodersCreated.get());
    }

    @Test
    void audioThatVanishesBeforeTheWorkerRunsGivesNoTranscriptAndKeepsTheCooldown() {
        DeferredAsyncScheduler deferred = new DeferredAsyncScheduler();
        VoiceReportConfig withCooldown = new VoiceReportConfig(true, VoiceReportConfig.Mode.COMMAND, 5, 1, 30, List.of(5), 60, 10,
                false, WEBHOOK, true, 45);
        VoiceReportManager reports = manager(withCooldown, deferred);
        spoke(target, 20, Duration.ofSeconds(2));

        CompletableFuture<VoiceReportManager.Outcome> pending = reports.submit(reporter, targetKnown, 5);
        recentAudio.clear(target.getUniqueId());
        deferred.drainAsync();

        assertEquals(Status.NO_TRANSCRIPT, pending.join().status());
        assertTrue(storage.findReports(false, 10).isEmpty());
        assertTrue(webhooks.isEmpty());
        assertTrue(reports.cooldownRemaining(reporter.getUniqueId()).isEmpty());
    }

    @Test
    void aDecoderFailureStillSendsTheTranscriptReport() {
        decoders = () -> {
            throw new IllegalStateException("no decoder");
        };
        VoiceReportManager reports = manager(audioConfig(true));
        said(target, "hello", Duration.ofSeconds(1));
        spoke(target, 10, Duration.ofSeconds(1));

        assertEquals(Status.SUBMITTED, reports.submit(reporter, targetKnown, 5).join().status());
        assertFalse(webhooks.get(0).hasAudio());
    }

    @Test
    void reachingTheOpenReportLimitSkipsDecoding() {
        VoiceReportManager reports = manager(new VoiceReportConfig(true, VoiceReportConfig.Mode.COMMAND, 5, 1, 30, List.of(5), 0, 1,
                false, WEBHOOK, true, 45));
        spoke(target, 10, Duration.ofSeconds(1));
        assertEquals(Status.SUBMITTED, reports.submit(reporter, targetKnown, 5).join().status());
        assertEquals(1, decodersCreated.get());

        assertEquals(Status.TOO_MANY, reports.submit(reporter, targetKnown, 5).join().status());
        assertEquals(1, decodersCreated.get());
    }

    @Test
    void submitSnapshotsSavesSendsWebhookAndConfirms() {
        said(target, "hello there", Duration.ofMinutes(2));
        buffer.append(target.getUniqueId(), TranscriptEntry.flagged(START.minusSeconds(30), "bad words", FlagSeverity.SEVERE));
        said(target, "too old", Duration.ofMinutes(20));
        VoiceReportManager reports = manager(config(true, 60, 3, false));

        VoiceReportManager.Outcome outcome = reports.submit(reporter, targetKnown, null).join();

        assertEquals(Status.SUBMITTED, outcome.status());
        VoiceReportRecord saved = outcome.report();
        assertNotNull(saved);
        assertTrue(saved.id() != 0);
        assertEquals(5, saved.windowMinutes());
        assertEquals("[11:58:00] hello there\n[11:59:30] [SEVERE] bad words", saved.transcriptSnapshot());
        assertEquals(1, webhooks.size());
        assertEquals(saved, webhooks.get(0).report());
        assertEquals(List.of(saved), storage.findReports(true, 10));
        verify(reporter).sendMessage(any(Component.class));
    }

    private Player standingAt(String name, double x, double z) {
        Player player = players.join(name);
        speakers.updatePosition(player.getUniqueId(), name, world, x, 64, z);
        return player;
    }

    @Test
    void reportSnapshotInterleavesNearbySpeakersLabelledByName() {
        speakers.updatePosition(target.getUniqueId(), "Target", world, 0, 64, 0);
        Player nearby = standingAt("Nearby", 5, 5);
        Player far = standingAt("Far", 900, 900);
        said(nearby, "who took my diamonds", Duration.ofSeconds(90));
        said(target, "not me", Duration.ofSeconds(60));
        buffer.append(nearby.getUniqueId(), TranscriptEntry.flagged(START.minusSeconds(30), "liar", FlagSeverity.MILD));
        said(far, "unrelated chatter", Duration.ofSeconds(45));
        said(target, "bad words", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 0, 0, false));

        VoiceReportRecord saved = reports.submit(reporter, targetKnown, 5).join().report();

        assertEquals("[Nearby] [11:58:30] who took my diamonds\n"
                + "[11:59:00] not me\n"
                + "[Nearby] [11:59:30] [MILD] liar\n"
                + "[11:59:50] bad words", saved.transcriptSnapshot());
        ReportWebhookPayload webhook = webhooks.getFirst();
        assertEquals("[11:59:00] not me\n[11:59:50] bad words", webhook.transcript());
        assertEquals(List.of("who took my diamonds", "liar"), webhook.contextLines().stream().map(ConversationLine::text).toList());
        assertEquals(saved, webhook.report());
    }

    @Test
    void nearbySpeechAloneIsNotEnoughToFileAReport() {
        speakers.updatePosition(target.getUniqueId(), "Target", world, 0, 64, 0);
        Player nearby = standingAt("Nearby", 5, 5);
        said(nearby, "only i spoke", Duration.ofSeconds(10));

        assertEquals(Status.NO_TRANSCRIPT, manager(config(true, 0, 0, false)).submit(reporter, targetKnown, 5).join().status());
        assertTrue(webhooks.isEmpty());
    }

    @Test
    void contextLinesZeroKeepsReportsToTheReportedPlayer() {
        speakers.updateConfig(new TranscriptConfig(43_200, 500, 0, 60, 48));
        speakers.updatePosition(target.getUniqueId(), "Target", world, 0, 64, 0);
        Player nearby = standingAt("Nearby", 5, 5);
        said(nearby, "hidden", Duration.ofSeconds(20));
        said(target, "hello", Duration.ofSeconds(10));

        VoiceReportRecord saved = manager(config(true, 0, 0, false)).submit(reporter, targetKnown, 5).join().report();

        assertEquals("[11:59:50] hello", saved.transcriptSnapshot());
        assertTrue(webhooks.getFirst().contextLines().isEmpty());
    }

    @Test
    void storedReportBookLabelsNearbySpeakers() {
        speakers.updatePosition(target.getUniqueId(), "Target", world, 0, 64, 0);
        Player nearby = standingAt("Nearby", 5, 5);
        said(nearby, "calm down", Duration.ofSeconds(20));
        said(target, "kept forever", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 0, 0, false));
        long id = reports.submit(reporter, targetKnown, 5).join().report().id();
        Player viewer = players.join("Viewer");

        assertTrue(reports.viewReport(viewer, id).join());

        ArgumentCaptor<Book> book = ArgumentCaptor.forClass(Book.class);
        verify(viewer).openBook(book.capture());
        String page = plain(book.getValue().pages().get(0));
        assertTrue(page.contains("11:59:40 [Nearby] calm down"), page);
        assertTrue(page.contains("11:59:50 kept forever"), page);
    }

    @Test
    void cooldownBlocksRepeatReportsUntilItExpires() {
        said(target, "hello", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 60, 10, false));
        assertEquals(Status.SUBMITTED, reports.submit(reporter, targetKnown, 5).join().status());

        clock.advance(Duration.ofSeconds(20));
        VoiceReportManager.Outcome blocked = reports.submit(reporter, targetKnown, 5).join();
        assertEquals(Status.COOLDOWN, blocked.status());
        assertEquals(40, blocked.cooldownSecondsLeft());
        assertEquals(1, storage.findReports(false, 10).size());

        clock.advance(Duration.ofSeconds(40));
        assertEquals(Status.SUBMITTED, reports.submit(reporter, targetKnown, 5).join().status());
    }

    @Test
    void emptyTranscriptDoesNotConsumeTheCooldown() {
        VoiceReportManager reports = manager(config(true, 60, 10, false));
        assertEquals(Status.NO_TRANSCRIPT, reports.submit(reporter, targetKnown, 5).join().status());
        said(target, "now speaking", Duration.ofSeconds(1));
        assertEquals(Status.SUBMITTED, reports.submit(reporter, targetKnown, 5).join().status());
    }

    @Test
    void maxConcurrentReportsCountsOpenReportsAndReleasesAfterReview() {
        said(target, "hello", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 0, 1, false));
        VoiceReportRecord first = reports.submit(reporter, targetKnown, 5).join().report();

        assertEquals(Status.TOO_MANY, reports.submit(reporter, targetKnown, 5).join().status());
        assertEquals(1, storage.findReports(false, 10).size());

        assertTrue(reports.markReviewed(first.id(), "Mod").join());
        assertEquals(Status.SUBMITTED, reports.submit(reporter, targetKnown, 5).join().status());
    }

    @Test
    void tooManyDoesNotStartTheCooldown() {
        said(target, "hello", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 60, 1, false));
        VoiceReportRecord first = reports.submit(reporter, targetKnown, 5).join().report();
        clock.advance(Duration.ofSeconds(61));
        assertEquals(Status.TOO_MANY, reports.submit(reporter, targetKnown, 5).join().status());
        reports.markReviewed(first.id(), "Mod").join();
        assertEquals(Status.SUBMITTED, reports.submit(reporter, targetKnown, 5).join().status());
    }

    @Test
    void windowIsClampedToConfiguredBounds() {
        said(target, "hello", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 0, 0, false));
        assertEquals(30, reports.submit(reporter, targetKnown, 999).join().report().windowMinutes());
        assertEquals(1, reports.submit(reporter, targetKnown, 0).join().report().windowMinutes());
        assertEquals(1, reports.submit(reporter, targetKnown, -4).join().report().windowMinutes());
        assertEquals(5, reports.submit(reporter, targetKnown, null).join().report().windowMinutes());
    }

    @Test
    void selfReportsAndDisabledReportsAreRejected() {
        said(reporter, "hello", Duration.ofSeconds(10));
        KnownPlayer self = new KnownPlayer(reporter.getUniqueId(), "Reporter");
        assertEquals(Status.SELF_REPORT, manager(config(true, 0, 0, false)).submit(reporter, self, 5).join().status());
        assertEquals(Status.DISABLED, manager(config(false, 0, 0, false)).submit(reporter, targetKnown, 5).join().status());
        assertTrue(storage.findReports(false, 10).isEmpty());
    }

    @Test
    void staffWithPermissionGetAClickableAlertAndOptionalFullTranscript() {
        Player staff = players.join("Staff");
        Player reviewer = players.join("Reviewer");
        when(staff.hasPermission(VoiceReportManager.ALERTS_PERMISSION)).thenReturn(true);
        when(reviewer.hasPermission(VoiceReportManager.REVIEW_PERMISSION)).thenReturn(true);
        said(target, "line one", Duration.ofSeconds(20));
        said(target, "line two", Duration.ofSeconds(10));

        manager(config(true, 0, 0, true)).submit(reporter, targetKnown, 5).join();

        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(staff, times(3)).sendMessage(messages.capture());
        verify(reviewer, times(3)).sendMessage(any(Component.class));
        verify(target, never()).sendMessage(any(Component.class));
        Component alert = messages.getAllValues().get(0);
        String command = flatten(alert).stream().map(Component::clickEvent).filter(e -> e != null).findFirst().orElseThrow().value();
        assertTrue(command.startsWith("/viewreport #"), command);
        assertEquals("[11:59:50] line two", plain(messages.getAllValues().get(2)));
    }

    @Test
    void bookPaginationRespectsLineAndCharacterBudgets() {
        List<TranscriptLine> lines = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            lines.add(new TranscriptLine("12:00:" + String.format("%02d", i), "line number " + i, i % 7 == 0 ? FlagSeverity.MILD : null));
        }
        lines.add(new TranscriptLine("12:01:00", "word ".repeat(100).strip(), null));
        Book book = TranscriptBook.build(TestMessages.renderer(), "Target", lines, 5);

        assertEquals("Voice report: Target", plain(book.title()));
        assertTrue(plain(book.pages().get(0)).startsWith("Target\nLast 5 minutes"));
        assertTrue(book.pages().size() >= 4);
        int totalLines = 0;
        for (Component page : book.pages()) {
            String text = plain(page);
            assertTrue(text.length() <= TranscriptBook.MAX_CHARS_PER_PAGE, "page too long: " + text.length());
            long pageLines = text.lines().filter(line -> line.matches("^\\d\\d:\\d\\d:\\d\\d .*")).count();
            assertTrue(pageLines <= TranscriptBook.MAX_LINES_PER_PAGE);
            totalLines += (int) pageLines;
        }
        assertEquals(43, totalLines);
        String all = book.pages().stream().map(VoiceReportManagerTest::plain).reduce("", String::concat);
        assertTrue(all.contains("12:00:39 line number 39"));
    }

    @Test
    void emptyBookShowsTheEmptyMessage() {
        Book book = TranscriptBook.build(TestMessages.renderer(), "Target", List.of(), 5);
        assertEquals(1, book.pages().size());
        assertTrue(plain(book.pages().get(0)).contains("No voice chat in this window."));
    }

    @Test
    void transcriptBookOpensOnTheViewersThread() {
        said(target, "hello", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 0, 0, false));
        Player viewer = players.join("Viewer");
        reports.viewTranscript(viewer, targetKnown, 10);
        ArgumentCaptor<Book> book = ArgumentCaptor.forClass(Book.class);
        verify(viewer).openBook(book.capture());
        assertTrue(plain(book.getValue().pages().get(0)).contains("11:59:50"));
        assertTrue(plain(book.getValue().pages().get(0)).contains("hello"));
    }

    @Test
    void storedReportReopensFromItsSnapshot() {
        said(target, "kept forever", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 0, 0, false));
        long id = reports.submit(reporter, targetKnown, 5).join().report().id();
        buffer.clear(target.getUniqueId());
        Player viewer = players.join("Viewer");

        assertTrue(reports.viewReport(viewer, id).join());
        assertFalse(reports.viewReport(viewer, id + 100).join());

        ArgumentCaptor<Book> book = ArgumentCaptor.forClass(Book.class);
        verify(viewer).openBook(book.capture());
        assertTrue(plain(book.getValue().pages().get(0)).contains("kept forever"));
    }

    @Test
    void inboxListsOpenReportsAndMarkReviewedRemovesThem() {
        said(target, "hello", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 0, 0, false));
        VoiceReportRecord report = reports.submit(reporter, targetKnown, 5).join().report();
        clock.advance(Duration.ofMinutes(3));
        CommandSender console = mock(CommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        assertEquals(List.of(report), reports.openInbox(console).join());
        ArgumentCaptor<Component> lines = ArgumentCaptor.forClass(Component.class);
        verify(console, times(3)).sendMessage(lines.capture());
        assertTrue(plain(lines.getAllValues().get(1)).contains("Target by Reporter, 3m ago"));

        assertTrue(reports.markReviewed(console, report.id()).join());
        assertTrue(reports.listReports(true, 10).join().isEmpty());
        assertEquals("CONSOLE", reports.listReports(false, 10).join().get(0).reviewedBy());

        CommandSender other = mock(CommandSender.class);
        assertTrue(reports.openInbox(other).join().isEmpty());
        verify(other, times(1)).sendMessage(any(Component.class));
    }

    @Test
    void guiFlowPicksTargetThenPresetThenSubmits() {
        said(target, "hello", Duration.ofSeconds(10));
        VoiceReportManager reports = manager(config(true, 0, 0, false));
        reports.openReportMenu(reporter);

        ReportMenuHolder targetsMenu = menus.lastHolder;
        assertEquals(ReportMenuHolder.Stage.PICK_TARGET, targetsMenu.stage());
        assertTrue(targetsMenu.targetSlots().values().stream().noneMatch(k -> k.id().equals(reporter.getUniqueId())));
        int targetSlot = targetsMenu.targetSlots().entrySet().stream()
                .filter(e -> e.getValue().id().equals(target.getUniqueId())).findFirst().orElseThrow().getKey();

        reports.onMenuClick(reporter, targetsMenu, 53);
        assertTrue(menus.lastHolder == targetsMenu);
        reports.onMenuClick(reporter, targetsMenu, targetSlot);
        ReportMenuHolder windows = menus.lastHolder;
        assertEquals(ReportMenuHolder.Stage.PICK_WINDOW, windows.stage());
        assertEquals(List.of(1, 5, 10, 15), menus.lastMinutes);

        reports.onMenuClick(reporter, windows, 2);
        verify(reporter).closeInventory();
        assertEquals(10, storage.findReports(true, 10).get(0).windowMinutes());
    }

    @Test
    void snapshotRoundTripsThroughTheParser() {
        List<TranscriptLine> lines = List.of(new TranscriptLine("01:02:03", "plain", null),
                new TranscriptLine("01:02:04", "[brackets] stay", FlagSeverity.MODERATE),
                new TranscriptLine("01:02:05", "[brackets] stay", null),
                new TranscriptLine("01:02:06", "from a peer", null, "Alex_2"),
                new TranscriptLine("01:02:07", "flagged peer", FlagSeverity.SEVERE, "SEVERE"),
                new TranscriptLine("01:02:08", "named like a flag", null, "MILD"),
                new TranscriptLine("01:02:09", "bedrock peer", null, ".Steve"));
        assertEquals(lines, TranscriptLine.parseSnapshot(TranscriptLine.toSnapshot(lines)));
    }

    @Test
    void snapshotsSavedBeforeNearbyContextStillParse() {
        assertEquals(List.of(new TranscriptLine("11:58:00", "hello there", null),
                        new TranscriptLine("11:59:30", "bad words", FlagSeverity.SEVERE),
                        new TranscriptLine("", "free text", null)),
                TranscriptLine.parseSnapshot("[11:58:00] hello there\n[11:59:30] [SEVERE] bad words\nfree text"));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<Component> flatten(Component component) {
        List<Component> out = new ArrayList<>();
        out.add(component);
        component.children().forEach(child -> out.addAll(flatten(child)));
        return out;
    }

    private static final class ConstantDecoder implements OpusAudioDecoder {

        @Override
        public int decode(byte[] packet, int offset, int length, short[] out48k) {
            Arrays.fill(out48k, 0, AudioFormat.OPUS_FRAME_20MS_48K, FAKE_SAMPLE);
            return AudioFormat.OPUS_FRAME_20MS_48K;
        }

        @Override
        public int conceal(short[] out48k) {
            return 0;
        }

        @Override
        public void reset() {
        }
    }

    private static final class DeferredAsyncScheduler extends ManualScheduler {

        private final List<Runnable> async = new ArrayList<>();

        @Override
        public TaskHandle runAsync(Runnable task) {
            async.add(task);
            return TaskHandle.NONE;
        }

        void drainAsync() {
            List<Runnable> batch = List.copyOf(async);
            async.clear();
            batch.forEach(Runnable::run);
        }
    }

    static final class RecordingMenus implements ReportMenus {
        ReportMenuHolder lastHolder;
        List<Integer> lastMinutes;

        @Override
        public void openTargets(Player reporter, ReportMenuHolder holder, List<KnownPlayer> targets) {
            lastHolder = holder;
        }

        @Override
        public void openWindows(Player reporter, ReportMenuHolder holder, List<Integer> minutes) {
            lastHolder = holder;
            lastMinutes = minutes;
        }
    }
}
