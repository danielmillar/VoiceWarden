package dev.danielmillar.voicewarden.stt;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DownloaderTest {
    @TempDir Path folder;
    private final byte[] content = "deterministic local model payload for SHA-256 verification".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final AtomicReference<String> range = new AtomicReference<>();
    private HttpServer server;
    private HttpClient client;
    private Downloader downloader;
    private URI uri;
    private volatile boolean honorRange;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            try (exchange) {
                String requested = exchange.getRequestHeaders().getFirst("Range");
                range.set(requested);
                int start = requested == null || !honorRange ? 0 : Integer.parseInt(requested.substring(6, requested.length() - 1));
                if (start > 0) exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (content.length - 1) + "/" + content.length);
                exchange.sendResponseHeaders(start > 0 ? 206 : 200, content.length - start);
                exchange.getResponseBody().write(content, start, content.length - start);
            }
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        downloader = new Downloader(client, Logger.getLogger("DownloaderTest"));
    }

    @AfterEach void cleanup() {
        if (server != null) server.stop(0);
        if (client != null) client.close();
    }

    @Test void sha256VerifiedDownloadMovesPartFileToTarget() throws Exception {
        Path target = folder.resolve("model.onnx");
        downloader.download(uri, target, content.length, sha256(), "test model");
        assertArrayEquals(content, Files.readAllBytes(target));
        assertFalse(Files.exists(part(target)));
        assertNull(range.get());
    }

    @Test void checksumMismatchDeletesPartAndThrowsWithoutRetry() throws Exception {
        Path target = folder.resolve("model.onnx");
        var error = assertThrows(Downloader.ChecksumException.class,
                () -> downloader.download(uri, target, content.length, "00".repeat(32), "test model"));
        assertTrue(error.getMessage().contains("SHA-256 mismatch"));
        assertFalse(Files.exists(part(target)));
        assertFalse(Files.exists(target));
    }

    @Test void rangeResumeAppends206AndHashesBothExistingAndNewBytes() throws Exception {
        honorRange = true;
        Path target = folder.resolve("model.onnx");
        Files.write(part(target), Arrays.copyOf(content, 13));
        downloader.download(uri, target, content.length, sha256(), "test model");
        assertEquals("bytes=13-", range.get());
        assertArrayEquals(content, Files.readAllBytes(target));
        assertFalse(Files.exists(part(target)));
    }

    @Test void serverIgnoringRangeRestartsRatherThanDuplicatingPrefix() throws Exception {
        Path target = folder.resolve("model.onnx");
        Files.write(part(target), Arrays.copyOf(content, 13));
        downloader.download(uri, target, content.length, sha256(), "test model");
        assertEquals("bytes=13-", range.get());
        assertArrayEquals(content, Files.readAllBytes(target));
    }

    private static Path part(Path target) { return target.resolveSibling(target.getFileName() + ".part"); }
    private String sha256() throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
}
