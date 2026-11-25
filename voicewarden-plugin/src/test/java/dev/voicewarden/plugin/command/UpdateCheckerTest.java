package dev.voicewarden.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "{\"tag_name\":\"v1.2.0\",\"name\":\"VoiceWarden 1.2.0\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/latest", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private UpdateChecker checker(String current) {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
        return new UpdateChecker(uri, current, Duration.ofSeconds(2));
    }

    private static UpdateChecker.Result await(UpdateChecker checker) throws Exception {
        try (checker) {
            return checker.check().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsANewerRelease() throws Exception {
        UpdateChecker.Result result = await(checker("1.0.0-SNAPSHOT"));
        assertEquals(UpdateChecker.Status.UPDATE_AVAILABLE, result.status());
        assertEquals("1.2.0", result.latest());
    }

    @Test
    void reportsUpToDate() throws Exception {
        assertEquals(UpdateChecker.Status.UP_TO_DATE, await(checker("1.2.0")).status());
        assertEquals(UpdateChecker.Status.UP_TO_DATE, await(checker("1.3.0")).status());
    }

    @Test
    void httpErrorsAndUnreadableBodiesFailGracefully() throws Exception {
        status = 404;
        UpdateChecker.Result notFound = await(checker("1.0.0"));
        assertEquals(UpdateChecker.Status.FAILED, notFound.status());
        assertTrue(notFound.message().contains("404"));
        status = 200;
        body = "<html>rate limited</html>";
        assertEquals(UpdateChecker.Status.FAILED, await(checker("1.0.0")).status());
    }

    @Test
    void unreachableServerFailsGracefully() throws Exception {
        URI closed = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
        server.stop(0);
        try (UpdateChecker checker = new UpdateChecker(closed, "1.0.0", Duration.ofSeconds(1))) {
            UpdateChecker.Result result = checker.check().get(5, TimeUnit.SECONDS);
            assertEquals(UpdateChecker.Status.FAILED, result.status());
            assertTrue(!result.message().isBlank());
        }
    }

    @Test
    void versionsCompareNumericallyAndPreReleasesSortFirst() {
        assertTrue(UpdateChecker.compare("1.10.0", "1.9.0") > 0);
        assertTrue(UpdateChecker.compare("v1.0.0", "1.0.0-SNAPSHOT") > 0);
        assertEquals(0, UpdateChecker.compare("1.0", "1.0.0"));
        assertEquals(Optional.of("2.0.1"), UpdateChecker.parseVersion("2.0.1\n"));
        assertEquals(Optional.empty(), UpdateChecker.parseVersion("nope"));
        assertEquals("https://api.github.com/repos/danielmillar/VoiceWarden/releases/latest",
                UpdateChecker.DEFAULT_RELEASES_URI.toString());
    }
}
