package dev.danielmillar.voicewarden.health;

import dev.danielmillar.voicewarden.evidence.EvidenceSettings;
import dev.danielmillar.voicewarden.notify.Formats;
import dev.danielmillar.voicewarden.notify.discord.DiscordSettings;
import dev.danielmillar.voicewarden.notify.discord.DiscordWebhookService;
import dev.danielmillar.voicewarden.notify.discord.WebhookSettings;
import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.stt.ModelManager;
import dev.danielmillar.voicewarden.stt.TranscriptionService.State;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * {@code /vw check}: turns facts about the running plugin into OK, WARN and FAIL findings, each problem with the fix.
 * Pure, so every status rule is testable; the plugin gathers the facts, off the server thread for disk and database.
 */
public final class SetupCheck {

    public enum Status { OK, WARN, FAIL }

    /** One line of the report. {@code fix} is empty for OK. */
    public record Finding(Status status, String area, String text, String fix) {
        static Finding ok(String area, String text) {
            return new Finding(Status.OK, area, text, "");
        }
    }

    public record Facts(Voice voice, Engine engine, Models models, Store database, LuckPerms luckPerms, Discord discord,
                        Config config, Recordings recordings) {
    }

    /** @param version Simple Voice Chat's version, or "" when unknown */
    public record Voice(String version, boolean registered, boolean noticeHook) {
    }

    /**
     * @param dropped utterances and packets skipped since start because transcription fell behind
     * @param platform the native-library platform id, or why this machine has none
     */
    public record Engine(boolean enabled, State state, String detail, String model, @Nullable String lastFailure,
                         boolean doubleCheckWanted, boolean doubleCheckLoaded, int workers, int aliveWorkers,
                         int queue, int queueMax, long dropped, long errors, long p95Millis, double speed,
                         boolean nativesPresent, String nativesVersion, String platform, boolean autoDownload) {
    }

    /** @param hashedAt when the running engine verified these exact settings' files by SHA-256, or null */
    public record Models(List<ModelManager.FileState> files, boolean autoDownload, @Nullable Instant hashedAt) {
    }

    /** @param error why the read failed, or null when it worked */
    public record Store(int schema, int expectedSchema, long mutes, @Nullable String error) {
    }

    public record LuckPerms(boolean installed, boolean connected, boolean mirror) {
    }

    public record Discord(DiscordSettings settings, long sent, long failed, long dropped) {
    }

    public record Config(boolean dryRun, int rules, List<String> warnings) {
    }

    /** @param error why the folder is not writable, or null */
    public record Recordings(EvidenceSettings settings, @Nullable String error, long usedBytes, long freeBytes) {
    }

    static final int MAX_CONFIG_WARNINGS = 5;
    static final long LOW_DISK_BYTES = 1L << 30;

    private SetupCheck() {
    }

    public static List<Finding> evaluate(Facts facts) {
        List<Finding> out = new ArrayList<>();
        voice(facts.voice(), out);
        engine(facts.engine(), out);
        models(facts.models(), out);
        database(facts.database(), out);
        luckPerms(facts.luckPerms(), out);
        discord(facts.discord(), out);
        config(facts.config(), out);
        recordings(facts.recordings(), out);
        return out;
    }

    static void voice(Voice v, List<Finding> out) {
        String area = "Voice chat";
        String version = v.version().isEmpty() ? "" : " " + v.version();
        if (!v.registered()) {
            out.add(new Finding(Status.FAIL, area, "Simple Voice Chat" + version + " has not loaded VoiceWarden, so voice is"
                    + " not moderated and mutes are not enforced",
                    "restart the server (voice chat picks up plugins at startup) and check the console for voice chat errors"));
            return;
        }
        out.add(Finding.ok(area, "Simple Voice Chat" + version + ", VoiceWarden registered"));
        if (!v.noticeHook()) {
            out.add(new Finding(Status.WARN, area, "could not replace voice chat's \"no permission to speak\" message,"
                    + " so muted players see the voice chat one; mutes still work",
                    "use Simple Voice Chat 2.6.x; see the console at startup for the reason"));
        }
    }

    static void engine(Engine e, List<Finding> out) {
        String area = "Speech engine";
        if (!e.enabled()) {
            out.add(new Finding(Status.WARN, area, "off (enabled: false): voice is not transcribed or moderated;"
                    + " mutes are still enforced", "set enabled: true in config.yml, then /vw reload"));
        } else {
            switch (e.state()) {
                case READY -> out.add(Finding.ok(area, "ready: " + e.detail()));
                case PREPARING, LOADING -> out.add(new Finding(Status.WARN, area, "starting (" + e.detail() + ")",
                        "wait and run /vw check again; the first start downloads the model"));
                case FAILED -> out.add(new Finding(Status.FAIL, area, "failed: " + e.detail(),
                        "fix the cause, then /vw reload"));
                case STOPPED -> out.add(new Finding(Status.FAIL, area, "stopped", "/vw reload starts it"));
            }
        }
        boolean ready = e.state() == State.READY;
        if (ready && e.lastFailure() != null) {
            out.add(new Finding(Status.WARN, area, "the last reload could not load the new settings (" + e.lastFailure()
                    + "), so the previous engine is still running", "fix the cause, then /vw reload"));
        }
        if (ready && e.doubleCheckWanted()) {
            out.add(e.doubleCheckLoaded()
                    ? Finding.ok("Double check", "second model loaded; mute-list hits are confirmed before muting")
                    : new Finding(Status.WARN, "Double check", "speech-to-text.double-check is on but the second model"
                    + " is not loaded, so mute-list hits are not confirmed", "check the console, then /vw reload"));
        }
        if (ready) {
            workers(e, out);
        }
        natives(e, out);
    }

    private static void workers(Engine e, List<Finding> out) {
        String area = "Transcription";
        int before = out.size();
        if (e.aliveWorkers() < e.workers()) {
            out.add(new Finding(Status.FAIL, area, (e.workers() - e.aliveWorkers()) + " of " + e.workers()
                    + " worker(s) crashed", "see \"worker crashed\" in the console, then restart the server"));
        }
        String speed = String.format(Locale.ROOT, "%.1f", e.speed());
        if (e.dropped() > 0) {
            out.add(new Finding(Status.WARN, area, e.dropped() + " utterance(s) or packet(s) skipped since start because"
                    + " transcription fell behind (speed " + speed + "× real time)",
                    "raise speech-to-text.workers or cpu-threads if the CPU has room"));
        }
        if (e.errors() > 0) {
            out.add(new Finding(Status.WARN, area, e.errors() + " transcription error(s) since start",
                    "see \"Transcription failed\" in the console"));
        }
        if (e.p95Millis() > HealthMonitor.SLOW_P95_MILLIS) {
            out.add(new Finding(Status.WARN, area, "slow: 95% of utterances take up to " + e.p95Millis()
                    + " ms", "raise speech-to-text.cpu-threads or pick a smaller model"));
        }
        if (out.size() == before) {
            out.add(Finding.ok(area, e.aliveWorkers() + " worker(s), queue " + e.queue() + "/" + e.queueMax()
                    + (e.p95Millis() < 0 ? "" : ", p95 " + e.p95Millis() + " ms, speed " + speed + "×")));
        }
    }

    private static void natives(Engine e, List<Finding> out) {
        String area = "Native libraries";
        String which = "sherpa-onnx " + e.nativesVersion() + ", " + e.platform();
        if (e.state() == State.READY) {
            out.add(Finding.ok(area, "loaded (" + which + ")"));
        } else if (e.nativesPresent()) {
            out.add(Finding.ok(area, "on disk (" + which + "), not loaded yet"));
        } else if (e.autoDownload()) {
            out.add(new Finding(Status.WARN, area, "not downloaded yet (" + which + ")",
                    e.enabled() ? "they download when the engine starts; check again shortly" : "they download once enabled"));
        } else {
            out.add(new Finding(Status.FAIL, area, "missing (" + which + ") and speech-to-text.auto-download is false",
                    "set auto-download: true, or install them as the console explains"));
        }
    }

    static void models(Models m, List<Finding> out) {
        String area = "Model files";
        if (m.files().isEmpty()) {
            out.add(new Finding(Status.FAIL, area, "speech-to-text.model names no known model",
                    "use parakeet-tdt-v2, parakeet-tdt-v3, whisper-small-en, whisper-turbo or custom"));
            return;
        }
        int before = out.size();
        for (ModelManager.FileState f : m.files()) {
            if (f.matchesPin()) {
                continue;
            }
            String problem = !f.present() ? f.label() + " is missing"
                    : f.label() + " is " + f.actualSize() + " bytes, expected " + f.expectedSize();
            boolean custom = f.expectedSize() < 0;
            String fix = custom ? "check the path in speech-to-text.custom"
                    : m.autoDownload() ? "restart the server or change the model to download it again"
                    : "download it again, or set speech-to-text.auto-download: true";
            out.add(new Finding(m.autoDownload() && !custom && !f.present() ? Status.WARN : Status.FAIL, area, problem, fix));
        }
        if (out.size() > before) {
            return;
        }
        boolean pinned = m.files().stream().allMatch(f -> f.expectedSize() >= 0);
        String hash = !pinned ? "custom model, not pinned"
                : m.hashedAt() != null ? "SHA-256 verified at load " + Formats.dateTime(m.hashedAt())
                : "SHA-256 is verified when the engine loads";
        out.add(Finding.ok(area, m.files().size() + " file(s) present at their expected size; " + hash));
    }

    static void database(Store s, List<Finding> out) {
        String area = "Database";
        if (s.error() != null) {
            out.add(new Finding(Status.FAIL, area, "read failed: " + s.error(),
                    "check the console and plugins/VoiceWarden/data/voicewarden.db"));
        } else if (s.schema() != s.expectedSchema()) {
            out.add(new Finding(Status.FAIL, area, "schema " + s.schema() + ", expected " + s.expectedSchema(),
                    "restart the server so VoiceWarden can migrate it"));
        } else {
            out.add(Finding.ok(area, "schema " + s.schema() + ", read OK (" + s.mutes() + " mute(s) stored)"));
        }
    }

    static void luckPerms(LuckPerms l, List<Finding> out) {
        String area = "LuckPerms";
        if (!l.installed()) {
            out.add(l.mirror()
                    ? new Finding(Status.WARN, area, "not installed, so mute.luckperms.enabled is ignored: mutes apply"
                    + " on this server only", "install LuckPerms, or set mute.luckperms.enabled: false")
                    : Finding.ok(area, "not installed (optional)"));
        } else if (!l.connected()) {
            out.add(new Finding(Status.WARN, area, "installed but its API was not available at startup, so mutes are"
                    + " not mirrored and offline bypass checks fail", "check LuckPerms started cleanly, then restart"));
        } else {
            out.add(Finding.ok(area, l.mirror() ? "connected; mutes are mirrored as deny nodes"
                    : "connected; mute mirror off (mute.luckperms.enabled: false)"));
        }
    }

    static void discord(Discord d, List<Finding> out) {
        String area = "Discord";
        DiscordSettings s = d.settings();
        List<String> ready = new ArrayList<>();
        int before = out.size();
        webhook("flags", s.flags(), ready, out);
        webhook("mutes", s.mutes(), ready, out);
        webhook("reports", s.reports(), ready, out);
        if (d.failed() > 0) {
            out.add(new Finding(Status.WARN, area, d.failed() + " message(s) failed to send since start",
                    "run /vw discordtest to see the error"));
        }
        if (d.dropped() > 0) {
            out.add(new Finding(Status.WARN, area, d.dropped() + " message(s) dropped because the queue was full",
                    "raise discord.max-queue-size or check for rate limits"));
        }
        if (out.size() > before) {
            return;
        }
        out.add(Finding.ok(area, ready.isEmpty() ? "no webhooks enabled"
                : String.join(", ", ready) + " webhook(s) valid, " + d.sent() + " sent; /vw discordtest sends a test"));
    }

    private static void webhook(String name, WebhookSettings w, List<String> ready, List<Finding> out) {
        if (!w.enabled()) {
            return;
        }
        String key = "discord." + name + ".webhook-url";
        if (w.url().isBlank()) {
            out.add(new Finding(Status.WARN, "Discord", name + " webhook is enabled but has no URL, so nothing is sent",
                    "set " + key + ", or discord." + name + ".enabled: false"));
            return;
        }
        boolean valid;
        try {
            valid = DiscordWebhookService.validDiscordUrl(URI.create(w.url()));
        } catch (IllegalArgumentException e) {
            valid = false;
        }
        if (!valid) {
            out.add(new Finding(Status.FAIL, "Discord", name + " webhook URL is not a Discord webhook ("
                    + DiscordWebhookService.mask(w.url()) + "), so nothing is sent",
                    "set " + key + " to https://discord.com/api/webhooks/…"));
            return;
        }
        ready.add(name);
    }

    static void config(Config c, List<Finding> out) {
        String area = "Config";
        if (c.dryRun()) {
            out.add(new Finding(Status.WARN, area, "moderation.dry-run is on: actions are decided but never applied",
                    "set moderation.dry-run: false when you trust the rules"));
        }
        for (String warning : c.warnings().stream().limit(MAX_CONFIG_WARNINGS).toList()) {
            out.add(new Finding(Status.WARN, area, warning, "fix the file, then /vw reload"));
        }
        if (c.warnings().size() > MAX_CONFIG_WARNINGS) {
            out.add(new Finding(Status.WARN, area, (c.warnings().size() - MAX_CONFIG_WARNINGS) + " more warning(s)",
                    "see the console after /vw reload"));
        }
        if (!c.dryRun() && c.warnings().isEmpty()) {
            out.add(Finding.ok(area, "loaded without warnings, " + c.rules() + " rule(s)"));
        }
    }

    static void recordings(Recordings r, List<Finding> out) {
        String area = "Recordings";
        EvidenceSettings s = r.settings();
        if (s.mode() == EvidenceSettings.Mode.NONE) {
            out.add(Finding.ok(area, "off (recordings.mode: none)"));
            return;
        }
        if (r.error() != null) {
            out.add(new Finding(Status.FAIL, area, "cannot write to " + s.directory() + ": " + r.error(),
                    "fix the folder's permissions or change recordings.directory"));
            return;
        }
        String used = mb(r.usedBytes()) + (s.maxTotalBytes() > 0 ? " of " + mb(s.maxTotalBytes()) : "");
        int before = out.size();
        if (s.maxTotalBytes() > 0 && r.usedBytes() > s.maxTotalBytes()) {
            out.add(new Finding(Status.WARN, area, "using " + used + ", over the limit",
                    "cleanup runs hourly; if this stays, check the console for evidence errors"));
        }
        if (r.freeBytes() >= 0 && r.freeBytes() < LOW_DISK_BYTES) {
            out.add(new Finding(Status.WARN, area, "only " + mb(r.freeBytes()) + " free on the disk",
                    "free space, or lower recordings.max-total-size-mb"));
        }
        if (out.size() == before) {
            out.add(Finding.ok(area, "writable, using " + used + ", " + mb(r.freeBytes()) + " free"));
        }
    }

    /** A trivial read queued behind pending writes; never fails, a problem becomes {@link Store#error()}. */
    public static CompletableFuture<Store> readDatabase(Database database, Duration timeout) {
        return database.query(connection -> {
            try (var s = connection.createStatement()) {
                int schema;
                try (var r = s.executeQuery("PRAGMA user_version")) {
                    schema = r.next() ? r.getInt(1) : 0;
                }
                try (var r = s.executeQuery("SELECT COUNT(*) FROM mutes")) {
                    return new Store(schema, Database.SCHEMA_VERSION, r.next() ? r.getLong(1) : 0, null);
                }
            }
        }).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).exceptionally(error -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            String reason = cause instanceof TimeoutException ? "no answer within " + timeout.toSeconds() + " seconds"
                    : String.valueOf(cause.getMessage());
            return new Store(0, Database.SCHEMA_VERSION, 0, reason);
        });
    }

    /** Disk side of {@link Recordings}: proves the folder takes a write and totals its evidence. Blocking. */
    public static Recordings inspectRecordings(EvidenceSettings settings) {
        if (settings.mode() == EvidenceSettings.Mode.NONE) {
            return new Recordings(settings, null, 0, -1);
        }
        Path dir = settings.directory();
        try {
            Files.createDirectories(dir);
            Files.delete(Files.createTempFile(dir, ".voicewarden-check-", ".tmp"));
        } catch (IOException | RuntimeException e) {
            return new Recordings(settings, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), 0, -1);
        }
        long used = 0;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".wav")).toList()) {
                used += Files.size(p);
            }
        } catch (IOException | RuntimeException e) {
            return new Recordings(settings, "could not list it: " + e.getMessage(), 0, -1);
        }
        long free;
        try {
            free = Files.getFileStore(dir).getUsableSpace();
        } catch (IOException e) {
            free = -1;
        }
        return new Recordings(settings, null, used, free);
    }

    private static String mb(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        return bytes >= 1L << 30 ? String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30))
                : String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
    }
}
