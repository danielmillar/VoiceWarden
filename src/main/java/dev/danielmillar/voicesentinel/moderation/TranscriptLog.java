package dev.danielmillar.voicesentinel.moderation;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

/** Optional daily transcript log files. Lines are queued lock-free and appended in batches once a second on IO. */
public final class TranscriptLog implements AutoCloseable {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

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

    @Override
    public void close() {
        if (flusher != null) {
            flusher.cancel(false);
        }
        flush();
    }
}
