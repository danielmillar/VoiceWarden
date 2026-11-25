package dev.voicewarden.plugin.command;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker implements AutoCloseable {

    public static final URI DEFAULT_RELEASES_URI = URI.create("https://api.github.com/repos/danielmillar/VoiceWarden/releases/latest");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final String USER_AGENT = "VoiceWarden-UpdateCheck";

    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION = Pattern.compile("v?\\d+(?:\\.\\d+)*(?:[-+][0-9A-Za-z.-]+)?");
    private static final int MAX_ERROR_LENGTH = 120;

    public enum Status {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        FAILED
    }

    public record Result(Status status, String current, String latest, String message) {

        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(current, "current");
            latest = latest == null ? "" : latest;
            message = message == null ? "" : message;
        }
    }

    private final URI releases;
    private final String currentVersion;
    private final Duration timeout;
    private final HttpClient http;

    public UpdateChecker(URI releases, String currentVersion, Duration timeout) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String currentVersion() {
        return currentVersion;
    }

    public CompletableFuture<Result> check() {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(releases)
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(failed("bad release URL " + releases));
        }
        try {
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(this::interpret)
                    .exceptionally(error -> failed(describe(error)));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(failed(describe(e)));
        }
    }

    Result interpret(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return failed("the release server answered HTTP " + response.statusCode());
        }
        Optional<String> latest = parseVersion(response.body());
        if (latest.isEmpty()) {
            return failed("the release server did not name a version");
        }
        String version = latest.get();
        if (compare(version, currentVersion) > 0) {
            return new Result(Status.UPDATE_AVAILABLE, currentVersion, version, "");
        }
        return new Result(Status.UP_TO_DATE, currentVersion, version, "");
    }

    public static Optional<String> parseVersion(String body) {
        if (body == null) {
            return Optional.empty();
        }
        Matcher tag = TAG_NAME.matcher(body);
        if (tag.find()) {
            return Optional.of(strip(tag.group(1)));
        }
        String trimmed = body.trim();
        return VERSION.matcher(trimmed).matches() ? Optional.of(strip(trimmed)) : Optional.empty();
    }

    public static int compare(String left, String right) {
        List<Integer> a = numbers(left);
        List<Integer> b = numbers(right);
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        boolean leftPre = isPreRelease(left);
        boolean rightPre = isPreRelease(right);
        if (leftPre != rightPre) {
            return leftPre ? -1 : 1;
        }
        return 0;
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    private Result failed(String message) {
        return new Result(Status.FAILED, currentVersion, "", message);
    }

    private static String strip(String version) {
        String trimmed = version.trim();
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
    }

    private static boolean isPreRelease(String version) {
        return strip(version).contains("-");
    }

    private static List<Integer> numbers(String version) {
        String core = strip(version).split("[-+]", 2)[0];
        List<Integer> parts = new ArrayList<>();
        for (String part : core.split("\\.")) {
            try {
                parts.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                parts.add(0);
            }
        }
        return parts;
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof HttpTimeoutException) {
            return "the release server did not answer in time";
        }
        String message = cause.getMessage();
        String text = message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ");
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
