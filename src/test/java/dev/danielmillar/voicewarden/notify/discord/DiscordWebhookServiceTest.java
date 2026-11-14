package dev.danielmillar.voicewarden.notify.discord;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import dev.danielmillar.voicewarden.TestFixtures;
import dev.danielmillar.voicewarden.audio.WavEncoder;
import dev.danielmillar.voicewarden.moderation.*;
import dev.danielmillar.voicewarden.report.VoiceReport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

class DiscordWebhookServiceTest {
    private record Captured(byte[] bytes, String contentType, String userAgent, String path, long receivedAt) {
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
        JsonObject json() {
            String text = text();
            if (contentType.startsWith("multipart/")) {
                int start = text.indexOf("\r\n\r\n") + 4;
                return JsonParser.parseString(text.substring(start, text.indexOf("\r\n--", start))).getAsJsonObject();
            }
            return JsonParser.parseString(text).getAsJsonObject();
        }
    }
    @FunctionalInterface private interface Reply { void handle(HttpExchange exchange, int attempt) throws Exception; }
    private HttpServer server;
    private ExecutorService serverExecutor;
    private HttpClient http;
    private AtomicReference<DiscordSettings> config;
    private DiscordWebhookService service;
    private BlockingQueue<Captured> requests;
    private List<LogRecord> logs;
    private BlockingQueue<LogRecord> logEvents;
    private volatile Reply reply;
    private AtomicInteger attempts;
    private String url;
    private Logger logger;

    @BeforeEach void setup() throws Exception {
        requests = new LinkedBlockingQueue<>();
        attempts = new AtomicInteger();
        logs = new CopyOnWriteArrayList<>();
        logEvents = new LinkedBlockingQueue<>();
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); logEvents.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        reply = (exchange, attempt) -> respond(exchange, 204, "");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            try {
                Captured captured = new Captured(exchange.getRequestBody().readAllBytes(),
                        exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("User-Agent"),
                        exchange.getRequestURI().getPath(), System.nanoTime());
                requests.add(captured);
                reply.handle(exchange, attempts.incrementAndGet());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                if (!(failure instanceof IOException)) throw new RuntimeException(failure);
            } finally { exchange.close(); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/123/secret-token";
        config = new AtomicReference<>(settings(webhook(url, false, "", false), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 2));
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach void teardown() {
        if (service != null) service.close();
        http.shutdownNow();
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private void start() {
        service = new DiscordWebhookService(config::get, http, logger, uri -> uri.getHost().equals("127.0.0.1"));
        service.start();
    }
    private void start(DiscordWebhookService.Sleeper sleeper) {
        service = new DiscordWebhookService(config::get, http, logger, uri -> uri.getHost().equals("127.0.0.1"), sleeper);
        service.start();
    }

    @Test void multipartContainsPayloadAndByteExactWav() throws Exception {
        config.set(settings(webhook(url, true, "12345", true), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        start();
        assertTrue(service.submitIncident(TestFixtures.incident()));
        Captured request = take();
        assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
        assertEquals("VoiceWarden (https://github.com/danielmillar, 1.0)", request.userAgent());
        assertTrue(request.text().contains("name=\"payload_json\"\r\nContent-Type: application/json"));
        assertTrue(request.text().contains("name=\"files[0]\"; filename=\"voice-abcdef01.wav\""));
        JsonObject json = request.json();
        assertEquals("voice-abcdef01.wav", json.getAsJsonArray("attachments").get(0).getAsJsonObject().get("filename").getAsString());
        assertEquals("attachment://voice-abcdef01.wav", new View(json).file());
        assertLimits(json);
        String binary = new String(request.bytes(), StandardCharsets.ISO_8859_1);
        int start = binary.indexOf("Content-Type: audio/wav\r\n\r\n") + "Content-Type: audio/wav\r\n\r\n".length();
        byte[] expected = WavEncoder.encode(TestFixtures.AUDIO, 16000);
        assertArrayEquals(expected, Arrays.copyOfRange(request.bytes(), start, start + expected.length));
        String boundary = request.contentType().substring(request.contentType().indexOf("boundary=") + 9);
        assertTrue(binary.endsWith("\r\n--" + boundary + "--\r\n"));
        deliveries(1, 0);
    }

    @Test void jsonOnlyEscapesPlayerTextAndHighlightsActualOffsets() throws Exception {
        start();
        assertTrue(service.submitIncident(TestFixtures.incident("Alex_*", "**badword** > text `code` [link](x) @everyone", ModerationAction.NONE, TestFixtures.AUDIO)));
        Captured request = take();
        assertEquals("application/json", request.contentType());
        assertFalse(request.json().has("attachments"));
        View view = new View(request);
        assertLimits(view.json());
        assertNull(view.file());
        assertEquals("Voice flag: Alex\\_\\*", view.title());
        assertEquals("> \\*\\***badword**\\*\\* \\> text \\`code\\` \\[link\\]\\(x\\) @everyone", view.part("Transcript"));
        assertEquals("Alex\\_\\* · `" + TestFixtures.PLAYER + "`", view.row("Player"));
        assertTrue(view.summary().contains("dry run"));
        assertTrue(view.summary().endsWith("<t:" + TestFixtures.TIME.getEpochSecond() + ":f>"));
        assertTrue(view.row("Flags").startsWith("2 of 3"));
        assertEquals("Profanity: badword", view.row("Matched"));
        assertEquals("-# \\-12s prior \\*line\\* @here", view.part("before"));
        assertEquals("Test server · world -1, 64, 12", view.row("Server"));
        assertEquals("`" + TestFixtures.ID + "`", view.row("Incident"));
        assertEquals(0xF1C40F, view.color());
    }

    @ParameterizedTest @ValueSource(strings = {"", "123456789", "123@everyone", "１２３"})
    void allowedMentionsOnlyPermitConfiguredNumericRole(String role) throws Exception {
        config.set(settings(webhook(url, false, role, true), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitIncident(TestFixtures.incident());
        JsonObject json = take().json();
        assertTrue(json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
        if (role.equals("123456789")) {
            assertEquals("<@&123456789>", new View(json).ping());
            assertEquals(List.of("123456789"), json.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").asList().stream().map(JsonElement::getAsString).toList());
        } else {
            assertNull(new View(json).ping());
            assertFalse(json.getAsJsonObject("allowed_mentions").has("roles"));
        }
        assertLimits(json);
        assertTrue(new View(json).part("Transcript").contains("||**badword**||"));
    }

    @Test void invalidRoleWarnsOnceAndNeverOnCaller() throws Exception {
        config.set(settings(webhook(url, false, "@here", false), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        Set<Long> logThreads = ConcurrentHashMap.newKeySet();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logThreads.add(Thread.currentThread().threadId()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        start();
        service.submitIncident(TestFixtures.incident());
        service.submitIncident(TestFixtures.incident());
        take(); take();
        deliveries(2, 0);
        assertEquals(1, logs.stream().filter(record -> record.getMessage().contains("role id")).count());
        assertFalse(logThreads.contains(Thread.currentThread().threadId()));
    }

    @Test void emptyAudioUsesJsonEvenWhenEnabled() throws Exception {
        config.set(settings(webhook(url, true, "", false), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitIncident(TestFixtures.incident("Alex", "badword", ModerationAction.WARN, new short[0]));
        Captured request = take();
        assertEquals("application/json", request.contentType());
        assertEquals(0xE67E22, new View(request).color());
        assertEquals("Voice warning: Alex", new View(request).title());
    }

    @Test void hugeTranscriptAndTemplatesObeyMessageLimitsWithBalancedMarkdown() throws Exception {
        String transcript = "badword " + "*_[\\😀]()".repeat(20_000);
        WebhookSettings webhook = new WebhookSettings(true, url, "", "", -1, "", false, true, true, true, true,
                "Title {player} " + "*".repeat(400), "Footer {server} " + "_".repeat(4000));
        config.set(settings(webhook, WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitIncident(TestFixtures.incident("Long".repeat(300), transcript, ModerationAction.NONE, TestFixtures.AUDIO));
        JsonObject json = take().json();
        assertLimits(json);
        View view = new View(json);
        assertTrue(view.part("Transcript").endsWith("…"));
        assertNotNull(view.part("Details"));
        view.texts().forEach(DiscordWebhookServiceTest::assertBalanced);
    }

    @Test void truncationInsideSpoilerClosesBothMarkers() {
        String transcript = "x*".repeat(2000);
        var match = new dev.danielmillar.voicewarden.moderation.rules.RuleMatch("x", "x",
                dev.danielmillar.voicewarden.moderation.rules.RuleAction.FLAG, 1, transcript, 0, transcript.length());
        String text = DiscordText.highlighted(transcript, List.of(match), true).render(100);
        assertTrue(text.startsWith("||**"));
        assertTrue(text.endsWith("**||…"));
        assertTrue(text.length() <= 100);
        assertBalanced(text);
    }

    @Test void autoMuteSameUrlSendsOnceWithMuteFormatting() throws Exception {
        WebhookSettings webhook = webhook(url, false, "", false);
        config.set(settings(webhook, webhook, WebhookSettings.disabled(), 10, 0));
        start(); service.start();
        service.submitIncident(TestFixtures.incident("Alex", "badword", ModerationAction.mute(Duration.ofMinutes(90)), TestFixtures.AUDIO));
        View view = new View(take());
        deliveries(1, 0);
        assertTrue(requests.isEmpty());
        assertEquals("Voice auto-mute: Alex", view.title());
        assertEquals(0xE74C3C, view.color());
        assertTrue(view.summary().contains("1h 30m"));
    }

    @Test void autoMuteDistinctUrlsSendsToBothAndStaffOnlyToMutes() throws Exception {
        config.set(settings(webhook(url, false, "", false), webhook(url + "-mutes", false, "", false), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitIncident(TestFixtures.incident("Alex", "badword", ModerationAction.mute(Duration.ofMinutes(15)), TestFixtures.AUDIO));
        assertEquals("/api/webhooks/123/secret-token", take().path());
        assertEquals("/api/webhooks/123/secret-token-mutes", take().path());
        service.submitStaffAction(new StaffAction(StaffAction.Type.MUTE, TestFixtures.PLAYER, "Alex_*", "Staff`",
                Duration.ofDays(2), "reason _word_ @everyone", TestFixtures.TIME));
        Captured staff = take();
        assertTrue(staff.path().endsWith("-mutes"));
        View view = new View(staff);
        assertEquals("Staff\\`", view.row("By"));
        assertTrue(view.summary().contains("2d"));
        assertEquals("reason \\_word\\_ @everyone", view.row("Reason"));
    }

    @Test void staffUnmuteHasGreenColorAndNoAbsentReason() throws Exception {
        config.set(settings(WebhookSettings.disabled(), webhook(url, false, "", false), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitStaffAction(new StaffAction(StaffAction.Type.UNMUTE, TestFixtures.PLAYER, "Alex", "System", null, null, TestFixtures.TIME));
        View view = new View(take());
        assertEquals("Voice unmute: Alex", view.title());
        assertEquals(0x2ECC71, view.color());
        assertNull(view.row("Reason"));
    }


    @Test void reportUsesUtcDropsOldestLinesAndAttachesAudio() throws Exception {
        config.set(settings(WebhookSettings.disabled(), WebhookSettings.disabled(), webhook(url, true, "", false), 10, 0));
        start();
        List<TranscriptLine> lines = new ArrayList<>();
        for (int index = 0; index < 30; index++) lines.add(new TranscriptLine(TestFixtures.TIME.plusSeconds(index), "line" + index + " " + "x".repeat(250)));
        VoiceReport report = new VoiceReport(TestFixtures.ID, TestFixtures.TIME, null, "Console", TestFixtures.PLAYER,
                "Alex", Duration.ofMinutes(1), lines, TestFixtures.AUDIO, 16000);
        service.submitReport(report);
        Captured request = take();
        assertTrue(request.contentType().startsWith("multipart/"));
        View view = new View(request);
        String transcript = view.part("Transcript");
        assertFalse(transcript.contains("line0 "));
        assertTrue(transcript.contains("\\[12:35:25\\] line29 "));
        assertTrue(view.row("Speech").startsWith("30 lines"));
        assertEquals("Console", view.row("Reporter"));
        assertEquals(0x3498DB, view.color());
        assertEquals("attachment://voice-abcdef01.wav", view.file());
        assertLimits(request.json());
    }

    @Test void templatesReplaceAllPlaceholdersAndColorOverridesDefaults() throws Exception {
        WebhookSettings webhook = new WebhookSettings(true, url, "VoiceBot", "https://example.test/avatar.png", 0x123456, "", false,
                false, false, false, false, "{player}/{action}/{duration}/{id}/{server}", "Custom {server} {id}");
        config.set(settings(webhook, WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitIncident(TestFixtures.incident("Alex", "badword", ModerationAction.mute(Duration.ofMinutes(15)), TestFixtures.AUDIO));
        JsonObject json = take().json();
        View view = new View(json);
        assertEquals("VoiceBot", json.get("username").getAsString());
        assertEquals("https://example.test/avatar.png", json.get("avatar_url").getAsString());
        assertEquals(0x123456, view.color());
        assertTrue(view.title().startsWith("Alex/"));
        assertTrue(view.title().endsWith("/15m/abcdef01/Test server"));
        assertEquals("Custom Test server abcdef01", view.footer());
        assertNull(view.part("Transcript"));
        assertNull(view.part("before"));
        assertNull(view.row("Server"));
    }

    @Test void emptyServerFooterOmitsBullet() throws Exception {
        DiscordSettings current = config.get();
        config.set(new DiscordSettings("", 2, 0, 10, current.flags(), current.mutes(), current.reports()));
        start(); service.submitIncident(TestFixtures.incident());
        assertEquals("VoiceWarden", new View(take()).footer());
    }

    @Test void retries429AfterFractionalBodyDelayEvenWithZeroOrdinaryRetries() throws Exception {
        config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        reply = (exchange, attempt) -> respond(exchange, attempt == 1 ? 429 : 204, attempt == 1 ? "{\"retry_after\":0.09}" : "");
        start(); service.submitIncident(TestFixtures.incident());
        Captured first = take(), second = take();
        assertTrue(second.receivedAt() - first.receivedAt() >= TimeUnit.MILLISECONDS.toNanos(75));
        deliveries(1, 0);
        assertEquals(0, service.failedCount());
    }

    @Test void retries429UsingHeaderWhenBodyIsInvalid() throws Exception {
        List<Duration> waits = new CopyOnWriteArrayList<>();
        reply = (exchange, attempt) -> {
            if (attempt == 1) exchange.getResponseHeaders().add("Retry-After", "0.25");
            respond(exchange, attempt == 1 ? 429 : 204, attempt == 1 ? "invalid json" : "");
        };
        start(waits::add); service.submitIncident(TestFixtures.incident());
        take(); take(); deliveries(1, 0);
        assertEquals(1, waits.size());
        assertTrue(waits.getFirst().toMillis() >= 200);
    }

    @Test void persistent429StopsAtTotalAttemptCap() throws Exception {
        config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        reply = (exchange, attempt) -> respond(exchange, 429, "{\"retry_after\":0}");
        start(duration -> {}); service.submitIncident(TestFixtures.incident());
        deliveries(0, 1);
        assertEquals(5, attempts.get());
        assertEquals(0, service.sentCount());
    }

    @Test void exhaustedUrlBucketDelaysNextRequest() throws Exception {
        reply = (exchange, attempt) -> {
            if (attempt == 1) {
                exchange.getResponseHeaders().add("X-RateLimit-Remaining", "0");
                exchange.getResponseHeaders().add("X-RateLimit-Reset-After", "0.09");
            }
            respond(exchange, 204, "");
        };
        start(); service.submitIncident(TestFixtures.incident()); service.submitIncident(TestFixtures.incident());
        Captured first = take(), second = take();
        assertTrue(second.receivedAt() - first.receivedAt() >= TimeUnit.MILLISECONDS.toNanos(75));
        deliveries(2, 0);
    }

    @Test void serverErrorsUseExponentialBackoffThenSucceed() throws Exception {
        List<Duration> waits = new CopyOnWriteArrayList<>();
        reply = (exchange, attempt) -> respond(exchange, attempt <= 2 ? 503 : 204, attempt <= 2 ? "busy" : "");
        start(waits::add); service.submitIncident(TestFixtures.incident());
        take(); take(); take(); deliveries(1, 0);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), waits);
        assertEquals(0, service.failedCount());
    }

    @Test void connectionErrorsRetryWithoutRunningIoOnCaller() throws Exception {
        server.stop(0);
        List<Duration> waits = new CopyOnWriteArrayList<>();
        start(waits::add);
        assertTrue(service.submitIncident(TestFixtures.incident()));
        deliveries(0, 1);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), waits);
        assertEquals(0, service.sentCount());
    }

    @Test void timedOutRequestRetriesThenSucceeds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Duration> waits = new CopyOnWriteArrayList<>();
        reply = (exchange, attempt) -> {
            if (attempt == 1) release.await();
            respond(exchange, 204, "");
        };
        config.set(new DiscordSettings("Test server", 1, 1, 10, config.get().flags(), config.get().mutes(), config.get().reports()));
        start(waits::add); service.submitIncident(TestFixtures.incident());
        Captured first = take(), second = take();
        assertTrue(second.receivedAt() - first.receivedAt() >= TimeUnit.MILLISECONDS.toNanos(900));
        deliveries(1, 0);
        assertEquals(List.of(Duration.ofSeconds(1)), waits);
        release.countDown();
    }

    @Test void zeroQueueCapacityRejectsNewWorkAndCountsDrop() {
        config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 0, 0));
        start();
        assertFalse(service.submitIncident(TestFixtures.incident()));
        assertEquals(1, service.droppedCount());
        assertEquals(0, service.queueSize());
    }

    @Test void terminal404IsNotRetriedAndTokenNeverAppearsInLogs() throws Exception {
        reply = (exchange, attempt) -> respond(exchange, 404, "{\"message\":\"Unknown Webhook " + url + "\"}");
        start(duration -> fail("Should not retry 404")); service.submitIncident(TestFixtures.incident());
        take(); deliveries(0, 1); logMatching(record -> true);
        assertEquals(1, attempts.get());
        assertTrue(logs.stream().anyMatch(record -> record.getMessage().contains("404 Unknown Webhook")));
        assertTrue(logs.stream().noneMatch(record -> record.getMessage().contains("secret-token")));
    }

    @Test void queueDropsNewItemAndHotReloadAppliesWhenDequeued() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        reply = (exchange, attempt) -> {
            if (attempt == 1) { entered.countDown(); release.await(); }
            respond(exchange, 204, "");
        };
        config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 1, 0));
        start(); assertTrue(service.submitIncident(TestFixtures.incident()));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertTrue(service.submitIncident(TestFixtures.incident("Second", "badword", ModerationAction.NONE, TestFixtures.AUDIO)));
        assertFalse(service.submitIncident(TestFixtures.incident("Third", "badword", ModerationAction.NONE, TestFixtures.AUDIO)));
        assertEquals(1, service.queueSize());
        assertEquals(1, service.droppedCount());
        config.set(settings(webhook(url + "-new", false, "", false), WebhookSettings.disabled(), WebhookSettings.disabled(), 100, 0));
        release.countDown();
        take(); Captured second = take();
        assertTrue(second.path().endsWith("-new"));
        assertEquals("Voice flag: Second", new View(second).title());
        deliveries(2, 0);
        assertEquals(0, service.queueSize());
    }

    @Test void capacityReloadShrinksAndGrowsWithoutDiscardingQueuedMessages() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        reply = (exchange, attempt) -> {
            if (attempt == 1) { entered.countDown(); release.await(); }
            respond(exchange, 204, "");
        };
        config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 3, 0));
        start();
        assertTrue(service.submitIncident(TestFixtures.incident()));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        try {
            assertTrue(service.submitIncident(TestFixtures.incident("Second", "badword", ModerationAction.NONE, TestFixtures.AUDIO)));
            assertTrue(service.submitIncident(TestFixtures.incident("Third", "badword", ModerationAction.NONE, TestFixtures.AUDIO)));
            config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 1, 0));
            assertFalse(service.submitIncident(TestFixtures.incident()));
            assertEquals(2, service.queueSize());
            config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 4, 0));
            assertTrue(service.submitIncident(TestFixtures.incident("Fourth", "badword", ModerationAction.NONE, TestFixtures.AUDIO)));
            var completed = service.sendTest(WebhookTestTarget.MUTE);
            release.countDown();
            take();
            assertEquals("Voice flag: Second", new View(take()).title());
            assertEquals("Voice flag: Third", new View(take()).title());
            assertEquals("Voice flag: Fourth", new View(take()).title());
            assertEquals(List.of(off("mutes")), completed.get(5, TimeUnit.SECONDS));
            assertEquals(4, service.sentCount());
            assertEquals(1, service.droppedCount());
        } finally {
            release.countDown();
        }
    }

    @Test void overflowWarningsAreRateLimited() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        reply = (exchange, attempt) -> {
            if (attempt == 1) { entered.countDown(); release.await(); }
            respond(exchange, 204, "");
        };
        config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 1, 0));
        start(); service.submitIncident(TestFixtures.incident());
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var completion = service.sendTest(WebhookTestTarget.FLAG);
        for (int index = 0; index < 20; index++) assertFalse(service.submitIncident(TestFixtures.incident()));
        release.countDown();
        assertEquals(List.of(sent("flags")), completion.get(5, TimeUnit.SECONDS));
        assertEquals(2, service.sentCount());
        logMatching(record -> record.getMessage().contains("queue full"));
        assertEquals(20, service.droppedCount());
        assertEquals(1, logs.stream().filter(record -> record.getMessage().contains("queue full")).count());
    }

    @Test void testSendsSamplesAndReportsDisabledAndFailedRoutes() throws Exception {
        config.set(settings(webhook(url, true, "", false), WebhookSettings.disabled(), webhook(url + "-reports", false, "", false), 10, 0));
        reply = (exchange, attempt) -> respond(exchange, exchange.getRequestURI().getPath().endsWith("-reports") ? 404 : 204,
                exchange.getRequestURI().getPath().endsWith("-reports") ? "{\"message\":\"Unknown Webhook\"}" : "");
        start();
        var results = service.sendTest(WebhookTestTarget.ALL).get(3, TimeUnit.SECONDS);
        assertEquals(List.of(sent("flags"), off("mutes"), new WebhookTestResult("reports", WebhookTestResult.Outcome.FAILED, 404, "Unknown Webhook")), results);
        Captured flag = take(); take();
        assertTrue(flag.contentType().startsWith("multipart/"));
        assertTrue(new View(flag).part("Transcript").contains("**badword**"));
        assertEquals(List.of(off("mutes")), service.sendTest(WebhookTestTarget.MUTE).get(3, TimeUnit.SECONDS));
    }

    @ParameterizedTest @EnumSource(value = WebhookTestTarget.class, names = {"FLAG", "MUTE", "REPORT"})
    void testTargetSendsOnlySelectedWebhook(WebhookTestTarget target) throws Exception {
        config.set(settings(webhook(url, false, "", false), webhook(url + "-mutes", false, "", false), webhook(url + "-reports", false, "", false), 10, 0));
        start();
        var results = service.sendTest(target).get(3, TimeUnit.SECONDS);
        String route = switch (target) { case FLAG -> "flags"; case MUTE -> "mutes"; case REPORT -> "reports"; default -> throw new AssertionError(); };
        assertEquals(List.of(sent(route)), results);
        take(); assertEquals(1, attempts.get());
    }

    @Test void closeReturnsImmediatelyWithStalledHttpAndDiscardsPendingTests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        reply = (exchange, attempt) -> { entered.countDown(); release.await(); respond(exchange, 204, ""); };
        config.set(new DiscordSettings("Test server", 60, 3, 10, config.get().flags(), config.get().mutes(), config.get().reports()));
        start(); service.submitIncident(TestFixtures.incident());
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var pending = service.sendTest(WebhookTestTarget.ALL);
        long before = System.nanoTime(); service.close();
        assertTrue(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(200));
        assertNotRun(pending.get(3, TimeUnit.SECONDS));
        logMatching(record -> record.getMessage().contains("discarded 1 pending"));
        assertFalse(service.submitIncident(TestFixtures.incident()));
        service.start();
        assertFalse(service.submitIncident(TestFixtures.incident()));
        release.countDown();
    }

    @Test void closeInterruptsRateLimitWait() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        reply = (exchange, attempt) -> respond(exchange, 429, "{\"retry_after\":300}");
        start(duration -> { sleeping.countDown(); Thread.sleep(duration.toMillis()); });
        var future = service.sendTest(WebhookTestTarget.FLAG);
        assertTrue(sleeping.await(3, TimeUnit.SECONDS));
        service.close();
        assertNotRun(future.get(3, TimeUnit.SECONDS));
    }

    @Test void disabledAndInvalidUrlsSkipWithoutExposingToken() throws Exception {
        service = new DiscordWebhookService(config::get, http, logger); // production validator rejects local HTTP
        service.start();
        assertFalse(service.submitIncident(TestFixtures.incident()));
        assertFalse(service.submitIncident(TestFixtures.incident()));
        logMatching(record -> true);
        assertFalse(logs.getFirst().getMessage().contains("secret-token"));
        assertTrue(logs.getFirst().getMessage().contains("/api/webhooks/123/***"));
        config.set(DiscordSettings.disabled());
        assertFalse(service.submitIncident(TestFixtures.incident()));
        assertFalse(service.submitStaffAction(new StaffAction(StaffAction.Type.UNMUTE, TestFixtures.PLAYER, "Alex", "System", null, null, TestFixtures.TIME)));
        assertFalse(service.submitReport(TestFixtures.report()));
        assertEquals(0, service.droppedCount());
    }

    @ParameterizedTest @CsvSource({
            "https://discord.com/api/webhooks/123/token, true", "https://discordapp.com/api/webhooks/123/token, true",
            "https://canary.discord.com/api/webhooks/123/token, true", "https://ptb.discord.com/api/webhooks/123/token, true",
            "http://discord.com/api/webhooks/123/token, false", "https://discord.com.evil.test/api/webhooks/123/token, false",
            "https://discord.com/wrong/path, false", "https://evil.test/api/webhooks/123/token, false",
            "https://user:secret@discord.com/api/webhooks/123/token, false"
    })
    void productionUrlValidation(String url, boolean expected) { assertEquals(expected, DiscordWebhookService.validDiscordUrl(URI.create(url))); }

    private Captured take() throws InterruptedException {
        Captured captured = requests.poll(5, TimeUnit.SECONDS);
        assertNotNull(captured, "No HTTP request received");
        return captured;
    }
    /** The Components V2 message: a container holding text displays, separators and an optional file. */
    private record View(JsonObject json) {
        View(Captured request) { this(request.json()); }
        JsonObject container() {
            for (JsonElement element : json.getAsJsonArray("components"))
                if (element.getAsJsonObject().get("type").getAsInt() == 17) return element.getAsJsonObject();
            throw new AssertionError("No container");
        }
        int color() { return container().get("accent_color").getAsInt(); }
        List<String> texts() {
            List<String> texts = new ArrayList<>();
            for (JsonElement element : container().getAsJsonArray("components"))
                if (element.getAsJsonObject().get("type").getAsInt() == 10) texts.add(element.getAsJsonObject().get("content").getAsString());
            return texts;
        }
        String title() { return texts().getFirst().lines().findFirst().orElseThrow().substring(3); }
        String summary() { return texts().getFirst().lines().skip(1).findFirst().orElseThrow(); }
        String footer() { return texts().getLast().substring(3); }
        /** The body under a {@code ###} heading containing {@code heading}, or null. */
        String part(String heading) {
            for (String text : texts())
                if (text.startsWith("### ") && text.lines().findFirst().orElseThrow().contains(heading)) return text.substring(text.indexOf('\n') + 1);
            return null;
        }
        /** A {@code **label** value} row of the details part, or null. */
        String row(String label) {
            String details = part("Details");
            if (details == null) return null;
            return details.lines().filter(line -> line.startsWith("**" + label + "** ")).findFirst()
                    .map(line -> line.substring(label.length() + 5)).orElse(null);
        }
        String ping() {
            JsonObject first = json.getAsJsonArray("components").get(0).getAsJsonObject();
            return first.get("type").getAsInt() == 10 ? first.get("content").getAsString() : null;
        }
        String file() {
            for (JsonElement element : container().getAsJsonArray("components"))
                if (element.getAsJsonObject().get("type").getAsInt() == 13)
                    return element.getAsJsonObject().getAsJsonObject("file").get("url").getAsString();
            return null;
        }
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) exchange.getResponseBody().write(bytes);
    }
    private void deliveries(long sent, long failed) throws Exception {
        DiscordSettings current = config.get();
        WebhookTestTarget disabled = !current.mutes().enabled() ? WebhookTestTarget.MUTE
                : !current.reports().enabled() ? WebhookTestTarget.REPORT : WebhookTestTarget.FLAG;
        var barrier = service.sendTest(disabled).get(5, TimeUnit.SECONDS);
        assertEquals(WebhookTestResult.Outcome.OFF, barrier.getFirst().outcome(), barrier.toString());
        assertEquals(sent, service.sentCount());
        assertEquals(failed, service.failedCount());
    }

    private static WebhookTestResult sent(String webhook) { return new WebhookTestResult(webhook, WebhookTestResult.Outcome.SENT, 204, ""); }
    private static WebhookTestResult off(String webhook) { return new WebhookTestResult(webhook, WebhookTestResult.Outcome.OFF, 0, ""); }
    private static void assertNotRun(List<WebhookTestResult> results) {
        assertEquals(1, results.size());
        assertEquals("", results.getFirst().webhook());
        assertEquals(WebhookTestResult.Outcome.FAILED, results.getFirst().outcome());
    }
    private void logMatching(java.util.function.Predicate<LogRecord> predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (logs.stream().noneMatch(predicate)) {
            long left = deadline - System.nanoTime();
            assertTrue(left > 0 && logEvents.poll(left, TimeUnit.NANOSECONDS) != null, "Expected log was not published");
        }
    }
    private static WebhookSettings webhook(String url, boolean audio, String role, boolean spoiler) {
        return new WebhookSettings(true, url, "", "", -1, role, audio, true, true, true, spoiler, "", "");
    }
    private static DiscordSettings settings(WebhookSettings flags, WebhookSettings mutes, WebhookSettings reports, int queue, int retries) {
        return new DiscordSettings("Test server", 2, retries, queue, flags, mutes, reports);
    }
    /** Discord refuses a Components V2 message over 4,000 characters of text or 40 components. */
    private static void assertLimits(JsonObject json) {
        assertFalse(json.has("content"));
        assertFalse(json.has("embeds"));
        assertEquals(1 << 15, json.get("flags").getAsInt() & (1 << 15));
        int[] length = {0}, count = {0};
        countComponents(json.getAsJsonArray("components"), length, count);
        assertTrue(length[0] <= 4000, "Text total was " + length[0]);
        assertTrue(count[0] <= 40, "Components: " + count[0]);
    }
    private static void countComponents(JsonArray components, int[] length, int[] count) {
        for (JsonElement element : components) {
            JsonObject component = element.getAsJsonObject();
            count[0]++;
            if (component.has("content")) length[0] += component.get("content").getAsString().length();
            if (component.has("components")) countComponents(component.getAsJsonArray("components"), length, count);
        }
    }
    private static void assertBalanced(String text) {
        int bold = 0, spoilers = 0, code = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '\\') { assertTrue(index + 1 < text.length(), "Dangling escape"); index++; }
            else if (ch == '*' && index + 1 < text.length() && text.charAt(index + 1) == '*') { bold++; index++; }
            else if (ch == '|' && index + 1 < text.length() && text.charAt(index + 1) == '|') { spoilers++; index++; }
            else if (ch == '`') code++;
        }
        assertEquals(0, bold % 2, "Dangling bold");
        assertEquals(0, spoilers % 2, "Dangling spoiler");
        assertEquals(0, code % 2, "Dangling code");
    }
}
