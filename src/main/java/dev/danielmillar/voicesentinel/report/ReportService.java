package dev.danielmillar.voicesentinel.report;

import dev.danielmillar.voicesentinel.config.PluginConfig;
import dev.danielmillar.voicesentinel.evidence.EvidenceSettings;
import dev.danielmillar.voicesentinel.evidence.EvidenceStore;
import dev.danielmillar.voicesentinel.moderation.TranscriptBuffer;
import dev.danielmillar.voicesentinel.moderation.TranscriptLine;
import dev.danielmillar.voicesentinel.notify.StaffAlertService;
import dev.danielmillar.voicesentinel.notify.discord.DiscordWebhookService;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Player voice reports. Building a report (copying transcripts and audio) runs on an IO thread. */
public final class ReportService {

    /** Silence inserted between buffered utterances in the report recording. */
    private static final int GAP_SAMPLES = 16_000 * 3 / 10;
    /** Most recent speech kept in a report recording (4 min ≈ 7.7 MB WAV, under Discord's attachment limit). */
    private static final int MAX_AUDIO_SAMPLES = 16_000 * 240;

    public sealed interface Result {
        record Disabled() implements Result { }

        record Self() implements Result { }

        record Cooldown(Duration remaining) implements Result { }

        record Nothing() implements Result { }

        record Submitted(VoiceReport report) implements Result { }
    }

    private final Supplier<PluginConfig> config;
    private final TranscriptBuffer buffer;
    private final ReportStore store;
    private final StaffAlertService alerts;
    private final DiscordWebhookService discord;
    private final EvidenceStore evidence;
    private final Executor io;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Long> lastReport = new ConcurrentHashMap<>();

    public ReportService(Supplier<PluginConfig> config, TranscriptBuffer buffer, ReportStore store, StaffAlertService alerts,
                         DiscordWebhookService discord, EvidenceStore evidence, Executor io, Logger logger) {
        this.config = config;
        this.buffer = buffer;
        this.store = store;
        this.alerts = alerts;
        this.discord = discord;
        this.evidence = evidence;
        this.io = io;
        this.logger = logger;
    }

    public CompletableFuture<Result> submit(@Nullable UUID reporterId, String reporterName, UUID targetId,
                                            String targetName, @Nullable Duration requestedWindow) {
        return CompletableFuture.supplyAsync(() -> {
            PluginConfig cfg = config.get();
            PluginConfig.VoiceReport settings = cfg.voiceReport();
            if (!settings.enabled()) {
                return new Result.Disabled();
            }
            if (targetId.equals(reporterId)) {
                return new Result.Self();
            }
            long now = System.currentTimeMillis();
            if (reporterId != null) {
                Long last = lastReport.get(reporterId);
                long cooldown = settings.cooldown().toMillis();
                if (last != null && now - last < cooldown) {
                    return new Result.Cooldown(Duration.ofMillis(cooldown - (now - last)));
                }
            }
            Duration window = settings.clamp(requestedWindow);
            List<TranscriptBuffer.Entry> entries = buffer.since(targetId, Instant.now().minus(window));
            if (entries.isEmpty()) {
                return new Result.Nothing();
            }
            List<TranscriptLine> lines = new ArrayList<>(entries.size());
            for (TranscriptBuffer.Entry entry : entries) {
                lines.add(entry.line());
            }
            // Newest utterances first until the cap, then laid out oldest → newest.
            List<short[]> clips = new ArrayList<>();
            int samples = 0;
            for (int i = entries.size() - 1; i >= 0; i--) {
                short[] a = entries.get(i).audio();
                if (a == null) {
                    continue;
                }
                if (samples + a.length > MAX_AUDIO_SAMPLES) {
                    break;
                }
                clips.addFirst(a);
                samples += a.length + GAP_SAMPLES;
            }
            short[] audio = new short[Math.max(0, samples - GAP_SAMPLES)];
            int pos = 0;
            for (short[] a : clips) {
                System.arraycopy(a, 0, audio, pos, a.length);
                pos += a.length + GAP_SAMPLES;
            }
            VoiceReport report = new VoiceReport(UUID.randomUUID(), Instant.now(), reporterId, reporterName, targetId,
                    targetName, window, lines, audio, 16_000);
            if (reporterId != null) {
                lastReport.put(reporterId, now);
            }
            store.add(report);
            alerts.report(report);
            discord.submitReport(report);
            if (cfg.recordings().mode() != EvidenceSettings.Mode.NONE) {
                evidence.saveReport(report).exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not save evidence for report " + report.shortId(), error);
                    return null;
                });
            }
            return new Result.Submitted(report);
        }, io);
    }

    public ReportStore store() {
        return store;
    }

    public List<TranscriptLine> liveLines(UUID target, Duration window) {
        return buffer.since(target, Instant.now().minus(window)).stream().map(TranscriptBuffer.Entry::line).toList();
    }
}
