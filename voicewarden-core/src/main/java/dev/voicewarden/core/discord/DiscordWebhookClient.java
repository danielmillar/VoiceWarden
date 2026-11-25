package dev.voicewarden.core.discord;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.api.VoiceMuteRecord;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.storage.VoiceReportRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DiscordWebhookClient implements AutoCloseable {

    static final long MAX_RETRY_DELAY_MS = 30_000L;
    static final int MAX_CONCURRENT_REQUESTS = 4;
    static final String USER_AGENT = "VoiceWarden-Webhook/1.0";

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordWebhookClient.class);
    private static final Gson GSON = new Gson();
    private static final UUID TEST_PLAYER = new UUID(0L, 0L);
    private static final UUID TEST_NEARBY_PLAYER = new UUID(0L, 1L);
    private static final int MAX_ERROR_BODY = 200;
    private static final long RATE_LIMIT_JITTER_MS = 150L;

    private final DiscordWebhookConfig config;
    private final DiscordMessages messages;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;
    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT_REQUESTS);
    private final ConcurrentHashMap<String, AtomicLong> rateLimitUntilMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> urlLocks = new ConcurrentHashMap<>();

    public DiscordWebhookClient(DiscordWebhookConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = new DiscordMessages(config);
        this.http = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DiscordWebhookConfig config() {
        return config;
    }

    public CompletableFuture<WebhookResult> sendFlag(FlagWebhookPayload payload) {
        Objects.requireNonNull(payload, "payload");
        return dispatch(WebhookKind.FLAG, () -> messages.flag(payload, false));
    }

    public CompletableFuture<WebhookResult> sendMicSpam(MicSpamWebhookPayload payload) {
        Objects.requireNonNull(payload, "payload");
        return dispatch(WebhookKind.FLAG, () -> messages.micSpam(payload, false));
    }

    public CompletableFuture<WebhookResult> sendMute(MuteWebhookPayload payload) {
        Objects.requireNonNull(payload, "payload");
        return dispatch(WebhookKind.MUTE, () -> messages.mute(payload, false));
    }

    public CompletableFuture<WebhookResult> sendReport(ReportWebhookPayload payload) {
        Objects.requireNonNull(payload, "payload");
        return dispatch(WebhookKind.REPORT, () -> messages.report(payload, false));
    }

    public CompletableFuture<WebhookResult> sendTest(TestTarget target) {
        Objects.requireNonNull(target, "target");
        Instant now = Instant.now();
        List<CompletableFuture<WebhookResult>> sends = new ArrayList<>();
        for (WebhookKind kind : target.kinds()) {
            sends.add(dispatch(kind, () -> testMessage(kind, now)));
        }
        if (sends.size() == 1) {
            return sends.getFirst();
        }
        return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> WebhookResult.combined(
                        target.name().toLowerCase(Locale.ROOT),
                        sends.stream().map(CompletableFuture::join).toList()));
    }

    @Override
    public void close() {
        http.shutdownNow();
        executor.shutdownNow();
    }

    private CompletableFuture<WebhookResult> dispatch(WebhookKind kind, MessageSource source) {
        WebhookTarget target = config.target(kind);
        if (!target.enabled()) {
            return CompletableFuture.completedFuture(WebhookResult.skipped(kind, kind.label() + " webhook is disabled"));
        }
        if (target.url().isEmpty()) {
            return CompletableFuture.completedFuture(WebhookResult.skipped(kind, kind.label() + " webhook has no URL set"));
        }
        URI baseUri = parseBaseUri(target.url());
        if (baseUri == null) {
            return CompletableFuture.completedFuture(WebhookResult.failed(kind, 0, 0,
                    kind.label() + " webhook URL is not a valid http(s) URL"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> buildAndDeliver(kind, baseUri, source), executor);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(WebhookResult.failed(kind, 0, 0, "webhook client is closed"));
        }
    }

    private WebhookResult buildAndDeliver(WebhookKind kind, URI baseUri, MessageSource source) {
        WebhookMessage message;
        try {
            message = source.build();
        } catch (RuntimeException e) {
            LOGGER.warn("Cannot build Discord {} webhook message", kind.label(), e);
            return WebhookResult.failed(kind, 0, 0, "cannot build message: " + e.getMessage());
        }
        return deliver(kind, baseUri, message);
    }

    private WebhookResult deliver(WebhookKind kind, URI baseUri, WebhookMessage message) {
        String rateLimitKey = baseUri.toString();
        URI componentsUri = withComponentsUri(baseUri);
        ReentrantLock lock = urlLocks.computeIfAbsent(rateLimitKey, ignored -> new ReentrantLock());
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WebhookResult.failed(kind, 0, 0, "interrupted while waiting to send");
        }
        try {
            WebhookResult cardResult = post(kind, rateLimitKey, jsonRequest(componentsUri, message.payload()));
            if (!cardResult.success() || !message.hasAttachment()) {
                return cardResult;
            }
            WebhookResult clipResult = post(kind, rateLimitKey, multipartRequest(baseUri, message));
            if (!clipResult.success()) {
                return WebhookResult.failed(kind, clipResult.statusCode(),
                        Math.max(cardResult.attempts(), clipResult.attempts()),
                        "message sent, audio clip failed: " + clipResult.message());
            }
            return WebhookResult.sent(kind, clipResult.statusCode(),
                    Math.max(cardResult.attempts(), clipResult.attempts()));
        } finally {
            lock.unlock();
        }
    }

    private WebhookResult post(WebhookKind kind, String rateLimitKey, HttpRequest request) {
        int maxAttempts = config.maxRetries() + 1;
        int lastStatus = 0;
        String lastError = "";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long delayMs;
            try {
                waitForRateLimit(rateLimitKey);
                inFlight.acquire();
                HttpResponse<String> response;
                try {
                    response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } finally {
                    inFlight.release();
                }
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return WebhookResult.sent(kind, status, attempt);
                }
                lastStatus = status;
                lastError = "HTTP " + status + errorDetail(response.body());
                if (status == 429) {
                    delayMs = retryAfterMs(response);
                    long jitter = delayMs > 0 ? ThreadLocalRandom.current().nextLong(Math.min(RATE_LIMIT_JITTER_MS, delayMs) + 1) : 0L;
                    long totalDelay = Math.min(MAX_RETRY_DELAY_MS, delayMs + jitter);
                    recordRateLimit(rateLimitKey, totalDelay);
                    delayMs = totalDelay;
                } else if (status >= 500) {
                    delayMs = backoffMs(attempt);
                } else {
                    LOGGER.warn("Discord {} webhook rejected the message: {}", kind.label(), lastError);
                    return WebhookResult.failed(kind, status, attempt, lastError);
                }
            } catch (IOException e) {
                lastStatus = 0;
                lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                delayMs = backoffMs(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return WebhookResult.failed(kind, lastStatus, attempt, "interrupted while sending");
            }
            if (attempt == maxAttempts) {
                break;
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return WebhookResult.failed(kind, lastStatus, attempt, "interrupted while waiting to retry: " + lastError);
            }
        }
        LOGGER.warn("Discord {} webhook failed after {} attempts: {}", kind.label(), maxAttempts, lastError);
        return WebhookResult.failed(kind, lastStatus, maxAttempts, lastError);
    }

    private void waitForRateLimit(String key) throws InterruptedException {
        AtomicLong until = rateLimitUntilMs.get(key);
        if (until == null) {
            return;
        }
        while (true) {
            long remaining = until.get() - System.currentTimeMillis();
            if (remaining <= 0L) {
                return;
            }
            Thread.sleep(Math.min(remaining, MAX_RETRY_DELAY_MS));
        }
    }

    private void recordRateLimit(String key, long delayMs) {
        if (delayMs <= 0L) {
            return;
        }
        long deadline = System.currentTimeMillis() + delayMs;
        rateLimitUntilMs.computeIfAbsent(key, ignored -> new AtomicLong()).accumulateAndGet(deadline, Math::max);
    }

    private HttpRequest jsonRequest(URI uri, JsonObject payload) {
        String json = GSON.toJson(payload);
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest multipartRequest(URI uri, WebhookMessage message) {
        String json = GSON.toJson(message.attachmentPayload());
        String boundary = "VoiceWarden" + Long.toHexString(ThreadLocalRandom.current().nextLong())
                + Long.toHexString(System.nanoTime());
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, json, message)))
                .build();
    }

    static byte[] multipart(String boundary, String json, WebhookMessage message) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(message.attachment().length + json.length() + 512);
        writeAscii(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n");
        body.writeBytes(json.getBytes(StandardCharsets.UTF_8));
        writeAscii(body, "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + message.attachmentName() + "\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n");
        body.writeBytes(message.attachment());
        writeAscii(body, "\r\n--" + boundary + "--\r\n");
        return body.toByteArray();
    }

    private long backoffMs(int attempt) {
        long base = Math.max(1L, Math.min(MAX_RETRY_DELAY_MS, config.retryBaseDelayMs()));
        int shift = Math.min(Math.max(0, attempt - 1), 16);
        long scaled = base > (MAX_RETRY_DELAY_MS >> shift) ? MAX_RETRY_DELAY_MS : (base << shift);
        return Math.min(MAX_RETRY_DELAY_MS, scaled);
    }

    private long retryAfterMs(HttpResponse<String> response) {
        Double seconds = response.headers().firstValue("Retry-After").map(DiscordWebhookClient::parseSeconds).orElse(null);
        if (seconds == null) {
            seconds = bodyRetryAfter(response.body());
        }
        if (seconds == null || seconds < 0) {
            return config.retryBaseDelayMs();
        }
        return Math.min(MAX_RETRY_DELAY_MS, (long) Math.ceil(seconds * 1000.0));
    }

    private static Double parseSeconds(String value) {
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double bodyRetryAfter(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has("retry_after") && object.get("retry_after").isJsonPrimitive()) {
                    return object.get("retry_after").getAsDouble();
                }
            }
        } catch (JsonParseException | NumberFormatException | IllegalStateException e) {
            return null;
        }
        return null;
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").strip();
        return ": " + DiscordMessages.truncate(flat, MAX_ERROR_BODY);
    }

    private static URI parseBaseUri(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                return null;
            }
            if (uri.getHost() == null) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static URI withComponentsUri(URI uri) {
        String query = uri.getRawQuery();
        String nextQuery;
        if (query == null || query.isEmpty()) {
            nextQuery = "with_components=true";
        } else if (query.contains("with_components=")) {
            nextQuery = query;
        } else {
            nextQuery = query + "&with_components=true";
        }
        try {
            return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), nextQuery, uri.getRawFragment());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Failed to append with_components query parameter", e);
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private WebhookMessage testMessage(WebhookKind kind, Instant now) {
        short[] tone = testTone();
        List<ConversationLine> nearby = List.of(new ConversationLine(TEST_NEARBY_PLAYER, "NearbyPlayer",
                now.minusSeconds(8), "Nearby speech appears here when other players were talking.", null));
        return switch (kind) {
            case FLAG -> messages.flag(new FlagWebhookPayload(
                    TEST_PLAYER, "VoiceWardenTest", FlagSeverity.MODERATE, ConfirmationState.CONFIRMED,
                    List.of("example"), "This is a VoiceWarden webhook test. No player was flagged.",
                    VoiceAction.WARN, tone, now, nearby), true);
            case MUTE -> messages.mute(MuteWebhookPayload.muted(
                    new VoiceMuteRecord(TEST_PLAYER, "VoiceWardenTest", "Webhook test", "VoiceWarden", now,
                            now.plus(Duration.ofMinutes(10)), true, config.serverId()),
                    "This is a VoiceWarden webhook test. No player was muted.", tone, now, nearby), true);
            case REPORT -> {
                String transcript = "This is a VoiceWarden webhook test. No report was filed.";
                yield messages.report(new ReportWebhookPayload(VoiceReportRecord.open(
                        TEST_PLAYER, "VoiceWardenTest", TEST_PLAYER, "VoiceWardenTest", 5,
                        transcript, now, config.serverId()), transcript, nearby, tone), true);
            }
        };
    }

    private static short[] testTone() {
        int samples = DiscordMessages.AUDIO_SAMPLE_RATE / 2;
        short[] tone = new short[samples];
        for (int i = 0; i < samples; i++) {
            tone[i] = (short) (Math.sin(2 * Math.PI * 440 * i / DiscordMessages.AUDIO_SAMPLE_RATE) * 8000);
        }
        return tone;
    }

    @FunctionalInterface
    private interface MessageSource {
        WebhookMessage build();
    }
}
