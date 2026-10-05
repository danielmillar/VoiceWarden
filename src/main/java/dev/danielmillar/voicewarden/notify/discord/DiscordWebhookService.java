package dev.danielmillar.voicewarden.notify.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.danielmillar.voicewarden.moderation.*;
import dev.danielmillar.voicewarden.moderation.rules.RuleAction;
import dev.danielmillar.voicewarden.moderation.rules.RuleMatch;
import dev.danielmillar.voicewarden.report.VoiceReport;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * One consumer owns payload construction, blocking HTTP, retry waits and logging. Producers only inspect
 * routing settings and enqueue immutable domain records. Call {@link #start()} before submitting work.
 * Closing signals cancellation and returns immediately; no join or network IO runs on the caller.
 */
public final class DiscordWebhookService implements AutoCloseable {
    private static final String USER_AGENT = "VoiceWarden (https://github.com/danielmillar, 1.0)";
    private static final Set<String> HOSTS = Set.of("discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com");
    private static final Pattern TOKEN = Pattern.compile("(?i)(/api/webhooks/[^/\\s?#]+/)[^\\s\"'<>]*");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    @FunctionalInterface interface Sleeper { void sleep(Duration duration) throws InterruptedException; }

    private sealed interface Job permits EventJob, TestJob {}
    private record EventJob(Object event) implements Job {}
    private record TestJob(WebhookTestTarget target, CompletableFuture<List<String>> result) implements Job {}
    private record Route(String name, WebhookSettings webhook) {}
    private record Delivery(boolean success, int status, String detail) {
        String line(String name) { return name + (success ? ": OK (" + status + ")" : ": FAILED " + (status == 0 ? "" : status + " ") + detail); }
    }

    private final Supplier<DiscordSettings> settings;
    private final HttpClient http;
    private final Logger logger;
    private final Predicate<URI> validator;
    private final Sleeper sleeper;
    // Claimable entries make cancellation O(1), including a producer racing the consumer's final drain.
    private final ConcurrentLinkedQueue<AtomicReference<Job>> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final Semaphore available = new Semaphore(0);
    private final ConcurrentHashMap<String, Boolean> validated = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> invalidUrls = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean overflowNotice = new AtomicBoolean();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Map<String, Long> nextAllowed = new HashMap<>(); // consumer-owned monotonic deadlines
    private final Set<String> warnedRoles = new HashSet<>();
    private static final int NEW = 0, STARTING = 1, RUNNING = 2, CLOSED = 3;
    private final AtomicInteger lifecycle = new AtomicInteger(NEW);
    private volatile Thread consumer;
    private boolean overflowWarned;
    private long lastOverflowWarning;

    public DiscordWebhookService(Supplier<DiscordSettings> settings, HttpClient http, Logger logger) {
        this(settings, http, logger, DiscordWebhookService::validDiscordUrl);
    }

    DiscordWebhookService(Supplier<DiscordSettings> settings, HttpClient http, Logger logger, Predicate<URI> validator) {
        this(settings, http, logger, validator, duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos()));
    }

    DiscordWebhookService(Supplier<DiscordSettings> settings, HttpClient http, Logger logger,
                          Predicate<URI> validator, Sleeper sleeper) {
        this.settings = Objects.requireNonNull(settings);
        this.http = Objects.requireNonNull(http);
        this.logger = Objects.requireNonNull(logger);
        this.validator = Objects.requireNonNull(validator);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    /** No HTTP, encoding or logging takes place here. Configuration suppliers must themselves be nonblocking. */
    public void start() {
        if (!lifecycle.compareAndSet(NEW, STARTING)) return;
        try {
            Thread thread = Thread.ofVirtual().name("VoiceWarden-Discord").unstarted(this::consume);
            consumer = thread;
            if (lifecycle.compareAndSet(STARTING, RUNNING)) thread.start();
        } catch (RuntimeException failure) {
            lifecycle.set(CLOSED);
            throw failure;
        }
    }

    public boolean submitIncident(Incident incident) { return submit(Objects.requireNonNull(incident)); }
    public boolean submitStaffAction(StaffAction action) { return submit(Objects.requireNonNull(action)); }
    public boolean submitReport(VoiceReport report) { return submit(Objects.requireNonNull(report)); }

    private boolean submit(Object event) {
        if (!running() || routes(event, settings.get()).isEmpty()) return false;
        return enqueue(new EventJob(event));
    }

    public CompletableFuture<List<String>> sendTest(WebhookTestTarget target) {
        Objects.requireNonNull(target);
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        if (!enqueue(new TestJob(target, result))) result.complete(List.of("test: FAILED service stopped or queue full"));
        return result;
    }

    private boolean enqueue(Job job) {
        if (!running()) return false;
        int size;
        do {
            size = queued.get();
            if (size >= Math.max(0, settings.get().maxQueueSize())) {
                dropped.incrementAndGet();
                if (overflowNotice.compareAndSet(false, true)) available.release();
                return false;
            }
        } while (!queued.compareAndSet(size, size + 1));
        AtomicReference<Job> entry = new AtomicReference<>(job);
        queue.add(entry);
        available.release();
        // Close can race the reservation above. A late producer can safely discard its own item.
        if (!running() && entry.getAndSet(null) != null) {
            queued.decrementAndGet();
            dropped.incrementAndGet();
            completeDiscarded(job);
            return false;
        }
        return true;
    }

    public int queueSize() { return queued.get(); }
    public long sentCount() { return sent.get(); }
    public long droppedCount() { return dropped.get(); }
    public long failedCount() { return failed.get(); }

    @Override public void close() {
        lifecycle.set(CLOSED);
        Thread thread = consumer;
        if (thread != null) thread.interrupt();
    }

    private void consume() {
        Job current = null;
        try {
            while (running()) {
                available.acquire();
                notices();
                if (!running()) break;
                AtomicReference<Job> entry = queue.poll();
                current = entry == null ? null : entry.getAndSet(null);
                if (current == null) continue;
                queued.decrementAndGet();
                try {
                    if (current instanceof EventJob event) process(event.event());
                    else if (current instanceof TestJob test) processTest(test);
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (RuntimeException failure) {
                    failed.incrementAndGet();
                    warn("Discord delivery failed: " + failure.getClass().getSimpleName());
                    if (current instanceof TestJob test) test.result().complete(List.of("test: FAILED " + failure.getClass().getSimpleName()));
                }
                current = null;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            lifecycle.set(CLOSED);
            if (current != null) completeDiscarded(current);
            int discarded = 0;
            AtomicReference<Job> entry;
            while ((entry = queue.poll()) != null) {
                Job job = entry.getAndSet(null);
                if (job == null) continue;
                queued.decrementAndGet();
                completeDiscarded(job);
                discarded++;
            }
            dropped.addAndGet(discarded);
            notices();
            info("Discord consumer stopped; discarded " + discarded + " pending messages");
        }
    }

    private static void completeDiscarded(Job job) {
        if (job instanceof TestJob test) test.result().complete(List.of("test: FAILED service closed"));
    }

    private void process(Object event) throws InterruptedException {
        DiscordSettings current = settings.get();
        for (Route route : routes(event, current)) {
            if (!running()) throw new InterruptedException();
            send(event, route.name(), current);
        }
    }

    private void processTest(TestJob test) throws InterruptedException {
        List<String> lines = new ArrayList<>();
        for (String name : List.of("flags", "mutes", "reports")) {
            boolean selected = test.target() == WebhookTestTarget.ALL || switch (test.target()) {
                case FLAG -> name.equals("flags");
                case MUTE -> name.equals("mutes");
                case REPORT -> name.equals("reports");
                case ALL -> true;
            };
            if (selected) {
                Delivery delivery = send(sample(name), name);
                lines.add(delivery == null ? name + ": disabled" : delivery.line(name));
            }
        }
        test.result().complete(List.copyOf(lines));
    }

    /** Reload immediately before each destination send, rather than retaining submission-time settings. */
    private Delivery send(Object event, String name) throws InterruptedException {
        return send(event, name, settings.get());
    }

    private Delivery send(Object event, String name, DiscordSettings current) throws InterruptedException {
        WebhookSettings webhook = target(current, name);
        if (!usable(webhook)) return null;
        String role = webhook.pingRoleId();
        if (!role.isEmpty() && (!DIGITS.matcher(role).matches() || role.length() > 1996)) {
            if (warnedRoles.add(role)) warn("Ignoring invalid Discord ping role id");
            role = "";
        }
        DiscordPayload.Body body = DiscordPayload.build(event, current, webhook, role);
        Delivery result = deliver(webhook.url(), body, current);
        if (result.success()) sent.incrementAndGet();
        else {
            failed.incrementAndGet();
            warn("Discord " + name + " delivery to " + mask(webhook.url()) + " failed: "
                    + (result.status() == 0 ? "" : result.status() + " ") + result.detail());
        }
        return result;
    }

    private Delivery deliver(String url, DiscordPayload.Body body, DiscordSettings current) throws InterruptedException {
        int retries = Math.max(0, current.retryAttempts());
        int failures = 0;
        long maximumAttempts = (long) retries + 5;
        Delivery last = new Delivery(false, 0, "attempt limit reached");
        for (long attempt = 0; attempt < maximumAttempts; attempt++) {
            if (!running()) throw new InterruptedException();
            Long deadline = nextAllowed.remove(url);
            if (deadline != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) sleeper.sleep(Duration.ofNanos(remaining));
            }
            if (!running()) throw new InterruptedException();
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(1, current.timeoutSeconds())))
                        .header("User-Agent", USER_AGENT).header("Content-Type", body.contentType())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes())).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")) {
                    double seconds = seconds(response.headers().firstValue("X-RateLimit-Reset-After").orElse(""), 0);
                    defer(url, seconds);
                }
                if (status >= 200 && status < 300) return new Delivery(true, status, "");
                last = new Delivery(false, status, responseDetail(response.body()));
                if (status == 429) {
                    double delay = retryAfter(response);
                    defer(url, delay);
                    continue; // limited by the total attempt cap, independently of ordinary retries
                }
                if (status < 500 || status > 599 || failures++ >= retries) return last;
            } catch (IOException failure) {
                last = new Delivery(false, 0, failure.getClass().getSimpleName());
                if (failures++ >= retries) return last;
            }
            if (attempt + 1 < maximumAttempts) sleeper.sleep(Duration.ofSeconds(1L << Math.min(failures - 1, 30)));
        }
        return last;
    }

    private void defer(String url, double seconds) {
        if (seconds <= 0) return;
        // Bound pathological server values so conversion and monotonic subtraction cannot overflow.
        long nanos = (long) (Math.min(seconds, 86_400 * 365.0) * 1_000_000_000);
        long deadline = System.nanoTime() + nanos;
        nextAllowed.merge(url, deadline, (oldValue, newValue) -> oldValue - newValue > 0 ? oldValue : newValue);
    }

    private static double retryAfter(HttpResponse<String> response) {
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("retry_after")) {
                double delay = seconds(json.get("retry_after").getAsString(), Double.NaN);
                if (!Double.isNaN(delay)) return delay;
            }
        } catch (RuntimeException ignored) { /* header fallback */ }
        return seconds(response.headers().firstValue("Retry-After").orElse(""), 1);
    }

    private static double seconds(String value, double fallback) {
        try {
            double seconds = Double.parseDouble(value);
            return Double.isFinite(seconds) && seconds >= 0 ? seconds : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    private static String responseDetail(String body) {
        String detail = body;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("message")) detail = json.get("message").getAsString();
        } catch (RuntimeException ignored) { /* non-JSON error */ }
        detail = mask(detail).replaceAll("[\\r\\n\\p{Cntrl}]", " ");
        return detail.length() <= 180 ? detail : detail.substring(0, 179) + "…";
    }

    private List<Route> routes(Object event, DiscordSettings current) {
        List<Route> routes = new ArrayList<>(2);
        if (event instanceof Incident incident) {
            if (usable(current.flags())) routes.add(new Route("flags", current.flags()));
            if (incident.action().type() == ActionType.MUTE && usable(current.mutes())
                    && routes.stream().noneMatch(route -> route.webhook().url().equals(current.mutes().url()))) {
                routes.add(new Route("mutes", current.mutes()));
            }
        } else if (event instanceof StaffAction) {
            if (usable(current.mutes())) routes.add(new Route("mutes", current.mutes()));
        } else if (event instanceof VoiceReport) {
            if (usable(current.reports())) routes.add(new Route("reports", current.reports()));
        }
        return routes;
    }

    private static WebhookSettings target(DiscordSettings current, String name) {
        return switch (name) { case "flags" -> current.flags(); case "mutes" -> current.mutes(); case "reports" -> current.reports(); default -> throw new IllegalArgumentException(name); };
    }

    private boolean usable(WebhookSettings webhook) {
        if (!webhook.usable()) return false;
        Boolean cached = validated.get(webhook.url());
        if (cached != null) return cached;
        boolean valid;
        try { valid = validator.test(URI.create(webhook.url())); }
        catch (RuntimeException invalid) { valid = false; }
        Boolean previous = validated.putIfAbsent(webhook.url(), valid);
        if (previous == null && !valid) {
            invalidUrls.add(webhook.url());
            available.release();
        }
        return previous == null ? valid : previous;
    }

    static boolean validDiscordUrl(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) && uri.getUserInfo() == null
                && uri.getPath() != null && uri.getPath().startsWith("/api/webhooks/");
    }

    static String mask(String value) { return TOKEN.matcher(value).replaceAll("$1***"); }

    private void notices() {
        String url;
        while ((url = invalidUrls.poll()) != null) warn("Ignoring invalid Discord webhook URL: " + mask(url));
        if (overflowNotice.getAndSet(false)) {
            long now = System.nanoTime();
            if (!overflowWarned || now - lastOverflowWarning >= Duration.ofSeconds(60).toNanos()) {
                overflowWarned = true;
                lastOverflowWarning = now;
                warn("Discord queue full; new messages dropped (total " + dropped.get() + ")");
            }
        }
    }

    private void warn(String message) { try { logger.warning(message); } catch (RuntimeException ignored) {} }
    private void info(String message) { try { logger.info(message); } catch (RuntimeException ignored) {} }

    private boolean running() { return lifecycle.get() == RUNNING; }

    private static Object sample(String route) {
        Instant now = Instant.now();
        UUID player = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        short[] audio = new short[1600];
        for (int index = 0; index < audio.length; index++) audio[index] = (short) (Math.sin(2 * Math.PI * 440 * index / 16_000) * 6000);
        if (route.equals("reports")) return new VoiceReport(UUID.randomUUID(), now, UUID.randomUUID(), "SampleReporter", player,
                "SamplePlayer", Duration.ofMinutes(1), List.of(new TranscriptLine(now.minusSeconds(12), "This is a sample voice report.")), audio, 16_000);
        String text = "This sample badword demonstrates a voice alert.";
        int start = text.indexOf("badword");
        boolean mute = route.equals("mutes");
        return new Incident(UUID.randomUUID(), now, now.minusMillis(100), Duration.ofMillis(100), player, "SamplePlayer", text,
                List.of(new RuleMatch("sample", "Sample rule", RuleAction.MUTE, 1, "badword", start, start + 7)), 1, mute ? 3 : 1, 3,
                mute ? ModerationAction.mute(Duration.ofMinutes(15)) : ModerationAction.NONE, mute ? 1 : 0, true,
                List.of(new TranscriptLine(now.minusSeconds(12), "Sample preceding voice context.")), new SpeakerLocation("world", 12, 64, 34), audio, 16_000);
    }
}
