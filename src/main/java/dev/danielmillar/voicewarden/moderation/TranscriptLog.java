package dev.danielmillar.voicewarden.moderation;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional daily transcript log files. Lines are queued lock-free and appended in batches once a second on IO. */
public final class TranscriptLog implements AutoCloseable {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final Pattern FILE = Pattern.compile("transcripts-(\\d{4}-\\d{2}-\\d{2})\\.log");

    private record Line(Instant at, UUID player, String name, String text) {
    }

    private final ConcurrentLinkedQueue<Line> pending = new ConcurrentLinkedQueue<>();
    private final Path directory;
    private final Executor io;
    private final Logger logger;
    private ScheduledFuture<?> flusher;

    public TranscriptLog(Path directory, Executor io, Logger logger) {
        this.directory = directory;
        this.io = io;
        this.logger = logger;
    }

    public void start(ScheduledExecutorService timer) {
        flusher = timer.scheduleWithFixedDelay(() -> {
            if (!pending.isEmpty()) {
                io.execute(this::flush);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void append(Instant at, UUID player, String name, String text) {
        pending.add(new Line(at, player, name, text));
    }

    private synchronized void flush() {
        List<Line> batch = new ArrayList<>();
        Line line;
        while ((line = pending.poll()) != null) {
            batch.add(line);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(directory);
            String day = null;
            Writer writer = null;
            try {
                for (Line l : batch) {
                    String d = DAY.format(l.at());
                    if (!d.equals(day)) {
                        if (writer != null) {
                            writer.close();
                        }
                        day = d;
                        writer = Files.newBufferedWriter(directory.resolve("transcripts-" + d + ".log"), StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                    writer.write("[" + TIME.format(l.at()) + "] " + l.name() + " (" + l.player() + "): "
                            + l.text().replace('\n', ' ') + System.lineSeparator());
                }
            } finally {
                if (writer != null) {
                    writer.close();
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write transcript log", e);
        }
    }

    /** Deletes daily files dated more than {@code days} days before {@code now}. IO thread. */
    public synchronized int prune(Instant now, int days) {
        if (days <= 0) {
            return 0;
        }
        LocalDate oldestKept = LocalDate.ofInstant(now, ZoneId.systemDefault()).minusDays(days);
        int deleted = 0;
        for (Path file : files()) {
            LocalDate day = day(file);
            if (day != null && day.isBefore(oldestKept)) {
                try {
                    if (Files.deleteIfExists(file)) {
                        deleted++;
                    }
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not delete old transcript log " + file, e);
                }
            }
        }
        return deleted;
    }

    /** The player's logged lines, oldest file first. IO thread. */
    public synchronized List<String> lines(UUID player) throws IOException {
        flush();
        String marker = marker(player);
        List<String> out = new ArrayList<>();
        for (Path file : files()) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.contains(marker)) {
                    out.add(file.getFileName() + " " + line);
                }
            }
        }
        return out;
    }

    /** Removes the player's lines from every file, including lines not written yet. Returns how many. IO thread. */
    public synchronized int forget(UUID player) throws IOException {
        int removed = 0;
        for (var it = pending.iterator(); it.hasNext(); ) {
            if (it.next().player().equals(player)) {
                it.remove();
                removed++;
            }
        }
        String marker = marker(player);
        for (Path file : files()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> kept = lines.stream().filter(line -> !line.contains(marker)).toList();
            if (kept.size() == lines.size()) {
                continue;
            }
            removed += lines.size() - kept.size();
            if (kept.isEmpty()) {
                Files.delete(file);
                continue;
            }
            Path temporary = Files.createTempFile(directory, ".transcripts-", ".tmp");
            try {
                Files.write(temporary, kept, StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return removed;
    }

    private static String marker(UUID player) {
        return " (" + player + "): ";
    }

    private List<Path> files() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var list = Files.list(directory)) {
            return list.filter(p -> day(p) != null).sorted().toList();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not list transcript logs in " + directory, e);
            return List.of();
        }
    }

    private static @Nullable LocalDate day(Path file) {
        Matcher m = FILE.matcher(file.getFileName().toString());
        if (!m.matches()) {
            return null;
        }
        try {
            return LocalDate.parse(m.group(1));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Override
    public void close() {
        if (flusher != null) {
            flusher.cancel(false);
        }
        flush();
    }
}
