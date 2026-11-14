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
        return cleanup(Instant.now());
    }

    /**
     * Deletes expired evidence, then the oldest until under the size cap. An incident's JSON and WAV share a name and
     * are deleted together, so neither outlives the other.
     */
    public CompletableFuture<CleanupResult> cleanup(Instant now) {
        return async(() -> {
            EvidenceSettings current = settings.get();
            Path root = current.directory();
            if (!Files.exists(root)) return new CleanupResult(0, 0, 0);
            List<Evidence> items = new ArrayList<>(scan(root));
            items.sort(Comparator.comparing(Evidence::modified).thenComparing(e -> e.stem().toString()));
            long total = items.stream().mapToLong(Evidence::size).sum();
            long freed = 0;
            int deleted = 0;
            Instant cutoff = current.retentionDays() > 0 ? now.minus(Duration.ofDays(current.retentionDays())) : null;
            for (Iterator<Evidence> iterator = items.iterator(); iterator.hasNext();) {
                Evidence item = iterator.next();
                if (cutoff != null && item.modified().isBefore(cutoff)) {
                    deleted += delete(item);
                    total -= item.size();
                    freed += item.size();
                    iterator.remove();
                }
            }
            for (Evidence item : items) {
                if (current.maxTotalBytes() <= 0 || total <= current.maxTotalBytes()) break;
                deleted += delete(item);
                total -= item.size();
                freed += item.size();
            }
            removeEmptyDateDirectories(root);
            return new CleanupResult(deleted, freed, total);
        });
    }

    /** One saved record: its JSON (when written) and WAV (when audio was kept), named {@code stem.json} and {@code stem.wav}. */
    private record Evidence(Path stem, List<Path> files, Instant modified, long size) {}

    private static List<Evidence> scan(Path root) throws IOException {
        Map<Path, List<Path>> byStem = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".json") || name.endsWith(".wav")) {
                    Path stem = path.resolveSibling(name.substring(0, name.lastIndexOf('.')));
                    byStem.computeIfAbsent(stem, k -> new ArrayList<>()).add(path);
                }
            }
        }
        List<Evidence> items = new ArrayList<>();
        for (var entry : byStem.entrySet()) {
            Instant modified = Instant.MIN;
            long size = 0;
            for (Path file : entry.getValue()) {
                Instant m = Files.getLastModifiedTime(file).toInstant();
                if (m.isAfter(modified)) modified = m;
                size += Files.size(file);
            }
            items.add(new Evidence(entry.getKey(), List.copyOf(entry.getValue()), modified, size));
        }
        return items;
    }

    private static int delete(Evidence item) throws IOException {
        int deleted = 0;
        for (Path file : item.files()) {
            if (Files.deleteIfExists(file)) deleted++;
        }
        return deleted;
    }

    private static void removeEmptyDateDirectories(Path root) throws IOException {
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
    }

    /** A saved record that names the player as speaker, report target or reporter. */
    public record Found(Path json, JsonObject content, @Nullable Path audio) {}

    private static final List<String> PLAYER_FIELDS = List.of("playerId", "targetId", "reporterId");

    /** Every saved record that names the player. */
    public CompletableFuture<List<Found>> find(UUID player) {
        return async(() -> {
            Path root = settings.get().directory();
            if (!Files.exists(root)) return List.of();
            List<Found> found = new ArrayList<>();
            for (Evidence item : scan(root)) {
                Path json = item.files().stream().filter(p -> p.toString().endsWith(".json")).findFirst().orElse(null);
                if (json == null) continue;
                JsonObject content;
                try {
                    content = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue; // not a VoiceWarden record
                }
                if (names(content, player)) {
                    Path audio = item.files().stream().filter(p -> p.toString().endsWith(".wav")).findFirst().orElse(null);
                    found.add(new Found(json, content, audio));
                }
            }
            return List.copyOf(found);
        });
    }

    /** Deletes every saved record that names the player, JSON and WAV. Completes with the number of files deleted. */
    public CompletableFuture<Integer> forget(UUID player) {
        return find(player).thenApplyAsync(found -> {
            int deleted = 0;
            try {
                for (Found f : found) {
                    if (Files.deleteIfExists(f.json())) deleted++;
                    if (f.audio() != null && Files.deleteIfExists(f.audio())) deleted++;
                }
                removeEmptyDateDirectories(settings.get().directory());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return deleted;
        }, ioExecutor);
    }

    private static boolean names(JsonObject content, UUID player) {
        String id = player.toString();
        for (String field : PLAYER_FIELDS) {
            JsonElement value = content.get(field);
            if (value != null && value.isJsonPrimitive() && id.equalsIgnoreCase(value.getAsString())) return true;
        }
        return false;
    }

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
