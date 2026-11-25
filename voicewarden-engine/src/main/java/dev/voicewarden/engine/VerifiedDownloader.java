package dev.voicewarden.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VerifiedDownloader {

    public static final String PART_SUFFIX = ".part";
    static final String USER_AGENT = "VoiceWarden-Engine";

    private static final Logger LOG = LoggerFactory.getLogger(VerifiedDownloader.class);
    private static final int BUFFER = 1 << 16;
    private static final int HTTP_OK = 200;
    private static final int PROGRESS_STEP_PERCENT = 10;
    private static final long PROGRESS_STEP_BYTES = 64L * 1024 * 1024;

    private final HttpClient client;
    private final Duration responseTimeout;

    public VerifiedDownloader(HttpClient client, Duration responseTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
    }

    public static VerifiedDownloader createDefault() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        return new VerifiedDownloader(client, Duration.ofSeconds(60));
    }

    public void download(URI url, Path destination, Optional<String> expectedSha256) throws IOException {
        Path part = destination.resolveSibling(destination.getFileName() + PART_SUFFIX);
        Files.createDirectories(destination.toAbsolutePath().getParent());
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(responseTimeout)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while downloading " + url);
        }
        MessageDigest digest = Sha256.newDigest();
        try (InputStream body = response.body()) {
            if (response.statusCode() != HTTP_OK) {
                throw new IOException("Download of " + url + " failed with HTTP " + response.statusCode());
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            LOG.info("Downloading {} ({})", url, length > 0 ? formatBytes(length) : "unknown size");
            streamTo(body, part, digest, length);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        String actual = Sha256.hex(digest);
        if (expectedSha256.isPresent()) {
            try {
                Sha256.verify(expectedSha256.get(), actual, part);
            } catch (IOException e) {
                Files.deleteIfExists(part);
                throw e;
            }
        } else {
            LOG.warn("No SHA-256 is pinned for {}; downloaded file hashes to {}. Place '{}' next to it to pin it.",
                    url, actual, destination.getFileName() + Sha256.SIDECAR_SUFFIX);
        }
        Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void streamTo(InputStream body, Path part, MessageDigest digest, long length) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long received = 0;
        long nextReport = length > 0 ? length * PROGRESS_STEP_PERCENT / 100 : PROGRESS_STEP_BYTES;
        try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int read;
            while ((read = body.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                received += read;
                if (received >= nextReport) {
                    if (length > 0) {
                        LOG.info("  {}% ({} of {})", received * 100 / length, formatBytes(received), formatBytes(length));
                        nextReport += length * PROGRESS_STEP_PERCENT / 100;
                    } else {
                        LOG.info("  {} received", formatBytes(received));
                        nextReport += PROGRESS_STEP_BYTES;
                    }
                }
            }
        }
        if (length > 0 && received != length) {
            throw new IOException("Download was cut short: received " + received + " of " + length + " bytes");
        }
    }

    static String formatBytes(long bytes) {
        double mib = bytes / (1024.0 * 1024.0);
        return mib >= 1 ? String.format(Locale.ROOT, "%.1f MiB", mib) : bytes + " B";
    }
}
