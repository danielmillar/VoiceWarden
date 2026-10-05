package dev.danielmillar.voicewarden.stt;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Blocking HTTPS downloader for models and native libraries; call it from an IO (virtual) thread only.
 * Downloads to {@code <file>.part}, resumes interrupted downloads with HTTP Range requests, aborts stalled transfers,
 * verifies size and SHA-256, then atomically moves the file into place.
 */
public final class Downloader {

    private static final int ATTEMPTS = 5;
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final HttpClient http;
    private final Logger logger;

    public Downloader(HttpClient http, Logger logger) {
        this.http = http;
        this.logger = logger;
    }

    public void download(URI uri, Path target, long expectedSize, @Nullable String sha256, String label)
            throws IOException, InterruptedException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                attempt(uri, target, expectedSize, sha256, label);
                return;
            } catch (ChecksumException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                logger.warning("Download of " + label + " failed (attempt " + attempt + "/" + ATTEMPTS + "): " + e.getMessage());
                Thread.sleep(Math.min(30_000, 2_000L << (attempt - 1)));
            }
        }
        throw new IOException("Could not download " + label + " from " + uri, last);
    }

    private void attempt(URI uri, Path target, long expectedSize, @Nullable String sha256, String label)
            throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long existing = Files.exists(part) ? Files.size(part) : 0;
        if (expectedSize > 0 && existing >= expectedSize) {
            Files.delete(part);
            existing = 0;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "VoiceWarden-Minecraft-Plugin")
                .GET();
        if (existing > 0) {
            request.header("Range", "bytes=" + existing + "-");
        }
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        boolean append = status == 206 && existing > 0;
        if (!append && status != 200) {
            response.body().close();
            throw new IOException("HTTP " + status);
        }
        if (!append) {
            existing = 0;
        }
        MessageDigest digest = sha256 == null ? null : sha256Digest();
        if (digest != null && append) {
            try (InputStream in = Files.newInputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    digest.update(buf, 0, n);
                }
            }
        }
        long total = expectedSize > 0 ? expectedSize
                : response.headers().firstValueAsLong("Content-Length").orElse(-1) + (append ? existing : 0);
        if (existing == 0) {
            logger.info("Downloading " + label + (total > 0 ? " (" + mib(total) + ")" : "") + "...");
        } else {
            logger.info("Resuming download of " + label + " at " + mib(existing) + "...");
        }

        InputStream body = response.body();
        AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        Thread watchdog = Thread.ofVirtual().name("VoiceWarden-DownloadWatchdog").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(5_000);
                    if (System.nanoTime() - lastProgress.get() > STALL_NANOS) {
                        body.close(); // unblocks the read below with an IOException
                        return;
                    }
                }
            } catch (InterruptedException | IOException ignored) {
                // finished
            }
        });
        long done = existing;
        try (InputStream in = body;
             OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[1 << 17];
            int lastDecile = total > 0 ? (int) (done * 10 / total) : 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("download cancelled");
                }
                out.write(buf, 0, n);
                if (digest != null) {
                    digest.update(buf, 0, n);
                }
                done += n;
                lastProgress.set(System.nanoTime());
                if (total > 0) {
                    int decile = (int) (done * 10 / total);
                    if (decile > lastDecile && decile < 10) {
                        lastDecile = decile;
                        logger.info("  " + label + ": " + decile * 10 + "% (" + mib(done) + " / " + mib(total) + ")");
                    }
                }
            }
        } finally {
            watchdog.interrupt();
        }
        if (expectedSize > 0 && done != expectedSize) {
            throw new IOException("incomplete download: got " + done + " of " + expectedSize + " bytes");
        }
        if (digest != null) {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(sha256)) {
                Files.deleteIfExists(part);
                throw new ChecksumException("SHA-256 mismatch for " + label + ": expected " + sha256 + " but got " + actual
                        + ". The file was deleted; it will be downloaded again on the next start.");
            }
        }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("Downloaded " + label + " (" + mib(done) + ")");
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
    }

    static final class ChecksumException extends IOException {
        ChecksumException(String message) {
            super(message);
        }
    }
}
