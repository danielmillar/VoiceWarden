package dev.voicewarden.core.moderation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ConsentStore {

    public enum Decision {
        ACCEPTED,
        DENIED
    }

    public record ConsentRecord(Decision decision, int rulesVersion) {

        public ConsentRecord {
            Objects.requireNonNull(decision, "decision");
        }
    }

    private static final String HEADER = "# VoiceWarden consent records: uuid=ACCEPTED:<rules_version> or uuid=DENIED:<rules_version>";

    private final Path file;
    private final Map<UUID, ConsentRecord> records = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public ConsentStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public Path file() {
        return file;
    }

    public List<String> load() throws IOException {
        synchronized (writeLock) {
            List<String> problems = new ArrayList<>();
            Map<UUID, ConsentRecord> loaded = new ConcurrentHashMap<>();
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    Optional<Map.Entry<UUID, ConsentRecord>> parsed = parseLine(line);
                    if (parsed.isPresent()) {
                        loaded.put(parsed.get().getKey(), parsed.get().getValue());
                    } else {
                        problems.add(file.getFileName() + " line " + (i + 1) + " is not uuid=ACCEPTED:<version>: " + line);
                    }
                }
            }
            records.keySet().retainAll(loaded.keySet());
            records.putAll(loaded);
            return problems;
        }
    }

    public ConsentStatus status(UUID playerId, int currentRulesVersion) {
        ConsentRecord record = records.get(playerId);
        if (record == null) {
            return ConsentStatus.PENDING;
        }
        if (record.rulesVersion() < currentRulesVersion) {
            return ConsentStatus.OUTDATED;
        }
        return record.decision() == Decision.ACCEPTED ? ConsentStatus.ACCEPTED : ConsentStatus.DENIED;
    }

    public Optional<ConsentRecord> record(UUID playerId) {
        return Optional.ofNullable(records.get(playerId));
    }

    public void accept(UUID playerId, int rulesVersion) throws IOException {
        put(playerId, new ConsentRecord(Decision.ACCEPTED, rulesVersion));
    }

    public boolean acceptIfNotDenied(UUID playerId, int rulesVersion) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (writeLock) {
            ConsentRecord existing = records.get(playerId);
            if (existing != null && existing.decision() == Decision.DENIED) {
                return false;
            }
            if (existing != null && existing.decision() == Decision.ACCEPTED && existing.rulesVersion() >= rulesVersion) {
                return false;
            }
            ConsentRecord record = new ConsentRecord(Decision.ACCEPTED, rulesVersion);
            Map<UUID, ConsentRecord> next = new TreeMap<>(records);
            next.put(playerId, record);
            save(next);
            records.put(playerId, record);
            return true;
        }
    }

    public void deny(UUID playerId, int rulesVersion) throws IOException {
        put(playerId, new ConsentRecord(Decision.DENIED, rulesVersion));
    }

    public void reset(UUID playerId) throws IOException {
        synchronized (writeLock) {
            if (!records.containsKey(playerId)) {
                return;
            }
            Map<UUID, ConsentRecord> next = new TreeMap<>(records);
            next.remove(playerId);
            save(next);
            records.remove(playerId);
        }
    }

    private void put(UUID playerId, ConsentRecord record) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (writeLock) {
            Map<UUID, ConsentRecord> next = new TreeMap<>(records);
            next.put(playerId, record);
            save(next);
            records.put(playerId, record);
        }
    }

    private void save(Map<UUID, ConsentRecord> snapshot) throws IOException {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        snapshot.forEach((id, record) ->
                out.append(id).append('=').append(record.decision()).append(':').append(record.rulesVersion()).append('\n'));
        Path absolute = file.toAbsolutePath();
        Path directory = absolute.getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, out, StandardCharsets.UTF_8);
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Optional<Map.Entry<UUID, ConsentRecord>> parseLine(String line) {
        int equals = line.indexOf('=');
        int colon = line.lastIndexOf(':');
        if (equals <= 0 || colon <= equals) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(line.substring(0, equals).strip());
            Decision decision = Decision.valueOf(line.substring(equals + 1, colon).strip().toUpperCase(Locale.ROOT));
            int version = Integer.parseInt(line.substring(colon + 1).strip());
            return Optional.of(Map.entry(id, new ConsentRecord(decision, version)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
