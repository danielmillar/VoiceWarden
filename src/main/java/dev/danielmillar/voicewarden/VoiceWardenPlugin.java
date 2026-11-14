package dev.danielmillar.voicewarden;

import dev.danielmillar.voicewarden.command.CommandRegistrar;
import dev.danielmillar.voicewarden.concurrent.PluginExecutors;
import dev.danielmillar.voicewarden.config.ConfigBundle;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.config.TurboModelActivation;
import dev.danielmillar.voicewarden.evidence.EvidenceStore;
import dev.danielmillar.voicewarden.health.HealthMonitor;
import dev.danielmillar.voicewarden.health.Metrics;
import dev.danielmillar.voicewarden.health.SetupCheck;
import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.moderation.CustomCommandRunner;
import dev.danielmillar.voicewarden.moderation.ModerationService;
import dev.danielmillar.voicewarden.moderation.OffenseTracker;
import dev.danielmillar.voicewarden.moderation.StaffModerationService;
import dev.danielmillar.voicewarden.moderation.TranscriptBuffer;
import dev.danielmillar.voicewarden.moderation.TranscriptLog;
import dev.danielmillar.voicewarden.moderation.rules.RuleEngine;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.mute.VoiceMuteMirror;
import dev.danielmillar.voicewarden.notify.StaffAlertService;
import dev.danielmillar.voicewarden.notify.discord.DiscordWebhookService;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.player.PlayerListener;
import dev.danielmillar.voicewarden.report.ReportService;
import dev.danielmillar.voicewarden.report.ReportStore;
import dev.danielmillar.voicewarden.storage.Database;
import dev.danielmillar.voicewarden.stt.Downloader;
import dev.danielmillar.voicewarden.stt.ModelManager;
import dev.danielmillar.voicewarden.stt.NativeLibraries;
import dev.danielmillar.voicewarden.stt.TranscriptionService;
import dev.danielmillar.voicewarden.voice.AudioIngestService;
import dev.danielmillar.voicewarden.voice.VoiceChatBridge;
import dev.danielmillar.voicewarden.voice.VoicePermissionMessageHook;
import dev.danielmillar.voicewarden.voice.VoiceSpeechNotice;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * VoiceWarden: real-time, fully local voice chat moderation for Simple Voice Chat.
 *
 * <p>Threading contract: after {@link #onEnable()} returns, the server thread only ever runs O(1) bookkeeping
 * (join/quit), command parsing, and the console commands an admin configured. Audio, transcription, moderation, IO,
 * LuckPerms writes and webhooks all run on VoiceWarden's own threads (see {@link PluginExecutors}).
 */
public final class VoiceWardenPlugin extends JavaPlugin {

    private static final List<String> DEFAULT_FILES = List.of("config.yml", "messages.yml", "rules.yml", "wordlist.txt");

    private volatile PluginConfig config;
    private volatile Messages messages;
    private volatile RuleEngine rules;
    private volatile List<String> configWarnings = List.of();

    private PluginExecutors executors;
    private Metrics metrics;
    private HttpClient http;
    private @Nullable LuckPermsHook luckPerms;
    private @Nullable Database database;
    private OnlinePlayers players;
    private TranscriptBuffer transcriptBuffer;
    private MuteService mutes;
    private StaffAlertService alerts;
    private DiscordWebhookService discord;
    private EvidenceStore evidence;
    private TranscriptLog transcriptLog;
    private ModerationService moderation;
    private StaffModerationService staffModeration;
    private TranscriptionService transcription;
    private AudioIngestService ingest;
    private @Nullable VoiceChatBridge bridge;
    private @Nullable VoicePermissionMessageHook permissionMessageHook;
    private VoiceSpeechNotice speechNotice;
    private ReportService reports;
    private ReportStore reportStore;
    private HealthMonitor health;
    private volatile boolean started;

    @Override
    public void onEnable() {
        Path dataFolder = getDataFolder().toPath();
        for (String name : DEFAULT_FILES) {
            if (!new File(getDataFolder(), name).exists()) {
                saveResource(name, false);
            }
        }
        try {
            TurboModelActivation.apply(dataFolder).ifPresent(backup ->
                    getLogger().info("Activated Whisper Turbo and quiet-speech gain; previous config saved to " + backup));
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not complete startup Turbo activation; loading current config.yml", e);
        }
        ConfigBundle bundle;
        try {
            bundle = ConfigBundle.load(dataFolder);
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not load configuration; VoiceWarden is disabled", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        bundle.warnings().forEach(w -> getLogger().warning(w));
        configWarnings = bundle.warnings();
        config = bundle.config();
        messages = bundle.messages();
        rules = bundle.rules();

        BukkitVoicechatService voicechat = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (voicechat == null) {
            getLogger().severe("Simple Voice Chat is required; VoiceWarden is disabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            database = Database.open(dataFolder.resolve("data").resolve("voicewarden.db"), getLogger());
        } catch (IOException | SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not open the VoiceWarden database; VoiceWarden is disabled", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // LuckPerms is optional. Its classes may be missing, so LuckPermsHook is only touched when the plugin is present.
        luckPerms = getServer().getPluginManager().getPlugin("LuckPerms") != null ? LuckPermsHook.connect(getServer()) : null;
        if (luckPerms == null && config.mute().luckPerms()) {
            getLogger().info("LuckPerms is not installed, so mute.luckperms is ignored. Voice mutes are still enforced"
                    + " by VoiceWarden on this server.");
        }

        int decodeThreads = config.audio().decodeThreads() > 0 ? config.audio().decodeThreads() : PluginExecutors.autoDecodeThreads();
        executors = new PluginExecutors(decodeThreads, getLogger());
        metrics = new Metrics();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        players = new OnlinePlayers();
        getServer().getOnlinePlayers().forEach(players::add);

        transcriptBuffer = new TranscriptBuffer(bufferLimits(config));
        mutes = new MuteService(this::config, this::messages, luckPerms != null ? luckPerms : VoiceMuteMirror.NONE, players,
                executors.io(), getLogger(), database);
        alerts = new StaffAlertService(this::config, this::messages, players, getLogger());
        discord = new DiscordWebhookService(() -> config.discord(), http, getLogger());
        staffModeration = new StaffModerationService(mutes, this::isBypassed, id -> moderation.forgive(id), action -> {
            alerts.staffAction(action);
            discord.submitStaffAction(action);
        });
        evidence = new EvidenceStore(() -> config.recordings(),
                PluginExecutors.bounded(executors.io(), 64, getLogger(), "Evidence storage"), getLogger());
        transcriptLog = new TranscriptLog(dataFolder.resolve("logs"), executors.io(), getLogger());
        OffenseTracker offenses = new OffenseTracker(database, getLogger());
        moderation = new ModerationService(this::config, this::messages, this::rules, transcriptBuffer,
                offenses, mutes, alerts, discord, evidence,
                new CustomCommandRunner(this, getLogger()), transcriptLog, players, metrics, getLogger(),
                segment -> transcription.doubleCheck(segment));
        reportStore = new ReportStore(database, getLogger());
        reports = new ReportService(this::config, transcriptBuffer, reportStore, alerts, discord, evidence, executors.io(), getLogger());

        ingest = new AudioIngestService(this::config, metrics, executors.decode(), segment -> transcription.submit(segment),
                players::get, player -> player.hasPermission(AudioIngestService.BYPASS_PERMISSION), getLogger());
        transcription = new TranscriptionService(dataFolder, new Downloader(http, getLogger()), executors.io(), this::config,
                metrics, getLogger(), moderation::onTranscript, ready -> ingest.setAccepting(ready && config.enabled()));
        health = new HealthMonitor(this::config, metrics, transcription, getLogger());

        mutes.onExpiry(action -> discord.submitStaffAction(action));
        speechNotice = new VoiceSpeechNotice(this, this::config, this::messages, mutes, System::currentTimeMillis, getLogger());
        mutes.onMute(speechNotice::show);
        permissionMessageHook = VoicePermissionMessageHook.install(voicechat.getClass().getClassLoader(),
                speechNotice::onDeniedSpeech, getLogger());
        bridge = new VoiceChatBridge(ingest, mutes, () -> config.mute(), speechNotice::onDeniedSpeech);
        // Must happen in onEnable: Simple Voice Chat reads registered plugins once, when the server finishes loading.
        voicechat.registerPlugin(bridge);

        getServer().getPluginManager().registerEvents(new PlayerListener(players, this::config, this::messages,
                speechNotice::show, speechNotice::onRespawn, id -> {
            speechNotice.onDisconnect(id);
            ingest.onDisconnect(id);
        }), this);
        if (luckPerms != null) {
            luckPerms.onUserRecalculate(this, id -> ingest.refreshBypass(id));
        }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> CommandRegistrar.register(event.registrar(), this));

        reportStore.load();
        offenses.load();
        mutes.start(executors.timer());
        players.all().forEach(speechNotice::show);
        ingest.start(executors.timer());
        transcriptLog.start(executors.timer());
        health.start(executors.timer());
        discord.start();
        executors.timer().scheduleWithFixedDelay(() -> executors.io().execute(moderation::housekeeping), 1, 1, TimeUnit.MINUTES);
        executors.timer().scheduleWithFixedDelay(() -> evidence.cleanup().exceptionally(e -> null), 5, 60, TimeUnit.MINUTES);
        started = true;

        if (config.enabled()) {
            transcription.start(config.speechToText());
            getLogger().info("Preparing the speech engine in the background (" + bundle.summary() + ")...");
        } else {
            getLogger().warning("VoiceWarden is disabled in config.yml (enabled: false); mutes are still enforced");
        }
    }

    @Override
    public void onDisable() {
        if (permissionMessageHook != null) {
            permissionMessageHook.close();
            permissionMessageHook = null;
        }
        if (speechNotice != null) {
            speechNotice.close();
        }
        if (!started) {
            if (database != null) {
                database.close();
                database = null;
            }
            return;
        }
        if (bridge != null) {
            bridge.deactivate();
        }
        ingest.close();
        started = false;
        staffModeration.close();
        CompletableFuture<Void> workers = transcription.closeAsync();
        health.close();
        discord.close();
        if (luckPerms != null) {
            luckPerms.close();
        }
        http.shutdownNow();
        executors.shutdownAfter(workers, () -> {
            CompletableFuture.allOf(mutes.flushMirrors(), luckPerms != null ? luckPerms.flush() : CompletableFuture.completedFuture(null)).handle((ignored, error) -> {
                if (error != null) {
                    getLogger().log(Level.WARNING, "Could not update the LuckPerms mirror during shutdown", error);
                }
                return null;
            }).join();
            mutes.close();
            database.close();
            transcriptLog.close();
        });
    }

    /** Result of {@link #reload()}. */
    public record ReloadResult(long millis, String summary, List<String> warnings) {
    }

    /** Re-reads every file off the main thread, swaps the snapshot, and rebuilds the engine only if its settings changed. */
    public CompletableFuture<ReloadResult> reload() {
        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.nanoTime();
            ConfigBundle bundle;
            try {
                bundle = ConfigBundle.load(getDataFolder().toPath());
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!started) {
                throw new IllegalStateException("VoiceWarden is stopping");
            }
            PluginConfig previous = config;
            config = bundle.config();
            messages = bundle.messages();
            rules = bundle.rules();
            transcriptBuffer.setLimits(bufferLimits(config));
            if (!config.mute().equals(previous.mute())) {
                mutes.reconcileMirrors();
                players.all().forEach(speechNotice::show);
            }
            health.start(executors.timer());

            String engineNote = "";
            boolean engineDown = transcription.state() == TranscriptionService.State.FAILED
                    || transcription.state() == TranscriptionService.State.STOPPED;
            if (config.enabled() && (engineDown || !config.speechToText().equals(previous.speechToText()))) {
                transcription.start(config.speechToText());
                engineNote = engineDown ? ", starting speech engine" : ", reloading speech engine in the background";
            }
            ingest.setAccepting(config.enabled() && transcription.state() == TranscriptionService.State.READY);
            if (!config.enabled()) {
                transcription.discardQueued();
            }
            if (config.audio().decodeThreads() != previous.audio().decodeThreads()) {
                engineNote += " (audio.decode-threads applies after a restart)";
            }
            bundle.warnings().forEach(w -> getLogger().warning(w));
            configWarnings = bundle.warnings();
            return new ReloadResult(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0), bundle.summary() + engineNote,
                    bundle.warnings());
        }, executors.io());
    }

    /**
     * Facts for {@code /vw check}. Cheap state is read here; model sizes, the recordings folder and a database read
     * run on IO threads, so this never blocks the caller.
     */
    public CompletableFuture<List<SetupCheck.Finding>> checkSetup() {
        PluginConfig c = config;
        var voicechat = getServer().getPluginManager().getPlugin("voicechat");
        var voice = new SetupCheck.Voice(voicechat == null ? "" : voicechat.getPluginMeta().getVersion(),
                bridge != null && bridge.initialized(), permissionMessageHook != null);
        var lp = new SetupCheck.LuckPerms(getServer().getPluginManager().getPlugin("LuckPerms") != null,
                luckPerms != null, c.mute().luckPerms());
        var discordFacts = new SetupCheck.Discord(c.discord(), discord.sentCount(), discord.failedCount(), discord.droppedCount());
        var configFacts = new SetupCheck.Config(c.moderation().dryRun(), rules.ruleCount(), configWarnings);
        CompletableFuture<SetupCheck.Store> store = SetupCheck.readDatabase(database, Duration.ofSeconds(5));
        Path dataFolder = getDataFolder().toPath();
        return CompletableFuture.supplyAsync(() -> {
            var stt = c.speechToText();
            String platform;
            boolean natives;
            try {
                platform = NativeLibraries.platform();
                natives = NativeLibraries.present(dataFolder, stt.nativeLibraryPath());
            } catch (IllegalStateException e) {
                platform = e.getMessage();
                natives = false;
            }
            long[] latency = metrics.latencySummary();
            var engine = new SetupCheck.Engine(c.enabled(), transcription.state(), transcription.stateDetail(), stt.model(),
                    transcription.lastFailure(), stt.doubleCheck(), transcription.doubleCheckLoaded(),
                    transcription.workers(), transcription.aliveWorkers(), transcription.queueSize(), stt.queueMaxSize(),
                    metrics.queueDropped.sum() + metrics.staleDropped.sum() + metrics.packetsDropped.sum(),
                    metrics.transcriptionErrors.sum(), latency[1], metrics.speedFactor(),
                    natives, NativeLibraries.VERSION, platform, stt.autoDownload());
            TranscriptionService.Loaded loaded = transcription.loaded();
            var models = new SetupCheck.Models(ModelManager.inspect(stt, dataFolder), stt.autoDownload(),
                    loaded != null && loaded.settings().equals(stt) ? loaded.at() : null);
            var recordings = SetupCheck.inspectRecordings(c.recordings());
            return new DiskFacts(engine, models, recordings);
        }, executors.io()).thenCombine(store, (disk, db) -> SetupCheck.evaluate(new SetupCheck.Facts(voice,
                disk.engine(), disk.models(), db, lp, discordFacts, configFacts, disk.recordings())));
    }

    private record DiskFacts(SetupCheck.Engine engine, SetupCheck.Models models, SetupCheck.Recordings recordings) {
    }

    private static TranscriptBuffer.Limits bufferLimits(PluginConfig config) {
        PluginConfig.TranscriptBuffer b = config.transcriptBuffer();
        int contextLines = Math.max(1, config.moderation().contextLines());
        return b.enabled()
                ? new TranscriptBuffer.Limits(b.retention(), Math.max(b.maxLinesPerPlayer(), contextLines), b.audioMemoryBytes())
                : new TranscriptBuffer.Limits(Duration.ofHours(1), contextLines, 0);
    }

    // ── accessors for commands ───────────────────────────────────────────────

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public RuleEngine rules() {
        return rules;
    }

    public Metrics metrics() {
        return metrics;
    }

    public OnlinePlayers players() {
        return players;
    }

    /** Null when LuckPerms is not installed. */
    public @Nullable LuckPermsHook luckPerms() {
        return luckPerms;
    }

    public MuteService mutes() {
        return mutes;
    }

    public StaffAlertService alerts() {
        return alerts;
    }

    public DiscordWebhookService discord() {
        return discord;
    }

    public TranscriptBuffer transcriptBuffer() {
        return transcriptBuffer;
    }

    public ModerationService moderation() {
        return moderation;
    }

    public TranscriptionService transcription() {
        return transcription;
    }

    public AudioIngestService ingest() {
        return ingest;
    }

    public ReportService reports() {
        return reports;
    }

    public PluginExecutors executors() {
        return executors;
    }

    public StaffModerationService staffModeration() {
        return staffModeration;
    }

    public CompletableFuture<Boolean> isBypassed(UUID id) {
        Player online = players.get(id);
        if (online != null) {
            return CompletableFuture.completedFuture(online.hasPermission(AudioIngestService.BYPASS_PERMISSION));
        }
        // Bukkit cannot check an offline player's permissions; without LuckPerms they are treated as not bypassed.
        return luckPerms != null ? luckPerms.hasPermission(id, AudioIngestService.BYPASS_PERMISSION)
                : CompletableFuture.completedFuture(false);
    }
}
