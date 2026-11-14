package dev.danielmillar.voicewarden.privacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.consent.ConsentService;
import dev.danielmillar.voicewarden.evidence.EvidenceStore;
import dev.danielmillar.voicewarden.moderation.OffenseTracker;
import dev.danielmillar.voicewarden.moderation.Offenses;
import dev.danielmillar.voicewarden.moderation.TranscriptBuffer;
import dev.danielmillar.voicewarden.moderation.TranscriptLine;
import dev.danielmillar.voicewarden.moderation.TranscriptLog;
import dev.danielmillar.voicewarden.mute.MuteEvent;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.report.ReportStore;
import dev.danielmillar.voicewarden.storage.Database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Everything VoiceWarden stores about one player, across every store: erasing it ({@code /vw forget}), exporting it
 * ({@code /vw export}), and deleting old records hourly under {@code privacy.retention}. Each store removes its own
 * in-memory state and rows; file work runs on the IO executor.
 */
public final class PlayerData {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

    /** What {@link #forget} deleted. */
    public record Forgotten(boolean wasMuted, int muteEvents, int offences, boolean consent, int reports,
                            int evidenceFiles, int bufferedLines, int logLines, int exports) {
    }

    /** What {@link #prune} deleted. */
    public record Pruned(int offences, int reports, int consents, int muteEvents, int transcriptLogs, int exports) {
        int total() {
            return offences + reports + consents + muteEvents + transcriptLogs + exports;
        }
    }

    private final Supplier<PluginConfig> config;
    private final Database database;
    private final MuteService mutes;
    private final OffenseTracker offenses;
    private final Function<UUID, CompletableFuture<Void>> forgive;
    private final ConsentService consent;
    private final ReportStore reportStore;
    private final Consumer<UUID> forgetReportCooldown;
    private final EvidenceStore evidence;
    private final TranscriptBuffer buffer;
    private final TranscriptLog transcriptLog;
    private final Path exports;
    private final Predicate<UUID> online;
    private final Executor io;
    private final Logger logger;

    /**
     * @param forgive clears the player's offence count and flag window (see {@code ModerationService#forgive})
     * @param forgetReportCooldown forgets when the player last used /reportvoice
     * @param online  whether the player is on this server now
     */
    public PlayerData(Supplier<PluginConfig> config, Database database, MuteService mutes, OffenseTracker offenses,
                      Function<UUID, CompletableFuture<Void>> forgive, ConsentService consent, ReportStore reportStore,
                      Consumer<UUID> forgetReportCooldown, EvidenceStore evidence, TranscriptBuffer buffer, TranscriptLog transcriptLog,
                      Path exports, Predicate<UUID> online, Executor io, Logger logger) {
        this.config = config;
        this.database = database;
        this.mutes = mutes;
        this.offenses = offenses;
        this.forgive = forgive;
        this.consent = consent;
        this.reportStore = reportStore;
        this.forgetReportCooldown = forgetReportCooldown;
        this.evidence = evidence;
        this.buffer = buffer;
        this.transcriptLog = transcriptLog;
        this.exports = exports;
        this.online = online;
        this.io = io;
        this.logger = logger;
    }

    /**
     * Erases the player from every store. The mute goes first: if its LuckPerms nodes cannot be removed, nothing else
     * is touched and the future fails, so the command can simply be run again.
     */
    public CompletableFuture<Forgotten> forget(UUID player) {
        return mutes.erase(player).thenCompose(mute -> {
            int offenceCount = offenses.stored(player).count();
            CompletableFuture<Void> offenceWrite = forgive.apply(player);
            boolean hadConsent = consent.forget(player);
            int reportCount = reportStore.forget(player);
            forgetReportCooldown.accept(player);
            int bufferedLines = buffer.forget(player);
            CompletableFuture<int[]> files = CompletableFuture.supplyAsync(() -> {
                try {
                    return new int[]{transcriptLog.forget(player), deleteExports(player)};
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, io);
            return evidence.forget(player).thenCombine(files, (evidenceFiles, logAndExports) ->
                            new Forgotten(mute.wasMuted(), mute.historyEvents(), offenceCount, hadConsent, reportCount,
                                    evidenceFiles, bufferedLines, logAndExports[0], logAndExports[1]))
                    .thenCombine(offenceWrite.thenCompose(ignored -> database.query(c -> leftovers(c, player))),
                            (forgotten, left) -> {
                                if (left > 0) {
                                    throw new IllegalStateException(left + " database row(s) could not be deleted; see the console");
                                }
                                return forgotten;
                            });
        });
    }

    /** Rows still naming the player. The deletes before it are queued writes, which only log a failure. */
    private static int leftovers(java.sql.Connection c, UUID player) throws java.sql.SQLException {
        int left = 0;
        for (String sql : List.of("SELECT COUNT(*) FROM mutes WHERE player_id = ?1",
                "SELECT COUNT(*) FROM mute_events WHERE player_id = ?1",
                "SELECT COUNT(*) FROM offenses WHERE player_id = ?1",
                "SELECT COUNT(*) FROM consents WHERE player_id = ?1",
                "SELECT COUNT(*) FROM reports WHERE reporter_id = ?1 OR target_id = ?1")) {
            try (var s = c.prepareStatement(sql)) {
                s.setString(1, player.toString());
                try (var r = s.executeQuery()) {
                    left += r.next() ? r.getInt(1) : 0;
                }
            }
        }
        return left;
    }

    /** Writes one JSON file in the exports folder with everything stored about the player. Completes with its path. */
    public CompletableFuture<Path> export(UUID player, String name) {
        Instant now = Instant.now();
        PluginConfig cfg = config.get();
        CompletableFuture<MuteEvent.Page> history = mutes.history(player, 1, 1_000_000);
        CompletableFuture<List<EvidenceStore.Found>> found = evidence.find(player);
        JsonObject out = new JsonObject();
        out.addProperty("player", player.toString());
        out.addProperty("name", name);
        out.addProperty("exportedAt", now.toString());
        out.add("mute", mutes.get(player).map(PlayerData::mute).orElse(null));
        Offenses o = offenses.stored(player);
        JsonObject offences = new JsonObject();
        offences.addProperty("count", o.count());
        offences.addProperty("current", offenses.lookup(player, cfg.moderation().ladder().resetAfterDays()).join().count());
        if (o.count() > 0) {
            offences.addProperty("lastOffenceAt", Instant.ofEpochMilli(o.lastEpochMillis()).toString());
        }
        out.add("offences", offences);
        out.add("consent", consent.answer(player).map(PlayerData::consent).orElse(null));
        JsonArray reportList = new JsonArray();
        reportStore.involving(player).forEach(r -> reportList.add(report(r, player)));
        out.add("reports", reportList);
        out.add("bufferedTranscript", lines(buffer.since(player, Instant.EPOCH).stream()
                .map(TranscriptBuffer.Entry::line).toList()));
        return history.thenCombine(found, (page, evidenceFiles) -> {
            JsonArray events = new JsonArray();
            page.events().forEach(e -> events.add(event(e)));
            out.add("muteHistory", events);
            JsonArray files = new JsonArray();
            for (EvidenceStore.Found f : evidenceFiles) {
                JsonObject entry = new JsonObject();
                entry.addProperty("file", f.json().toString());
                JsonObject content = f.content().deepCopy();
                String id = player.toString();
                if (id.equals(string(content, "playerId")) || id.equals(string(content, "targetId"))) {
                    entry.addProperty("audioFile", f.audio() == null ? null : f.audio().toString());
                    if (!id.equals(string(content, "reporterId"))) {
                        content.remove("reporterId");
                        content.remove("reporterName");
                    }
                    entry.add("content", content);
                } else {
                    // A report the player filed holds the other player's speech, so only its existence is exported.
                    entry.addProperty("role", "reporter");
                    entry.addProperty("createdAt", string(content, "createdAt"));
                }
                files.add(entry);
            }
            out.add("evidence", files);
            return out;
        }).thenApplyAsync(json -> {
            try {
                JsonArray logged = new JsonArray();
                transcriptLog.lines(player).forEach(logged::add);
                json.add("transcriptLogs", logged);
                Files.createDirectories(exports);
                Path file = exports.resolve(player + "-" + STAMP.format(now) + ".json");
                Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, io);
    }

    /** Deletes records older than {@code privacy.retention} allows. Run hourly off the server and voice threads. */
    public CompletableFuture<Pruned> prune(Instant now) {
        PluginConfig cfg = config.get();
        PluginConfig.Retention r = cfg.retention();
        long millis = now.toEpochMilli();
        int offenceCount = offenses.prune(millis, cfg.moderation().ladder().resetAfterDays());
        int reportCount = reportStore.prune(now, r.reportDays());
        int consentCount = consent.prune(millis, r.consentDays(), online);
        CompletableFuture<Integer> events = r.muteHistoryDays() > 0
                ? mutes.pruneHistory(now.minus(Duration.ofDays(r.muteHistoryDays())).toEpochMilli())
                : CompletableFuture.completedFuture(0);
        CompletableFuture<int[]> files = CompletableFuture.supplyAsync(() ->
                new int[]{transcriptLog.prune(now, r.transcriptLogDays()), pruneExports(now, r.exportDays())}, io);
        return events.thenCombine(files, (eventCount, f) -> {
            Pruned pruned = new Pruned(offenceCount, reportCount, consentCount, eventCount, f[0], f[1]);
            if (pruned.total() > 0) {
                logger.info("Deleted old records under privacy.retention: reports " + pruned.reports()
                        + ", mute history entries " + pruned.muteEvents() + ", reset offence counts " + pruned.offences()
                        + ", consent answers " + pruned.consents() + ", transcript logs " + pruned.transcriptLogs()
                        + ", exports " + pruned.exports() + ".");
            }
            return pruned;
        });
    }

    private int pruneExports(Instant now, int days) {
        if (days <= 0) {
            return 0;
        }
        Instant cutoff = now.minus(Duration.ofDays(days));
        return deleteExportsIf(file -> Files.getLastModifiedTime(file).toInstant().isBefore(cutoff));
    }

    private int deleteExports(UUID player) {
        return deleteExportsIf(file -> file.getFileName().toString().startsWith(player.toString()));
    }

    @FunctionalInterface
    private interface FileTest {
        boolean test(Path file) throws IOException;
    }

    private int deleteExportsIf(FileTest test) {
        if (!Files.isDirectory(exports)) {
            return 0;
        }
        int deleted = 0;
        try (var list = Files.list(exports)) {
            for (Path file : list.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                if (test.test(file) && Files.deleteIfExists(file)) {
                    deleted++;
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not clean up " + exports, e);
        }
        return deleted;
    }

    private static JsonObject mute(MuteService.Mute m) {
        JsonObject json = new JsonObject();
        json.addProperty("since", Instant.ofEpochMilli(m.createdAt()).toString());
        json.addProperty("until", m.expiresAt() == MuteService.PERMANENT ? "permanent" : Instant.ofEpochMilli(m.expiresAt()).toString());
        json.addProperty("reason", m.reason());
        json.addProperty("by", m.actor());
        json.addProperty("automatic", m.automatic());
        return json;
    }

    private static JsonObject event(MuteEvent e) {
        JsonObject json = new JsonObject();
        json.addProperty("type", e.type().key());
        json.addProperty("at", Instant.ofEpochMilli(e.at()).toString());
        json.addProperty("name", e.playerName());
        json.addProperty("by", e.actor());
        json.addProperty("reason", e.reason());
        if (e.expiresAt() != null) {
            json.addProperty("until", e.expiresAt() == MuteService.PERMANENT ? "permanent"
                    : Instant.ofEpochMilli(e.expiresAt()).toString());
        }
        json.addProperty("automatic", e.automatic());
        return json;
    }

    private static JsonObject consent(ConsentService.Answer a) {
        JsonObject json = new JsonObject();
        json.addProperty("accepted", a.accepted());
        json.addProperty("rulesVersion", a.rulesVersion());
        json.addProperty("decidedAt", Instant.ofEpochMilli(a.decidedAt()).toString());
        json.addProperty("lastSeenAt", Instant.ofEpochMilli(a.lastSeenAt()).toString());
        return json;
    }

    /** A report about the player leaves out who filed it. */
    private static JsonObject report(ReportStore.StoredReport r, UUID player) {
        JsonObject json = new JsonObject();
        json.addProperty("id", r.id().toString());
        json.addProperty("createdAt", r.createdAt().toString());
        boolean filed = player.equals(r.reporterId());
        json.addProperty("role", filed ? "reporter" : "reported");
        if (filed) {
            json.addProperty("reporterName", r.reporterName());
        }
        json.addProperty("targetName", r.targetName());
        json.addProperty("windowSeconds", r.windowSeconds());
        json.addProperty("reviewed", r.reviewed());
        if (r.reviewedAt() != null) {
            json.addProperty("reviewedAt", r.reviewedAt().toString());
            json.addProperty("reviewer", r.reviewer());
        }
        if (!filed) {
            // A report the player filed holds the other player's speech, not theirs.
            json.add("lines", lines(r.lines()));
        }
        return json;
    }

    private static JsonArray lines(List<TranscriptLine> lines) {
        JsonArray array = new JsonArray();
        for (TranscriptLine line : lines) {
            JsonObject json = new JsonObject();
            json.addProperty("spokenAt", line.spokenAt().toString());
            json.addProperty("text", line.text());
            array.add(json);
        }
        return array;
    }

    private static String string(JsonObject json, String field) {
        var value = json.get(field);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }
}
