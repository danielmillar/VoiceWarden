package dev.voicewarden.plugin.report;

import dev.voicewarden.core.audio.OpusAudioDecoder;
import dev.voicewarden.core.audio.RollingOpusBuffer;
import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.core.config.VoiceReportConfig;
import dev.voicewarden.core.discord.ReportWebhookPayload;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.moderation.DurationParser;
import dev.voicewarden.core.moderation.TranscriptBuffer;
import dev.voicewarden.core.moderation.TranscriptEntry;
import dev.voicewarden.core.storage.StorageProvider;
import dev.voicewarden.core.storage.VoiceReportRecord;
import dev.voicewarden.plugin.mute.KnownPlayer;
import dev.voicewarden.plugin.mute.PlayerDirectory;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import dev.voicewarden.plugin.voice.SpeakerContextTracker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public final class VoiceReportManager {

    public static final String ALERTS_PERMISSION = "voicewarden.alerts";
    public static final String REVIEW_PERMISSION = "voicewarden.report.review";
    public static final int INBOX_LIMIT = 10;

    public enum Status {
        SUBMITTED,
        DISABLED,
        SELF_REPORT,
        COOLDOWN,
        TOO_MANY,
        NO_TRANSCRIPT,
        FAILED
    }

    public record Outcome(Status status, @Nullable VoiceReportRecord report, long cooldownSecondsLeft) {

        static Outcome of(Status status) {
            return new Outcome(status, null, 0);
        }
    }

    private static final int REPORTER_LOCK_STRIPES = 64;
    private static final int COOLDOWN_PRUNE_THRESHOLD = 1024;

    private record CooldownClaim(long timestampMillis, long token) {
    }

    private final TranscriptBuffer transcripts;
    private final RollingOpusBuffer recentAudio;
    private final Supplier<OpusAudioDecoder> decoders;
    private final SpeakerContextTracker speakers;
    private final StorageProvider storage;
    private final ReportWebhookSender webhook;
    private final TaskScheduler scheduler;
    private final PlayerDirectory players;
    private final MessageRenderer messages;
    private final ReportMenus menus;
    private final Clock clock;
    private final ZoneId zone;
    private final String serverId;
    private final Logger logger;
    private final Executor async;
    private final Map<UUID, CooldownClaim> lastReportClaims = new ConcurrentHashMap<>();
    private final AtomicLong claimTokens = new AtomicLong();
    private final ReentrantLock[] reporterLocks = new ReentrantLock[REPORTER_LOCK_STRIPES];
    private volatile VoiceReportConfig config;

    public VoiceReportManager(
            VoiceReportConfig config,
            TranscriptBuffer transcripts,
            RollingOpusBuffer recentAudio,
            Supplier<OpusAudioDecoder> decoders,
            SpeakerContextTracker speakers,
            StorageProvider storage,
            ReportWebhookSender webhook,
            TaskScheduler scheduler,
            PlayerDirectory players,
            MessageRenderer messages,
            ReportMenus menus,
            Clock clock,
            ZoneId zone,
            String serverId,
            Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.recentAudio = Objects.requireNonNull(recentAudio, "recentAudio");
        this.decoders = Objects.requireNonNull(decoders, "decoders");
        this.speakers = Objects.requireNonNull(speakers, "speakers");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.webhook = Objects.requireNonNull(webhook, "webhook");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.players = Objects.requireNonNull(players, "players");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.async = scheduler::runAsync;
        for (int i = 0; i < REPORTER_LOCK_STRIPES; i++) {
            reporterLocks[i] = new ReentrantLock();
        }
        recentAudio.updateRetention(audioRetention(config));
    }

    public VoiceReportConfig config() {
        return config;
    }

    public void updateConfig(VoiceReportConfig newConfig) {
        this.config = Objects.requireNonNull(newConfig, "newConfig");
        recentAudio.updateRetention(audioRetention(newConfig));
    }

    public void pruneAudio() {
        recentAudio.prune(clock.millis());
    }

    public int resolveMinutes(@Nullable Integer requested) {
        VoiceReportConfig current = config;
        return current.clampMinutes(requested == null ? current.defaultMinutes() : requested);
    }

    public CompletableFuture<Outcome> submit(Player reporter, KnownPlayer target, @Nullable Integer requestedMinutes) {
        VoiceReportConfig current = config;
        UUID reporterId = reporter.getUniqueId();
        if (!current.enabled()) {
            return reply(reporter, Outcome.of(Status.DISABLED), MessageKey.REPORT_DISABLED, Map.of());
        }
        if (reporterId.equals(target.id())) {
            return reply(reporter, Outcome.of(Status.SELF_REPORT), MessageKey.REPORT_SELF_REPORT, Map.of());
        }
        long now = clock.millis();
        long secondsLeft = cooldownSecondsLeft(reporterId, now, current.cooldownSeconds());
        if (secondsLeft > 0) {
            return reply(reporter, new Outcome(Status.COOLDOWN, null, secondsLeft), MessageKey.REPORT_COOLDOWN,
                    Map.of("seconds", Long.toString(secondsLeft)));
        }
        int minutes = resolveMinutes(requestedMinutes);
        Map<String, String> values = Map.of("player", target.name(), "minutes", Integer.toString(minutes), "reporter", reporter.getName());
        Instant at = clock.instant();
        List<TranscriptEntry> own = transcripts.getRecent(target.id(), minutes, at);
        boolean captureAudio = current.audioCaptureEnabled();
        Duration requestedWindow = Duration.ofMinutes(minutes);
        Duration retentionWindow = audioRetention(current);
        Duration audioWindow = requestedWindow.compareTo(retentionWindow) < 0 ? requestedWindow : retentionWindow;
        if (own.isEmpty() && !(captureAudio && recentAudio.hasAudio(target.id(), audioWindow, now))) {
            return reply(reporter, Outcome.of(Status.NO_TRANSCRIPT), MessageKey.REPORT_NO_TRANSCRIPT, values);
        }
        List<ConversationLine> nearby = speakers.conversation(target.id(), at.minus(requestedWindow), at);
        List<TranscriptLine> lines = TranscriptLine.interleave(own, nearby, zone);
        String ownSnapshot = TranscriptLine.toSnapshot(TranscriptLine.of(own, zone));
        CooldownClaim claim = claimCooldown(reporterId, now, current.cooldownSeconds());
        if (claim == null) {
            long left = cooldownSecondsLeft(reporterId, now, current.cooldownSeconds());
            return reply(reporter, new Outcome(Status.COOLDOWN, null, left), MessageKey.REPORT_COOLDOWN, Map.of("seconds", Long.toString(left)));
        }
        VoiceReportRecord unsaved = VoiceReportRecord.open(reporterId, reporter.getName(), target.id(), target.name(), minutes,
                TranscriptLine.toSnapshot(lines), at, serverId);
        return CompletableFuture.supplyAsync(() -> {
            int max = current.maxConcurrentReports();
            if (captureAudio && atReportLimit(reporterId, max)) {
                lastReportClaims.remove(reporterId, claim);
                return Outcome.of(Status.TOO_MANY);
            }
            short[] clip = captureAudio ? decodeRecentAudio(target, audioWindow, now) : null;
            if (own.isEmpty() && (clip == null || clip.length == 0)) {
                lastReportClaims.remove(reporterId, claim);
                return Outcome.of(Status.NO_TRANSCRIPT);
            }
            VoiceReportRecord saved;
            ReentrantLock lock = reporterLock(reporterId);
            lock.lock();
            try {
                if (atReportLimit(reporterId, max)) {
                    lastReportClaims.remove(reporterId, claim);
                    return Outcome.of(Status.TOO_MANY);
                }
                saved = storage.saveReport(unsaved);
            } finally {
                lock.unlock();
            }
            webhook.send(new ReportWebhookPayload(saved, ownSnapshot, nearby, clip)).whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Could not send voice report #" + saved.id() + " to Discord", error);
                }
            });
            alertStaff(saved, lines, current.staffChatFullTranscript());
            return new Outcome(Status.SUBMITTED, saved, 0);
        }, async).exceptionally(error -> {
            lastReportClaims.remove(reporterId, claim);
            logger.log(Level.WARNING, "Could not save a voice report from " + reporter.getName(), error);
            return Outcome.of(Status.FAILED);
        }).thenApply(outcome -> {
            switch (outcome.status()) {
                case SUBMITTED -> send(reporter, MessageKey.REPORT_SUBMITTED, values);
                case TOO_MANY -> send(reporter, MessageKey.REPORT_TOO_MANY, values);
                case NO_TRANSCRIPT -> send(reporter, MessageKey.REPORT_NO_TRANSCRIPT, values);
                default -> send(reporter, MessageKey.REPORT_SAVE_FAILED, Map.of());
            }
            return outcome;
        });
    }

    public void openReportMenu(Player reporter) {
        if (!config.enabled()) {
            send(reporter, MessageKey.REPORT_DISABLED, Map.of());
            return;
        }
        List<KnownPlayer> targets = players.onlinePlayers().stream()
                .filter(player -> !player.getUniqueId().equals(reporter.getUniqueId()))
                .map(player -> new KnownPlayer(player.getUniqueId(), player.getName()))
                .sorted(Comparator.comparing(KnownPlayer::name, String.CASE_INSENSITIVE_ORDER))
                .limit(ChestReportMenus.MAX_SIZE)
                .toList();
        Map<Integer, KnownPlayer> slots = new LinkedHashMap<>();
        for (int slot = 0; slot < targets.size(); slot++) {
            slots.put(slot, targets.get(slot));
        }
        ReportMenuHolder holder = ReportMenuHolder.targets(slots);
        scheduler.runForEntity(reporter, () -> menus.openTargets(reporter, holder, targets));
    }

    public void openWindowMenu(Player reporter, KnownPlayer target) {
        List<Integer> presets = config.presetMinutes().isEmpty() ? List.of(config.defaultMinutes()) : config.presetMinutes();
        Map<Integer, Integer> slots = new LinkedHashMap<>();
        for (int slot = 0; slot < presets.size() && slot < ChestReportMenus.MAX_SIZE; slot++) {
            slots.put(slot, presets.get(slot));
        }
        ReportMenuHolder holder = ReportMenuHolder.windows(target, slots);
        scheduler.runForEntity(reporter, () -> menus.openWindows(reporter, holder, presets));
    }

    public void onMenuClick(Player reporter, ReportMenuHolder holder, int slot) {
        switch (holder.stage()) {
            case PICK_TARGET -> holder.targetAt(slot).ifPresent(target -> openWindowMenu(reporter, target));
            case PICK_WINDOW -> holder.minutesAt(slot).ifPresent(minutes -> holder.target().ifPresent(target -> {
                reporter.closeInventory();
                submit(reporter, target, minutes);
            }));
        }
    }

    public void viewTranscript(Player viewer, KnownPlayer target, @Nullable Integer requestedMinutes) {
        int minutes = resolveViewMinutes(requestedMinutes);
        List<TranscriptLine> lines = TranscriptLine.of(transcripts.getRecent(target.id(), minutes, clock.instant()), zone);
        openBook(viewer, TranscriptBook.build(messages, target.name(), lines, minutes));
    }

    public CompletableFuture<Boolean> viewReport(Player viewer, long reportId) {
        return CompletableFuture.supplyAsync(() -> storage.findReportById(reportId), async).thenApply(found -> {
            found.ifPresent(report -> openBook(viewer, TranscriptBook.build(messages, report.targetName(),
                    TranscriptLine.parseSnapshot(report.transcriptSnapshot()), report.windowMinutes())));
            return found.isPresent();
        });
    }

    public void openTranscriptBook(Player viewer, String targetName, List<TranscriptLine> lines, int minutes) {
        openBook(viewer, TranscriptBook.build(messages, targetName, lines, minutes));
    }

    public CompletableFuture<List<VoiceReportRecord>> listReports(boolean unreviewedOnly, int limit) {
        return CompletableFuture.supplyAsync(() -> storage.findReports(unreviewedOnly, limit), async);
    }

    public CompletableFuture<List<VoiceReportRecord>> openInbox(CommandSender viewer) {
        return listReports(true, INBOX_LIMIT).thenApply(reports -> {
            Instant now = clock.instant();
            List<Component> lines = new ArrayList<>();
            if (reports.isEmpty()) {
                lines.add(messages.render(MessageKey.REPORT_INBOX_EMPTY));
            } else {
                lines.add(messages.render(MessageKey.REPORT_INBOX_HEADER, Map.of("count", Integer.toString(reports.size()))));
                for (VoiceReportRecord report : reports) {
                    lines.add(messages.render(MessageKey.REPORT_INBOX_ENTRY, Map.of(
                            "id", Long.toString(report.id()),
                            "reporter", report.reporterName(),
                            "player", report.targetName(),
                            "minutes", Integer.toString(report.windowMinutes()),
                            "age", age(report.createdAt(), now))));
                }
                lines.add(Component.empty());
            }
            deliver(viewer, lines);
            return reports;
        });
    }

    public CompletableFuture<Boolean> markReviewed(long reportId, String reviewer) {
        return CompletableFuture.supplyAsync(() -> storage.markReportReviewed(reportId, reviewer, clock.instant()), async);
    }

    public CompletableFuture<Boolean> markReviewed(CommandSender reviewer, long reportId) {
        return markReviewed(reportId, reviewer.getName()).thenApply(changed -> {
            if (changed) {
                deliver(reviewer, List.of(messages.render(MessageKey.REPORT_INBOX_REVIEWED, Map.of("id", Long.toString(reportId)))));
            }
            return changed;
        });
    }

    public Optional<Long> cooldownRemaining(UUID reporterId) {
        long left = cooldownSecondsLeft(reporterId, clock.millis(), config.cooldownSeconds());
        return left > 0 ? Optional.of(left) : Optional.empty();
    }

    private boolean atReportLimit(UUID reporterId, int max) {
        return max > 0 && storage.countOpenReportsByReporter(reporterId) >= max;
    }

    private short @Nullable [] decodeRecentAudio(KnownPlayer target, Duration window, long now) {
        try {
            return recentAudio.decodeToPcm16k(target.id(), window, now, decoders);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not decode recent voice audio for " + target.name()
                    + "; sending the report without a clip", e);
            return null;
        }
    }

    private static Duration audioRetention(VoiceReportConfig config) {
        return config.audioCaptureEnabled() ? Duration.ofSeconds(config.audioBufferSeconds()) : Duration.ZERO;
    }

    private int resolveViewMinutes(@Nullable Integer requested) {
        int value = requested == null ? config.defaultMinutes() : requested;
        return Math.max(1, Math.min(value, transcripts.maxWindowMinutes()));
    }

    private void alertStaff(VoiceReportRecord report, List<TranscriptLine> lines, boolean fullTranscript) {
        Map<String, String> values = Map.of(
                "reporter", report.reporterName(),
                "player", report.targetName(),
                "minutes", Integer.toString(report.windowMinutes()),
                "id", Long.toString(report.id()));
        for (Player staff : players.onlinePlayers()) {
            scheduler.runForEntity(staff, () -> {
                if (!staff.hasPermission(ALERTS_PERMISSION) && !staff.hasPermission(REVIEW_PERMISSION)) {
                    return;
                }
                staff.sendMessage(messages.render(MessageKey.REPORT_STAFF_ALERT, staff, values));
                if (fullTranscript) {
                    for (TranscriptLine line : lines) {
                        staff.sendMessage(Component.text(line.toSnapshotLine()));
                    }
                }
            });
        }
    }

    private void openBook(Player viewer, Book book) {
        scheduler.runForEntity(viewer, () -> viewer.openBook(book));
    }

    private CompletableFuture<Outcome> reply(Player reporter, Outcome outcome, MessageKey key, Map<String, String> values) {
        send(reporter, key, values);
        return CompletableFuture.completedFuture(outcome);
    }

    private void send(Player player, MessageKey key, Map<String, String> values) {
        scheduler.runForEntity(player, () -> player.sendMessage(messages.render(key, player, values)));
    }

    private void deliver(CommandSender viewer, List<Component> lines) {
        Runnable sendAll = () -> lines.forEach(viewer::sendMessage);
        if (viewer instanceof Player player) {
            scheduler.runForEntity(player, sendAll);
        } else {
            scheduler.runGlobal(sendAll);
        }
    }

    private long cooldownSecondsLeft(UUID reporterId, long now, int cooldownSeconds) {
        CooldownClaim last = lastReportClaims.get(reporterId);
        if (last == null || cooldownSeconds <= 0) {
            return 0;
        }
        long leftMillis = last.timestampMillis() + cooldownSeconds * 1000L - now;
        return leftMillis <= 0 ? 0 : (leftMillis + 999) / 1000;
    }

    private @Nullable CooldownClaim claimCooldown(UUID reporterId, long now, int cooldownSeconds) {
        if (lastReportClaims.size() > COOLDOWN_PRUNE_THRESHOLD) {
            pruneCooldowns(now, cooldownSeconds);
        }
        CooldownClaim next = new CooldownClaim(now, claimTokens.incrementAndGet());
        while (true) {
            CooldownClaim last = lastReportClaims.get(reporterId);
            if (last == null) {
                if (lastReportClaims.putIfAbsent(reporterId, next) == null) {
                    return next;
                }
                continue;
            }
            if (cooldownSeconds > 0 && now - last.timestampMillis() < cooldownSeconds * 1000L) {
                return null;
            }
            if (lastReportClaims.replace(reporterId, last, next)) {
                return next;
            }
        }
    }

    private void pruneCooldowns(long now, int cooldownSeconds) {
        long windowMillis = Math.max(0L, cooldownSeconds) * 1000L;
        lastReportClaims.entrySet().removeIf(entry -> now - entry.getValue().timestampMillis() >= windowMillis);
    }

    private ReentrantLock reporterLock(UUID reporterId) {
        return reporterLocks[Math.floorMod(reporterId.hashCode(), REPORTER_LOCK_STRIPES)];
    }

    private static String age(Instant createdAt, Instant now) {
        Duration elapsed = Duration.between(createdAt, now);
        if (elapsed.compareTo(Duration.ofMinutes(1)) >= 0) {
            elapsed = elapsed.truncatedTo(ChronoUnit.MINUTES);
        }
        return DurationParser.format(elapsed);
    }
}
