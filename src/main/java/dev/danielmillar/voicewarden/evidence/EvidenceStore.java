package dev.danielmillar.voicewarden.evidence;

import com.google.gson.*;
import dev.danielmillar.voicewarden.audio.WavEncoder;
import dev.danielmillar.voicewarden.moderation.Incident;
import dev.danielmillar.voicewarden.report.VoiceReport;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** All settings reads, serialization, filesystem access and failure logging run on the IO executor. */
public final class EvidenceStore {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) -> new JsonPrimitive(value.toString()))
            .registerTypeAdapter(Duration.class, (JsonSerializer<Duration>) (value, type, context) -> new JsonPrimitive(value.toString()))
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override public boolean shouldSkipField(FieldAttributes field) {
                    return field.getName().equals("audio") && field.getDeclaredClass() == short[].class;
                }
                @Override public boolean shouldSkipClass(Class<?> type) { return false; }
            }).create();

    private final Supplier<EvidenceSettings> settings;
    private final Executor ioExecutor;
    private final Logger logger;

    public EvidenceStore(Supplier<EvidenceSettings> settings, Executor ioExecutor, Logger logger) {
        this.settings = Objects.requireNonNull(settings);
        this.ioExecutor = Objects.requireNonNull(ioExecutor);
        this.logger = Objects.requireNonNull(logger);
    }

    public CompletableFuture<@Nullable Path> saveIncident(Incident incident) {
        return async(() -> {
            EvidenceSettings current = settings.get();
            if (current.mode() == EvidenceSettings.Mode.NONE) return null;
            return save(current, incident, incident.detectedAt(), incident.playerName(), "incident", incident.shortId(),
                    incident.audio(), incident.sampleRate());
        });
    }

    public CompletableFuture<@Nullable Path> saveReport(VoiceReport report) {
        return async(() -> {
            EvidenceSettings current = settings.get();
            if (current.mode() == EvidenceSettings.Mode.NONE) return null;
            return save(current, report, report.createdAt(), report.targetName(), "report", report.shortId(),
                    report.audio(), report.sampleRate());
        });
    }

    public CompletableFuture<@Nullable Path> saveUtterance(UUID playerId, String playerName, Instant spokenAt,
                                                         String transcript, short[] audio, int sampleRate) {
        return async(() -> {
            EvidenceSettings current = settings.get();
            if (current.mode() != EvidenceSettings.Mode.ALL) return null;
            UUID id = UUID.randomUUID();
            Utterance utterance = new Utterance(id, playerId, playerName, spokenAt, transcript, sampleRate);
            return save(current, utterance, spokenAt, playerName, "utterance", id.toString().substring(0, 8), audio, sampleRate);
        });
    }

    private record Utterance(UUID id, UUID playerId, String playerName, Instant spokenAt, String transcript, int sampleRate) {}

    private Path save(EvidenceSettings current, Object record, Instant time, String player, String kind,
                      String shortId, short[] audio, int sampleRate) throws IOException {
        Path directory = current.directory().resolve(DATE.format(time));
        Files.createDirectories(directory);
        String safePlayer = player.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safePlayer.isEmpty()) safePlayer = "player";
        if (safePlayer.length() > 64) safePlayer = safePlayer.substring(0, 64);
        String stem = TIME.format(time) + "_" + safePlayer + "_" + kind + "_" + shortId;
        Path jsonPath = directory.resolve(stem + ".json");
        Path audioPath = current.saveAudio() && audio.length > 0 ? directory.resolve(stem + ".wav") : null;
        JsonObject json = GSON.toJsonTree(record).getAsJsonObject();
        json.addProperty("schema", 1);
        json.add("audioFile", audioPath == null ? JsonNull.INSTANCE : new JsonPrimitive(audioPath.getFileName().toString()));
        if (audioPath != null) atomicWrite(audioPath, WavEncoder.encode(audio, sampleRate));
        atomicWrite(jsonPath, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        return jsonPath;
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".voicewarden-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record CleanupResult(int filesDeleted, long bytesFreed, long bytesRemaining) {}

    public CompletableFuture<CleanupResult> cleanup() {
        return async(() -> {
            EvidenceSettings current = settings.get();
            Path root = current.directory();
            if (!Files.exists(root)) return new CleanupResult(0, 0, 0);
            List<StoredFile> files = new ArrayList<>();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".wav")).toList()) {
                    files.add(new StoredFile(path, Files.getLastModifiedTime(path).toInstant(), Files.size(path)));
                }
            }
            files.sort(Comparator.comparing(StoredFile::modified).thenComparing(f -> f.path().toString()));
            long total = files.stream().mapToLong(StoredFile::size).sum();
            long freed = 0;
            int deleted = 0;
            Instant cutoff = current.retentionDays() > 0 ? Instant.now().minus(Duration.ofDays(current.retentionDays())) : null;
            for (Iterator<StoredFile> iterator = files.iterator(); iterator.hasNext();) {
                StoredFile file = iterator.next();
                if (cutoff != null && file.modified().isBefore(cutoff) && Files.deleteIfExists(file.path())) {
                    total -= file.size();
                    freed += file.size();
                    deleted++;
                    iterator.remove();
                }
            }
            for (StoredFile file : files) {
                if (current.maxTotalBytes() <= 0 || total <= current.maxTotalBytes()) break;
                if (Files.deleteIfExists(file.path())) {
                    total -= file.size();
                    freed += file.size();
                    deleted++;
                }
            }
            try (var children = Files.list(root)) {
                for (Path dateDirectory : children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}")).toList()) {
                    try (var contents = Files.list(dateDirectory)) {
                        if (contents.findAny().isEmpty()) {
                            try { Files.delete(dateDirectory); } catch (DirectoryNotEmptyException ignored) { /* concurrent save */ }
                        }
                    }
                }
            }
            return new CleanupResult(deleted, freed, total);
        });
    }

    private record StoredFile(Path path, Instant modified, long size) {}
    @FunctionalInterface private interface IOOperation<T> { T run() throws Exception; }

    private <T> CompletableFuture<T> async(IOOperation<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            ioExecutor.execute(() -> {
                try {
                    future.complete(operation.run());
                } catch (Exception failure) {
                    logFailure(failure);
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            // Even failure logging must not perform IO on the caller if the supplied executor has stopped.
            Thread.startVirtualThread(() -> logFailure(rejected));
            future.completeExceptionally(rejected);
        }
        return future;
    }

    private void logFailure(Exception failure) {
        try { logger.log(Level.WARNING, "Voice evidence operation failed", failure); }
        catch (RuntimeException ignored) { /* a broken log handler must not leave the future incomplete */ }
    }
}
