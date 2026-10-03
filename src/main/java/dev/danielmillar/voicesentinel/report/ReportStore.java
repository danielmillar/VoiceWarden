package dev.danielmillar.voicesentinel.report;

import com.google.gson.reflect.TypeToken;
import dev.danielmillar.voicesentinel.moderation.TranscriptLine;
import dev.danielmillar.voicesentinel.util.JsonFiles;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Persistent voice-report inbox ({@code data/reports.json}). Thread-safe; writes are asynchronous and coalesced. */
public final class ReportStore {

    private static final int MAX_STORED = 1_000;

    public record StoredReport(UUID id, Instant createdAt, @Nullable UUID reporterId, String reporterName, UUID targetId,
                               String targetName, long windowSeconds, List<TranscriptLine> lines, boolean reviewed,
                               @Nullable String reviewer, @Nullable Instant reviewedAt) {
        public String shortId() {
            return id.toString().substring(0, 8);
        }
    }

    private final ConcurrentHashMap<UUID, StoredReport> reports = new ConcurrentHashMap<>();
    private final Path file;
    private final Executor io;
    private final Logger logger;
    private final AtomicBoolean savePending = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Object saveLock = new Object();

    public ReportStore(Path dataFolder, Executor io, Logger logger) {
        this.file = dataFolder.resolve("data").resolve("reports.json");
        this.io = io;
        this.logger = logger;
    }

    public void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<StoredReport> stored = JsonFiles.read(file, new TypeToken<List<StoredReport>>() { }.getType());
            if (stored != null) {
                stored.stream().filter(r -> r != null && r.id() != null).forEach(r -> reports.put(r.id(), r));
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not read " + file + "; starting with an empty report inbox", e);
        }
    }

    public void add(VoiceReport report) {
        reports.put(report.id(), new StoredReport(report.id(), report.createdAt(), report.reporterId(),
                report.reporterName(), report.targetId(), report.targetName(), report.window().toSeconds(),
                report.lines(), false, null, null));
        trim();
        save();
    }

    public List<StoredReport> open() {
        return reports.values().stream().filter(r -> !r.reviewed())
                .sorted(Comparator.comparing(StoredReport::createdAt).reversed()).toList();
    }

    /** Finds by full id or unique id prefix (as shown in chat). */
    public Optional<StoredReport> find(String idOrPrefix) {
        String needle = idOrPrefix.toLowerCase(Locale.ROOT);
        List<StoredReport> hits = new ArrayList<>();
        for (StoredReport r : reports.values()) {
            if (r.id().toString().startsWith(needle)) {
                hits.add(r);
            }
        }
        return hits.size() == 1 ? Optional.of(hits.getFirst()) : Optional.empty();
    }

    public boolean markReviewed(UUID id, String reviewer) {
        StoredReport updated = reports.computeIfPresent(id, (k, r) -> new StoredReport(r.id(), r.createdAt(),
                r.reporterId(), r.reporterName(), r.targetId(), r.targetName(), r.windowSeconds(), r.lines(), true,
                reviewer, Instant.now()));
        if (updated != null) {
            save();
        }
        return updated != null;
    }

    private void trim() {
        if (reports.size() <= MAX_STORED) {
            return;
        }
        reports.values().stream()
                .sorted(Comparator.comparing(StoredReport::reviewed).reversed().thenComparing(StoredReport::createdAt))
                .limit(reports.size() - MAX_STORED)
                .forEach(r -> reports.remove(r.id()));
    }

    private void save() {
        dirty.set(true);
        if (savePending.compareAndSet(false, true)) {
            io.execute(this::saveNow);
        }
    }

    /** Serialised and skipped when nothing changed (so the shutdown call is usually free). */
    public void saveNow() {
        synchronized (saveLock) {
            savePending.set(false);
            if (!dirty.getAndSet(false)) {
                return;
            }
            try {
                JsonFiles.writeAtomically(file, new ArrayList<>(reports.values()));
            } catch (IOException e) {
                dirty.set(true);
                logger.log(Level.WARNING, "Could not save " + file, e);
            }
        }
    }
}
