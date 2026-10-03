package dev.danielmillar.voicesentinel;

import dev.danielmillar.voicesentinel.command.CommandRegistrar;
import dev.danielmillar.voicesentinel.concurrent.PluginExecutors;
import dev.danielmillar.voicesentinel.config.ConfigBundle;
import dev.danielmillar.voicesentinel.config.LegacyDataMigration;
import dev.danielmillar.voicesentinel.config.Messages;
import dev.danielmillar.voicesentinel.config.PluginConfig;
import dev.danielmillar.voicesentinel.config.TurboModelActivation;
import dev.danielmillar.voicesentinel.evidence.EvidenceStore;
import dev.danielmillar.voicesentinel.health.HealthMonitor;
import dev.danielmillar.voicesentinel.health.Metrics;
import dev.danielmillar.voicesentinel.luckperms.LuckPermsHook;
import dev.danielmillar.voicesentinel.moderation.CustomCommandRunner;
import dev.danielmillar.voicesentinel.moderation.ModerationService;
import dev.danielmillar.voicesentinel.moderation.OffenseTracker;
import dev.danielmillar.voicesentinel.moderation.TranscriptBuffer;
import dev.danielmillar.voicesentinel.moderation.TranscriptLog;
import dev.danielmillar.voicesentinel.moderation.rules.RuleEngine;
import dev.danielmillar.voicesentinel.mute.MuteService;
import dev.danielmillar.voicesentinel.notify.StaffAlertService;
import dev.danielmillar.voicesentinel.notify.discord.DiscordWebhookService;
import dev.danielmillar.voicesentinel.player.OnlinePlayers;
import dev.danielmillar.voicesentinel.player.PlayerListener;
import dev.danielmillar.voicesentinel.report.ReportService;
import dev.danielmillar.voicesentinel.report.ReportStore;
import dev.danielmillar.voicesentinel.stt.Downloader;
import dev.danielmillar.voicesentinel.stt.TranscriptionService;
import dev.danielmillar.voicesentinel.voice.AudioIngestService;
import dev.danielmillar.voicesentinel.voice.VoiceChatBridge;
import dev.danielmillar.voicesentinel.voice.VoicePermissionMessageHook;
import dev.danielmillar.voicesentinel.voice.VoiceSpeechNotice;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.luckperms.api.LuckPerms;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * NevusVoice: real-time, fully local voice chat moderation for Simple Voice Chat.
 *
 * <p>Threading contract: after {@link #onEnable()} returns, the server thread only ever runs O(1) bookkeeping
 * (join/quit), command parsing, and the console commands an admin configured. Audio, transcription, moderation, IO,
 * LuckPerms writes and webhooks all run on NevusVoice's own threads (see {@link PluginExecutors}).
 */
public final class VoiceSentinelPlugin extends JavaPlugin {

    private static final List<String> DEFAULT_FILES = List.of("config.yml", "messages.yml", "rules.yml", "wordlist.txt");

    private volatile PluginConfig config;
    private volatile Messages messages;
    private volatile RuleEngine rules;

    private PluginExecutors executors;
    private Metrics metrics;
    private HttpClient http;
    private LuckPermsHook luckPerms;
    private OnlinePlayers players;
    private TranscriptBuffer transcriptBuffer;
    private MuteService mutes;
    private StaffAlertService alerts;
    private DiscordWebhookService discord;
    private EvidenceStore evidence;
    private TranscriptLog transcriptLog;
    private ModerationService moderation;
    private TranscriptionService transcription;
    private AudioIngestService ingest;
    private @Nullable VoiceChatBridge bridge;
    private @Nullable VoicePermissionMessageHook permissionMessageHook;
    private VoiceSpeechNotice speechNotice;
    private ReportService reports;
    private ReportStore reportStore;
    private HealthMonitor health;
    private boolean started;

    @Override
    public void onEnable() {
        Path dataFolder = getDataFolder().toPath();
        try {
            if (LegacyDataMigration.migrate(dataFolder)) {
                getLogger().info("Migrated existing VoiceSentinel data to " + dataFolder);
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not migrate existing plugin data; NevusVoice is disabled", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
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
            getLogger().log(Level.SEVERE, "Could not load configuration; NevusVoice is disabled", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        bundle.warnings().forEach(w -> getLogger().warning(w));
        config = bundle.config();
        messages = bundle.messages();
        rules = bundle.rules();

        LuckPerms luckPermsApi = getServer().getServicesManager().load(LuckPerms.class);
        BukkitVoicechatService voicechat = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (luckPermsApi == null || voicechat == null) {
            getLogger().severe("LuckPerms and Simple Voice Chat are required; NevusVoice is disabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        int decodeThreads = config.audio().decodeThreads() > 0 ? config.audio().decodeThreads() : PluginExecutors.autoDecodeThreads();
        executors = new PluginExecutors(decodeThreads, getLogger());
        metrics = new Metrics();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        luckPerms = new LuckPermsHook(luckPermsApi);
        players = new OnlinePlayers();
        getServer().getOnlinePlayers().forEach(players::add);

        transcriptBuffer = new TranscriptBuffer(bufferLimits(config));
        mutes = new MuteService(this::config, this::messages, luckPerms, players, executors.io(), getLogger(), dataFolder);
        alerts = new StaffAlertService(this::config, this::messages, players, getLogger());
        discord = new DiscordWebhookService(() -> config.discord(), http, getLogger());
        evidence = new EvidenceStore(() -> config.recordings(),
                PluginExecutors.bounded(executors.io(), 64, getLogger(), "Evidence storage"), getLogger());
        transcriptLog = new TranscriptLog(dataFolder.resolve("logs"), executors.io(), getLogger());
        moderation = new ModerationService(this::config, this::messages, this::rules, transcriptBuffer,
                new OffenseTracker(luckPerms, getLogger()), mutes, alerts, discord, evidence,
                new CustomCommandRunner(this, getLogger()), transcriptLog, players, metrics, getLogger());
        reportStore = new ReportStore(dataFolder, executors.io(), getLogger());
        reports = new ReportService(this::config, transcriptBuffer, reportStore, alerts, discord, evidence, executors.io(), getLogger());

        ingest = new AudioIngestService(this::config, metrics, executors.decode(), segment -> transcription.submit(segment),
                players::get, player -> player.hasPermission(AudioIngestService.BYPASS_PERMISSION), getLogger());
        transcription = new TranscriptionService(dataFolder, new Downloader(http, getLogger()), executors.io(), this::config,
                metrics, getLogger(), moderation::onTranscript, ready -> ingest.setAccepting(ready && config.enabled()));
        health = new HealthMonitor(this::config, metrics, transcription, getLogger());

        mutes.onExpiry(action -> discord.submitStaffAction(action));
        speechNotice = new VoiceSpeechNotice(this, this::config, this::messages, mutes, getLogger());
        permissionMessageHook = VoicePermissionMessageHook.install(voicechat.getClass().getClassLoader(),
                speechNotice::onDeniedSpeech, getLogger());
        bridge = new VoiceChatBridge(ingest, mutes, () -> config.mute().disableListening(), speechNotice::onDeniedSpeech);
        // Must happen in onEnable: Simple Voice Chat reads registered plugins once, when the server finishes loading.
        voicechat.registerPlugin(bridge);

        getServer().getPluginManager().registerEvents(new PlayerListener(players, this::config, this::messages, id -> {
            speechNotice.onDisconnect(id);
            ingest.onDisconnect(id);
            moderation.onQuit(id);
        }), this);
        luckPerms.onUserRecalculate(this, id -> ingest.refreshBypass(id));
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> CommandRegistrar.register(event.registrar(), this));

        reportStore.load();
        mutes.start(executors.timer());
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
            getLogger().warning("NevusVoice is disabled in config.yml (enabled: false); mutes are still enforced");
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
            return;
        }
        // Order: stop intake → retire engine → stop notifications → persist state → stop threads.
        // Nothing here waits on native code or the network: the engine is released by a reaper thread once its
        // workers exit, Discord/HTTP are cancelled, and the only synchronous IO is writing the small state files when
        // they changed since their last (debounced) save. Executors get a 250 ms grace period, then are interrupted.
        if (bridge != null) {
            bridge.deactivate();
        }
        ingest.close();
        transcription.close();
        health.close();
        discord.close();
        mutes.close();
        reportStore.saveNow();
        transcriptLog.close();
        luckPerms.close();
        executors.shutdown(250);
        http.shutdownNow();
        started = false;
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
            PluginConfig previous = config;
            config = bundle.config();
            messages = bundle.messages();
            rules = bundle.rules();
            transcriptBuffer.setLimits(bufferLimits(config));
            health.start(executors.timer());

            String engineNote = "";
            boolean engineDown = transcription.state() == TranscriptionService.State.FAILED
                    || transcription.state() == TranscriptionService.State.STOPPED;
            if (config.enabled() && (engineDown || !config.speechToText().equals(previous.speechToText()))) {
                transcription.start(config.speechToText());
                engineNote = engineDown ? ", starting speech engine" : ", reloading speech engine in the background";
            }
            ingest.setAccepting(config.enabled() && transcription.state() == TranscriptionService.State.READY);
            if (config.audio().decodeThreads() != previous.audio().decodeThreads()) {
                engineNote += " (audio.decode-threads applies after a restart)";
            }
            bundle.warnings().forEach(w -> getLogger().warning(w));
            return new ReloadResult(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0), bundle.summary() + engineNote,
                    bundle.warnings());
        }, executors.io());
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

    public LuckPermsHook luckPerms() {
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

    public boolean isBypassed(@Nullable Player player) {
        return player != null && player.hasPermission(AudioIngestService.BYPASS_PERMISSION);
    }
}
