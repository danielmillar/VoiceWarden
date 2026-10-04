package dev.danielmillar.nevusvoice.notify.discord;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import dev.danielmillar.nevusvoice.TestFixtures;
import dev.danielmillar.nevusvoice.audio.WavEncoder;
import dev.danielmillar.nevusvoice.moderation.*;
import dev.danielmillar.nevusvoice.report.VoiceReport;
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
import java.util.function.BooleanSupplier;
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
    private volatile Reply reply;
    private AtomicInteger attempts;
    private String url;
    private Logger logger;

    @BeforeEach void setup() throws Exception {
        requests = new LinkedBlockingQueue<>();
        attempts = new AtomicInteger();
        logs = new CopyOnWriteArrayList<>();
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); }
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
        assertEquals("NevusVoice (https://github.com/danielmillar, 1.0)", request.userAgent());
        assertTrue(request.text().contains("name=\"payload_json\"\r\nContent-Type: application/json"));
        assertTrue(request.text().contains("name=\"files[0]\"; filename=\"voice-abcdef01.wav\""));
        JsonObject json = request.json();
        assertEquals("voice-abcdef01.wav", json.getAsJsonArray("attachments").get(0).getAsJsonObject().get("filename").getAsString());
        String binary = new String(request.bytes(), StandardCharsets.ISO_8859_1);
        int start = binary.indexOf("Content-Type: audio/wav\r\n\r\n") + "Content-Type: audio/wav\r\n\r\n".length();
        byte[] expected = WavEncoder.encode(TestFixtures.AUDIO, 16000);
        assertArrayEquals(expected, Arrays.copyOfRange(request.bytes(), start, start + expected.length));
        String boundary = request.contentType().substring(request.contentType().indexOf("boundary=") + 9);
        assertTrue(binary.endsWith("\r\n--" + boundary + "--\r\n"));
        await(() -> service.sentCount() == 1);
    }

    @Test void jsonOnlyEscapesPlayerTextAndHighlightsActualOffsets() throws Exception {
        start();
        assertTrue(service.submitIncident(TestFixtures.incident("Alex_*", "**badword** > text `code` [link](x) @everyone", ModerationAction.NONE, TestFixtures.AUDIO)));
        Captured request = take();
        assertEquals("application/json", request.contentType());
        assertFalse(request.json().has("attachments"));
        JsonObject embed = embed(request);
        assertEquals("Voice flag: Alex\\_\\*", embed.get("title").getAsString());
        assertEquals("\\*\\***badword**\\*\\* \\> text \\`code\\` \\[link\\]\\(x\\) @everyone", embed.get("description").getAsString());
        assertTrue(field(embed, "Player").startsWith("Alex\\_\\*\n`"));
        assertTrue(field(embed, "Player").endsWith("`" + TestFixtures.PLAYER + "`"));
        assertEquals("Flagged — dry run, not applied", field(embed, "Action"));
        assertEquals("2 / 3 in window", field(embed, "Flags"));
        assertEquals("Profanity: badword", field(embed, "Matched"));
        assertEquals("\\-12s prior \\*line\\* @here", field(embed, "Context"));
        assertEquals("Test server\nworld -1, 64, 12", field(embed, "Server"));
        assertEquals(TestFixtures.TIME.toString(), embed.get("timestamp").getAsString());
        assertEquals(0xF1C40F, embed.get("color").getAsInt());
    }

    @ParameterizedTest @ValueSource(strings = {"", "123456789", "123@everyone", "１２３"})
    void allowedMentionsOnlyPermitConfiguredNumericRole(String role) throws Exception {
        config.set(settings(webhook(url, false, role, true), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitIncident(TestFixtures.incident());
        JsonObject json = take().json();
        assertTrue(json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
        if (role.equals("123456789")) {
            assertEquals("<@&123456789>", json.get("content").getAsString());
            assertEquals(List.of("123456789"), json.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").asList().stream().map(JsonElement::getAsString).toList());
        } else {
            assertFalse(json.has("content"));
            assertFalse(json.getAsJsonObject("allowed_mentions").has("roles"));
        }
        assertTrue(embed(json).get("description").getAsString().contains("||**badword**||"));
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
        await(() -> service.sentCount() == 2);
        assertEquals(1, logs.stream().filter(record -> record.getMessage().contains("role id")).count());
        assertFalse(logThreads.contains(Thread.currentThread().threadId()));
    }

    @Test void emptyAudioUsesJsonEvenWhenEnabled() throws Exception {
        config.set(settings(webhook(url, true, "", false), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitIncident(TestFixtures.incident("Alex", "badword", ModerationAction.WARN, new short[0]));
        Captured request = take();
        assertEquals("application/json", request.contentType());
        assertEquals(0xE67E22, embed(request).get("color").getAsInt());
        assertEquals("Voice warning: Alex", embed(request).get("title").getAsString());
    }

    @Test void hugeTranscriptAndTemplatesObeyAllEmbedLimitsWithBalancedMarkdown() throws Exception {
        String transcript = "badword " + "*_[\\😀]()".repeat(20_000);
        WebhookSettings webhook = new WebhookSettings(true, url, "", "", -1, "", false, true, true, true, true,
                "Title {player} " + "*".repeat(400), "Footer {server} " + "_".repeat(4000));
        config.set(settings(webhook, WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitIncident(TestFixtures.incident("Long".repeat(300), transcript, ModerationAction.NONE, TestFixtures.AUDIO));
        JsonObject json = take().json();
        assertLimits(json);
        JsonObject embed = embed(json);
        assertTrue(embed.get("description").getAsString().endsWith("…"));
        assertBalanced(embed.get("description").getAsString());
        assertBalanced(embed.get("title").getAsString());
        assertBalanced(embed.getAsJsonObject("footer").get("text").getAsString());
        embed.getAsJsonArray("fields").forEach(field -> assertBalanced(field.getAsJsonObject().get("value").getAsString()));
    }

    @Test void truncationInsideSpoilerClosesBothMarkers() {
        String transcript = "x*".repeat(2000);
        var match = new dev.danielmillar.nevusvoice.moderation.rules.RuleMatch("x", "x",
                dev.danielmillar.nevusvoice.moderation.rules.RuleAction.FLAG, 1, transcript, 0, transcript.length());
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
        JsonObject embed = embed(take());
        await(() -> service.sentCount() == 1);
        assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
        assertEquals("Voice auto-mute: Alex", embed.get("title").getAsString());
        assertEquals(0xE74C3C, embed.get("color").getAsInt());
        assertEquals("Muted for 1h 30m \\(offense \\#2\\) — dry run, not applied", field(embed, "Action"));
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
        assertEquals("Staff\\`", field(embed(staff), "By"));
        assertEquals("2d", field(embed(staff), "Duration"));
        assertEquals("reason \\_word\\_ @everyone", field(embed(staff), "Reason"));
    }

    @Test void staffUnmuteHasGreenColorAndNoDurationOrAbsentReason() throws Exception {
        config.set(settings(WebhookSettings.disabled(), webhook(url, false, "", false), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitStaffAction(new StaffAction(StaffAction.Type.UNMUTE, TestFixtures.PLAYER, "Alex", "System", null, null, TestFixtures.TIME));
        JsonObject embed = embed(take());
        assertEquals("Voice unmute: Alex", embed.get("title").getAsString());
        assertEquals(0x2ECC71, embed.get("color").getAsInt());
        assertNull(field(embed, "Duration"));
        assertNull(field(embed, "Reason"));
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
        JsonObject embed = embed(request);
        String description = embed.get("description").getAsString();
        assertFalse(description.contains("line0 "));
        assertTrue(description.contains("\\[12:35:25\\] line29 "));
        assertEquals("30", field(embed, "Lines"));
        assertEquals("1m", field(embed, "Window"));
        assertEquals("Console", field(embed, "Reporter"));
        assertEquals(0x3498DB, embed.get("color").getAsInt());
        assertLimits(request.json());
    }

    @Test void templatesReplaceAllPlaceholdersAndColorOverridesDefaults() throws Exception {
        WebhookSettings webhook = new WebhookSettings(true, url, "VoiceBot", "https://example.test/avatar.png", 0x123456, "", false,
                false, false, false, false, "{player}/{action}/{duration}/{id}/{server}", "Custom {server} {id}");
        config.set(settings(webhook, WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        start();
        service.submitIncident(TestFixtures.incident("Alex", "badword", ModerationAction.mute(Duration.ofMinutes(15)), TestFixtures.AUDIO));
        JsonObject json = take().json(), embed = embed(json);
        assertEquals("VoiceBot", json.get("username").getAsString());
        assertEquals("https://example.test/avatar.png", json.get("avatar_url").getAsString());
        assertEquals(0x123456, embed.get("color").getAsInt());
        assertTrue(embed.get("title").getAsString().contains("Alex/Muted for 15m"));
        assertTrue(embed.get("title").getAsString().endsWith("/15m/abcdef01/Test server"));
        assertEquals("Custom Test server abcdef01", embed.getAsJsonObject("footer").get("text").getAsString());
        assertFalse(embed.has("description"));
        assertNull(field(embed, "Context"));
        assertNull(field(embed, "Server"));
    }

    @Test void emptyServerFooterOmitsBullet() throws Exception {
        DiscordSettings current = config.get();
        config.set(new DiscordSettings("", 2, 0, 10, current.flags(), current.mutes(), current.reports()));
        start(); service.submitIncident(TestFixtures.incident());
        assertEquals("NevusVoice", embed(take()).getAsJsonObject("footer").get("text").getAsString());
    }

    @Test void retries429AfterFractionalBodyDelayEvenWithZeroOrdinaryRetries() throws Exception {
        config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        reply = (exchange, attempt) -> respond(exchange, attempt == 1 ? 429 : 204, attempt == 1 ? "{\"retry_after\":0.09}" : "");
        start(); service.submitIncident(TestFixtures.incident());
        Captured first = take(), second = take();
        assertTrue(second.receivedAt() - first.receivedAt() >= TimeUnit.MILLISECONDS.toNanos(75));
        await(() -> service.sentCount() == 1);
        assertEquals(0, service.failedCount());
    }

    @Test void retries429UsingHeaderWhenBodyIsInvalid() throws Exception {
        List<Duration> waits = new CopyOnWriteArrayList<>();
        reply = (exchange, attempt) -> {
            if (attempt == 1) exchange.getResponseHeaders().add("Retry-After", "0.25");
            respond(exchange, attempt == 1 ? 429 : 204, attempt == 1 ? "invalid json" : "");
        };
        start(waits::add); service.submitIncident(TestFixtures.incident());
        take(); take(); await(() -> service.sentCount() == 1);
        assertEquals(1, waits.size());
        assertTrue(waits.getFirst().toMillis() >= 200);
    }

    @Test void persistent429StopsAtTotalAttemptCap() throws Exception {
        config.set(settings(config.get().flags(), WebhookSettings.disabled(), WebhookSettings.disabled(), 10, 0));
        reply = (exchange, attempt) -> respond(exchange, 429, "{\"retry_after\":0}");
        start(duration -> {}); service.submitIncident(TestFixtures.incident());
        await(() -> service.failedCount() == 1);
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
        await(() -> service.sentCount() == 2);
    }

    @Test void serverErrorsUseExponentialBackoffThenSucceed() throws Exception {
        List<Duration> waits = new CopyOnWriteArrayList<>();
        reply = (exchange, attempt) -> respond(exchange, attempt <= 2 ? 503 : 204, attempt <= 2 ? "busy" : "");
        start(waits::add); service.submitIncident(TestFixtures.incident());
        take(); take(); take(); await(() -> service.sentCount() == 1);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), waits);
        assertEquals(0, service.failedCount());
    }

    @Test void connectionErrorsRetryWithoutRunningIoOnCaller() throws Exception {
        server.stop(0);
        List<Duration> waits = new CopyOnWriteArrayList<>();
        start(waits::add);
        assertTrue(service.submitIncident(TestFixtures.incident()));
        await(() -> service.failedCount() == 1);
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
        await(() -> service.sentCount() == 1);
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
        take(); await(() -> service.failedCount() == 1); await(() -> !logs.isEmpty());
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
        assertEquals("Voice flag: Second", embed(second).get("title").getAsString());
        await(() -> service.sentCount() == 2);
        assertEquals(0, service.queueSize());
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
        assertTrue(service.submitIncident(TestFixtures.incident()));
        for (int index = 0; index < 20; index++) assertFalse(service.submitIncident(TestFixtures.incident()));
        release.countDown(); await(() -> service.sentCount() == 2);
        await(() -> logs.stream().anyMatch(record -> record.getMessage().contains("queue full")));
        assertEquals(20, service.droppedCount());
        assertEquals(1, logs.stream().filter(record -> record.getMessage().contains("queue full")).count());
    }

    @Test void testSendsSamplesAndReportsDisabledAndFailedRoutes() throws Exception {
        config.set(settings(webhook(url, true, "", false), WebhookSettings.disabled(), webhook(url + "-reports", false, "", false), 10, 0));
        reply = (exchange, attempt) -> respond(exchange, exchange.getRequestURI().getPath().endsWith("-reports") ? 404 : 204,
                exchange.getRequestURI().getPath().endsWith("-reports") ? "{\"message\":\"Unknown Webhook\"}" : "");
        start();
        List<String> lines = service.sendTest(WebhookTestTarget.ALL).get(3, TimeUnit.SECONDS);
        assertEquals(List.of("flags: OK (204)", "mutes: disabled", "reports: FAILED 404 Unknown Webhook"), lines);
        Captured flag = take(); take();
        assertTrue(flag.contentType().startsWith("multipart/"));
        assertTrue(embed(flag).get("description").getAsString().contains("**badword**"));
        assertEquals(List.of("mutes: disabled"), service.sendTest(WebhookTestTarget.MUTE).get(3, TimeUnit.SECONDS));
    }

    @ParameterizedTest @EnumSource(value = WebhookTestTarget.class, names = {"FLAG", "MUTE", "REPORT"})
    void testTargetSendsOnlySelectedWebhook(WebhookTestTarget target) throws Exception {
        config.set(settings(webhook(url, false, "", false), webhook(url + "-mutes", false, "", false), webhook(url + "-reports", false, "", false), 10, 0));
        start();
        var lines = service.sendTest(target).get(3, TimeUnit.SECONDS);
        assertEquals(1, lines.size());
        String route = switch (target) { case FLAG -> "flags"; case MUTE -> "mutes"; case REPORT -> "reports"; default -> throw new AssertionError(); };
        assertEquals(List.of(route + ": OK (204)"), lines);
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
        assertEquals(List.of("test: FAILED service closed"), pending.get(3, TimeUnit.SECONDS));
        await(() -> service.droppedCount() == 1);
        await(() -> logs.stream().anyMatch(record -> record.getMessage().contains("discarded 1 pending")));
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
        assertEquals(List.of("test: FAILED service closed"), future.get(3, TimeUnit.SECONDS));
    }

    @Test void disabledAndInvalidUrlsSkipWithoutExposingToken() throws Exception {
        service = new DiscordWebhookService(config::get, http, logger); // production validator rejects local HTTP
        service.start();
        assertFalse(service.submitIncident(TestFixtures.incident()));
        assertFalse(service.submitIncident(TestFixtures.incident()));
        await(() -> logs.size() == 1);
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
    private static JsonObject embed(Captured request) { return embed(request.json()); }
    private static JsonObject embed(JsonObject payload) { return payload.getAsJsonArray("embeds").get(0).getAsJsonObject(); }
    private static String field(JsonObject embed, String name) {
        for (JsonElement element : embed.getAsJsonArray("fields")) {
            JsonObject field = element.getAsJsonObject();
            if (field.get("name").getAsString().equals(name)) return field.get("value").getAsString();
        }
        return null;
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) exchange.getResponseBody().write(bytes);
    }
    private static void await(BooleanSupplier predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(predicate.getAsBoolean(), "Condition did not become true");
    }
    private static WebhookSettings webhook(String url, boolean audio, String role, boolean spoiler) {
        return new WebhookSettings(true, url, "", "", -1, role, audio, true, true, true, spoiler, "", "");
    }
    private static DiscordSettings settings(WebhookSettings flags, WebhookSettings mutes, WebhookSettings reports, int queue, int retries) {
        return new DiscordSettings("Test server", 2, retries, queue, flags, mutes, reports);
    }
    private static void assertLimits(JsonObject json) {
        if (json.has("content")) assertTrue(json.get("content").getAsString().length() <= 2000);
        JsonObject embed = embed(json);
        int length = embed.get("title").getAsString().length();
        assertTrue(length <= 256);
        String footer = embed.getAsJsonObject("footer").get("text").getAsString();
        assertTrue(footer.length() <= 2048);
        length += footer.length();
        if (embed.has("description")) {
            String description = embed.get("description").getAsString();
            assertTrue(description.length() <= 4096);
            length += description.length();
        }
        assertTrue(embed.getAsJsonArray("fields").size() <= 25);
        for (JsonElement element : embed.getAsJsonArray("fields")) {
            JsonObject field = element.getAsJsonObject();
            assertTrue(field.get("name").getAsString().length() <= 256);
            assertTrue(field.get("value").getAsString().length() <= 1024);
            length += field.get("name").getAsString().length() + field.get("value").getAsString().length();
        }
        assertTrue(length <= 6000, "Embed total was " + length);
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
