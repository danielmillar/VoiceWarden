package dev.danielmillar.voicewarden.health;

import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.evidence.EvidenceSettings;
import dev.danielmillar.voicewarden.health.SetupCheck.Finding;
import dev.danielmillar.voicewarden.health.SetupCheck.Status;
import dev.danielmillar.voicewarden.notify.discord.DiscordSettings;
import dev.danielmillar.voicewarden.notify.discord.WebhookSettings;
import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.stt.ModelManager;
import dev.danielmillar.voicewarden.stt.TranscriptionService.State;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SetupCheckTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/secret-token";

    @TempDir Path folder;
    private SetupCheck.Facts healthy;

    @BeforeEach
    void setUp() throws Exception {
        PluginConfig config = Fakes.config(folder);
        var webhook = Fakes.with(Fakes.with(WebhookSettings.disabled(), "enabled", true), "url", WEBHOOK);
        var file = new ModelManager.FileState("model.onnx", folder.resolve("model.onnx"), 10, 10);
        healthy = new SetupCheck.Facts(
                new SetupCheck.Voice("2.6.25", true, true),
                new SetupCheck.Engine(true, State.READY, "Whisper", "whisper-turbo", null, false, false, 2, 2, 0, 64,
                        0, 0, 800, 6.0, true, "1.13.8", "linux-x64", true),
                new SetupCheck.Models(List.of(file), true, Instant.now()),
                new SetupCheck.Store(Database.SCHEMA_VERSION, Database.SCHEMA_VERSION, 3, null),
                new SetupCheck.LuckPerms(true, true, true),
                new SetupCheck.Discord(Fakes.with(config.discord(), "flags", webhook), 4, 0, 0),
                new SetupCheck.Config(false, 12, List.of()),
                new SetupCheck.Recordings(config.recordings(), null, 1024, 50L << 30));
    }

    @Test
    void healthySetupIsAllOk() {
        List<Finding> findings = SetupCheck.evaluate(healthy);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().allMatch(f -> f.status() == Status.OK && f.fix().isEmpty()), findings::toString);
    }

    @Test
    void everyProblemCarriesAFix() {
        var broken = new SetupCheck.Facts(
                new SetupCheck.Voice("", false, false),
                Fakes.with(healthy.engine(), "state", State.FAILED),
                new SetupCheck.Models(List.of(), false, null),
                new SetupCheck.Store(0, 2, 0, "locked"),
                new SetupCheck.LuckPerms(false, false, true),
                Fakes.with(healthy.discord(), "failed", 2L),
                new SetupCheck.Config(true, 0, List.of("bad")),
                Fakes.with(healthy.recordings(), "error", "denied"));
        assertTrue(SetupCheck.evaluate(broken).stream()
                .filter(f -> f.status() != Status.OK).allMatch(f -> !f.fix().isBlank()));
    }

    // ── voice chat ──────────────────────────────────────────────────────────

    @Test
    void voiceChatNotRegisteredFails() {
        assertEquals(List.of(Status.FAIL), check("voice", new SetupCheck.Voice("2.6.25", false, true)));
    }

    @Test
    void missingPermissionNoticeHookWarns() {
        assertEquals(List.of(Status.OK, Status.WARN), check("voice", new SetupCheck.Voice("2.6.25", true, false)));
    }

    // ── speech engine ───────────────────────────────────────────────────────

    @Test
    void engineStates() {
        assertEquals(Status.FAIL, first(engine("state", State.FAILED)));
        assertEquals(Status.FAIL, first(engine("state", State.STOPPED)));
        assertEquals(Status.WARN, first(engine("state", State.LOADING)));
        assertEquals(Status.WARN, first(engine("state", State.PREPARING)));
        assertEquals(Status.WARN, first(engine("enabled", false)));
    }

    @Test
    void failedReloadWhileTheOldEngineRunsWarns() {
        assertTrue(engine("lastFailure", "bad model").contains(Status.WARN));
    }

    @Test
    void doubleCheckWantedButNotLoadedWarns() {
        var wanted = Fakes.with(healthy.engine(), "doubleCheckWanted", true);
        assertTrue(check("engine", Fakes.with(wanted, "doubleCheckLoaded", false)).contains(Status.WARN));
        assertFalse(check("engine", Fakes.with(wanted, "doubleCheckLoaded", true)).contains(Status.WARN));
    }

    @Test
    void crashedWorkerFails() {
        assertTrue(engine("aliveWorkers", 1).contains(Status.FAIL));
    }

    @Test
    void droppedAudioErrorsAndSlowTranscriptionWarn() {
        assertTrue(engine("dropped", 5L).contains(Status.WARN));
        assertTrue(engine("errors", 1L).contains(Status.WARN));
        assertTrue(engine("p95Millis", HealthMonitor.SLOW_P95_MILLIS + 1).contains(Status.WARN));
    }

    @Test
    void nativeLibraries() {
        var stopped = Fakes.with(Fakes.with(healthy.engine(), "state", State.FAILED), "nativesPresent", false);
        assertEquals(Status.WARN, natives(stopped));
        assertEquals(Status.FAIL, natives(Fakes.with(stopped, "autoDownload", false)));
        assertEquals(Status.OK, natives(Fakes.with(stopped, "nativesPresent", true)));
    }

    // ── model files ─────────────────────────────────────────────────────────

    @Test
    void modelFiles() {
        Path p = folder.resolve("m");
        assertEquals(List.of(Status.WARN), models(new ModelManager.FileState("a", p, 10, -1), true));
        assertEquals(List.of(Status.FAIL), models(new ModelManager.FileState("a", p, 10, -1), false));
        assertEquals(List.of(Status.FAIL), models(new ModelManager.FileState("a", p, 10, 9), true));
        assertEquals(List.of(Status.FAIL), models(new ModelManager.FileState("custom", p, -1, -1), true));
        assertEquals(List.of(Status.OK), models(new ModelManager.FileState("custom", p, -1, 5), true));
        assertEquals(List.of(Status.FAIL), check("models", new SetupCheck.Models(List.of(), true, null)));
    }

    // ── database ────────────────────────────────────────────────────────────

    @Test
    void realDatabaseReadsOk() {
        Database database = Fakes.database(folder);
        try {
            var store = SetupCheck.readDatabase(database, Duration.ofSeconds(5)).join();
            assertNull(store.error());
            assertEquals(List.of(Status.OK), check("database", store));
        } finally {
            database.close();
        }
    }

    @Test
    void closedDatabaseFailsInsteadOfThrowing() {
        Database database = Fakes.database(folder);
        database.close();
        var store = SetupCheck.readDatabase(database, Duration.ofSeconds(5)).join();
        assertNotNull(store.error());
        assertEquals(List.of(Status.FAIL), check("database", store));
    }

    @Test
    void wrongSchemaFails() {
        assertEquals(List.of(Status.FAIL), check("database", new SetupCheck.Store(1, 2, 0, null)));
    }

    // ── LuckPerms ───────────────────────────────────────────────────────────

    @Test
    void luckPerms() {
        assertEquals(List.of(Status.WARN), check("luckPerms", new SetupCheck.LuckPerms(false, false, true)));
        assertEquals(List.of(Status.OK), check("luckPerms", new SetupCheck.LuckPerms(false, false, false)));
        assertEquals(List.of(Status.WARN), check("luckPerms", new SetupCheck.LuckPerms(true, false, true)));
        assertEquals(List.of(Status.OK), check("luckPerms", new SetupCheck.LuckPerms(true, true, false)));
    }

    // ── Discord ─────────────────────────────────────────────────────────────

    @Test
    void invalidWebhookUrlFailsWithoutLeakingTheToken() {
        for (String url : List.of("http://discord.com/api/webhooks/1/secret-token", "https://example.com/hook",
                "not a url at all")) {
            var findings = discord(Fakes.with(healthy.discord().settings().flags(), "url", url));
            assertEquals(List.of(Status.FAIL), findings.stream().map(Finding::status).toList(), url);
            assertFalse(findings.getFirst().text().contains("secret-token"));
        }
    }

    @Test
    void enabledWebhookWithoutUrlWarns() {
        assertEquals(List.of(Status.WARN), discord(Fakes.with(healthy.discord().settings().flags(), "url", ""))
                .stream().map(Finding::status).toList());
    }

    @Test
    void noWebhooksEnabledIsOk() {
        var off = new SetupCheck.Discord(DiscordSettings.disabled(), 0, 0, 0);
        assertEquals(List.of(Status.OK), check("discord", off));
    }

    @Test
    void failedOrDroppedDeliveriesWarn() {
        assertEquals(List.of(Status.WARN), check("discord", Fakes.with(healthy.discord(), "failed", 1L)));
        assertEquals(List.of(Status.WARN), check("discord", Fakes.with(healthy.discord(), "dropped", 1L)));
    }

    // ── config ──────────────────────────────────────────────────────────────

    @Test
    void dryRunAndLoadWarningsWarn() {
        assertEquals(List.of(Status.WARN), check("config", new SetupCheck.Config(true, 1, List.of())));
        var many = List.of("1", "2", "3", "4", "5", "6", "7");
        List<Status> lines = check("config", new SetupCheck.Config(false, 1, many));
        assertEquals(SetupCheck.MAX_CONFIG_WARNINGS + 1, lines.size());
        assertTrue(lines.stream().allMatch(s -> s == Status.WARN));
    }

    // ── recordings ──────────────────────────────────────────────────────────

    @Test
    void recordingsFolderIsWrittenAndMeasured() throws Exception {
        Path dir = folder.resolve("recordings");
        Files.createDirectories(dir.resolve("2026-11-14"));
        Files.write(dir.resolve("2026-11-14/a.wav"), new byte[300]);
        Files.write(dir.resolve("2026-11-14/a.json"), new byte[100]);
        Files.write(dir.resolve("notes.txt"), new byte[5000]);
        var settings = new EvidenceSettings(EvidenceSettings.Mode.FLAGGED, dir, true, 0, 0);
        var facts = SetupCheck.inspectRecordings(settings);
        assertNull(facts.error());
        assertEquals(400, facts.usedBytes());
        try (var left = Files.list(dir)) {
            assertEquals(2, left.count(), "the write probe leaves nothing behind");
        }
        assertEquals(List.of(Status.OK), check("recordings", Fakes.with(facts, "freeBytes", 50L << 30)));
        var tight = new EvidenceSettings(EvidenceSettings.Mode.FLAGGED, dir, true, 0, 100);
        assertEquals(List.of(Status.WARN), check("recordings", new SetupCheck.Recordings(tight, null, 400, 50L << 30)));
        assertEquals(List.of(Status.WARN), check("recordings", Fakes.with(facts, "freeBytes", 1024L)));
    }

    @Test
    void unwritableRecordingsFolderFails() throws Exception {
        Path notADirectory = Files.writeString(folder.resolve("file"), "x");
        var facts = SetupCheck.inspectRecordings(new EvidenceSettings(EvidenceSettings.Mode.ALL, notADirectory, true, 0, 0));
        assertNotNull(facts.error());
        assertEquals(List.of(Status.FAIL), check("recordings", facts));
    }

    @Test
    void recordingsOffIsOkWithoutTouchingDisk() {
        Path dir = folder.resolve("never");
        var facts = SetupCheck.inspectRecordings(new EvidenceSettings(EvidenceSettings.Mode.NONE, dir, true, 0, 0));
        assertFalse(Files.exists(dir));
        assertEquals(List.of(Status.OK), check("recordings", facts));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static final java.util.Map<String, List<String>> AREAS = java.util.Map.of(
            "voice", List.of("Voice chat"),
            "engine", List.of("Speech engine", "Double check", "Transcription", "Native libraries"),
            "models", List.of("Model files"),
            "database", List.of("Database"),
            "luckPerms", List.of("LuckPerms"),
            "discord", List.of("Discord"),
            "config", List.of("Config"),
            "recordings", List.of("Recordings"));

    /** Statuses of one fact's findings when every other fact is healthy. */
    private List<Status> check(String field, Object value) {
        return findings(field, value).stream().map(Finding::status).toList();
    }

    private List<Finding> findings(String field, Object value) {
        return SetupCheck.evaluate(Fakes.with(healthy, field, value)).stream()
                .filter(f -> AREAS.get(field).contains(f.area())).toList();
    }

    private List<Status> engine(String field, Object value) {
        return check("engine", Fakes.with(healthy.engine(), field, value));
    }

    private Status first(List<Status> statuses) {
        return statuses.getFirst();
    }

    private Status natives(SetupCheck.Engine engine) {
        return findings("engine", engine).stream().filter(f -> f.area().equals("Native libraries"))
                .findFirst().orElseThrow().status();
    }

    private List<Status> models(ModelManager.FileState file, boolean autoDownload) {
        return check("models", new SetupCheck.Models(List.of(file), autoDownload, null));
    }

    private List<Finding> discord(WebhookSettings flags) {
        return findings("discord", Fakes.with(healthy.discord(), "settings", Fakes.with(healthy.discord().settings(), "flags", flags)));
    }
}
