package dev.voicewarden.plugin;

import dev.voicewarden.api.VoiceWardenAPI;
import dev.voicewarden.api.VoiceWardenProvider;
import dev.voicewarden.core.audio.AudioPipelineStats;
import dev.voicewarden.core.audio.ConcentusOpusAudioDecoder;
import dev.voicewarden.core.audio.RollingOpusBuffer;
import dev.voicewarden.core.audio.ShardedAudioPipeline;
import dev.voicewarden.core.config.VoiceWardenConfig;
import dev.voicewarden.core.discord.DiscordWebhookClient;
import dev.voicewarden.core.discord.DiscordWebhookConfig;
import dev.voicewarden.core.ipc.EngineClient;
import dev.voicewarden.core.ipc.EngineLogSink;
import dev.voicewarden.core.ipc.EngineProcessConfig;
import dev.voicewarden.core.ipc.EngineProcessSupervisor;
import dev.voicewarden.core.ipc.EngineResultListener;
import dev.voicewarden.core.ipc.EngineSupervisorListener;
import dev.voicewarden.core.ipc.IpcAuthToken;
import dev.voicewarden.core.ipc.IpcMessage;
import dev.voicewarden.core.moderation.ConsentStore;
import dev.voicewarden.core.moderation.ModerationEngine;
import dev.voicewarden.core.moderation.TranscriptBuffer;
import dev.voicewarden.core.storage.ResilientStorageManager;
import dev.voicewarden.core.storage.StorageConfig;
import dev.voicewarden.core.storage.StorageProvider;
import dev.voicewarden.core.wordlist.AhoCorasickMatcher;
import dev.voicewarden.core.wordlist.RuleMatcher;
import dev.voicewarden.core.wordlist.WordlistRegistry;
import dev.voicewarden.plugin.command.AdminRuntime;
import dev.voicewarden.plugin.command.ReportInboxCommand;
import dev.voicewarden.plugin.command.ReportVoiceCommand;
import dev.voicewarden.plugin.command.UpdateChecker;
import dev.voicewarden.plugin.command.ViewReportCommand;
import dev.voicewarden.plugin.command.VoiceConsentCommand;
import dev.voicewarden.plugin.command.VoiceWardenCommand;
import dev.voicewarden.plugin.consent.BedrockConsentForms;
import dev.voicewarden.plugin.consent.ConsentManager;
import dev.voicewarden.plugin.consent.ConsentMenuListener;
import dev.voicewarden.plugin.consent.JavaConsentMenu;
import dev.voicewarden.plugin.mute.BukkitEventDispatcher;
import dev.voicewarden.plugin.mute.BukkitPlayerDirectory;
import dev.voicewarden.plugin.mute.MuteMirror;
import dev.voicewarden.plugin.mute.MutePlayerListener;
import dev.voicewarden.plugin.mute.NativeMuteService;
import dev.voicewarden.plugin.mute.StrikeCountCache;
import dev.voicewarden.plugin.pipeline.ConfigBridges;
import dev.voicewarden.plugin.pipeline.EngineBinaryExtractor;
import dev.voicewarden.plugin.pipeline.ModerationQuitListener;
import dev.voicewarden.plugin.pipeline.PluginFiles;
import dev.voicewarden.plugin.pipeline.StorageStrikePersistence;
import dev.voicewarden.plugin.pipeline.VoiceModerationPipeline;
import dev.voicewarden.plugin.placeholder.PlaceholderExpansionRegistrar;
import dev.voicewarden.plugin.report.ChestReportMenus;
import dev.voicewarden.plugin.report.ReportMenuListener;
import dev.voicewarden.plugin.report.VoiceReportManager;
import dev.voicewarden.plugin.scheduler.PaperFoliaScheduler;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import dev.voicewarden.plugin.text.PlaceholderHook;
import dev.voicewarden.plugin.voice.SpeakerContextTracker;
import dev.voicewarden.plugin.voice.VoiceAudioSink;
import dev.voicewarden.plugin.voice.VoiceBackendManager;
import dev.voicewarden.plugin.voice.VoicePlayerListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class VoiceWardenPlugin extends JavaPlugin implements AdminRuntime {

    public static final Duration TRANSCRIPT_PRUNE_PERIOD = Duration.ofMinutes(1);
    public static final long BYPASS_REFRESH_TICKS = 20L * 30;
    public static final Duration OLD_WEBHOOK_CLIENT_GRACE = Duration.ofSeconds(60);

    private final Object engineLock = new Object();
    private final Object reloadLock = new Object();
    private final List<Runnable> shutdownHooks = new ArrayList<>();
    private final AtomicLong engineGeneration = new AtomicLong();

    private Path dataFolder;
    private Clock clock;
    private TaskScheduler scheduler;
    private volatile VoiceWardenConfig config;
    private StorageConfig storageConfig;
    private ResilientStorageManager storage;
    private volatile DiscordWebhookClient discord;
    private WordlistRegistry wordlist;
    private TranscriptBuffer transcripts;
    private RollingOpusBuffer recentAudio;
    private SpeakerContextTracker speakers;
    private MessageRenderer messages;
    private BukkitPlayerDirectory players;
    private StrikeCountCache strikeCounts;
    private ConsentManager consent;
    private NativeMuteService mutes;
    private volatile ModerationEngine moderation;
    private VoiceReportManager reports;
    private VoiceModerationPipeline pipeline;
    private ShardedAudioPipeline audio;
    private VoiceBackendManager voice;
    private UpdateChecker updates;
    private volatile EngineProcessSupervisor supervisor;
    private volatile String engineState = "starting";
    private volatile boolean shuttingDown;
    private Runnable placeholderUnregister = () -> { };

    @Override
    public void onEnable() {
        dataFolder = getDataFolder().toPath();
        clock = Clock.systemDefaultZone();
        scheduler = new PaperFoliaScheduler(this);
        PluginFiles.Loaded loaded;
        try {
            loaded = PluginFiles.load(dataFolder);
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not load the VoiceWarden configuration. The plugin is disabled.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loaded.notices().forEach(getLogger()::info);
        loaded.warnings().forEach(getLogger()::warning);
        config = loaded.config();
        PluginManager pluginManager = getServer().getPluginManager();

        storageConfig = ConfigBridges.storage(config, dataFolder);
        storage = ResilientStorageManager.create(storageConfig);
        CompletableFuture<Boolean> storageReady = storage.start();
        discord = new DiscordWebhookClient(discordConfig(config));
        wordlist = new WordlistRegistry(loaded.wordlist());
        transcripts = new TranscriptBuffer(Duration.ofSeconds(config.transcripts().retentionSeconds()), config.transcripts().maxLinesPerPlayer());
        recentAudio = new RollingOpusBuffer(Duration.ZERO);
        speakers = new SpeakerContextTracker(transcripts, config.transcripts(), clock);
        messages = new MessageRenderer(loaded.messages(), PlaceholderHook.detect(config.placeholderApiEnabled()));
        players = new BukkitPlayerDirectory(getServer());
        BukkitEventDispatcher events = new BukkitEventDispatcher(scheduler, pluginManager);

        ConsentStore consentStore = new ConsentStore(dataFolder.resolve(PluginFiles.CONSENT_FILE));
        try {
            consentStore.load().forEach(getLogger()::warning);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not read " + PluginFiles.CONSENT_FILE + "; everyone is asked for consent again", e);
        }
        consent = new ConsentManager(consentStore, config.consent(), scheduler, players, messages, new JavaConsentMenu(messages),
                BedrockConsentForms.detect(messages, scheduler), clock, playerId -> {
                    ShardedAudioPipeline current = audio;
                    if (current != null) {
                        current.removePlayer(playerId);
                    }
                    recentAudio.clear(playerId);
                }, getLogger());

        strikeCounts = new StrikeCountCache(
                (playerId, since) -> storage.countActiveStrikes(playerId, since, config.moderation().networkStrikes()),
                scheduler::runAsync, clock, config.moderationSettings().window(), StrikeCountCache.DEFAULT_MAX_AGE);
        mutes = new NativeMuteService(config.mutes(), storage, scheduler, players, messages, events, MuteMirror.detectLuckPerms(),
                strikeCounts, consent::isMonitoringAllowed, clock, config.serverId(), getLogger());
        mutes.start();
        storageReady.thenCompose(ignored -> mutes.loadActiveMutes()).whenComplete((count, error) -> {
            if (error != null) {
                getLogger().log(Level.WARNING, "Could not load active voice mutes from storage", error);
            } else if (count > 0) {
                getLogger().info("Loaded " + count + " active voice mutes.");
            }
        });

        moderation = buildModeration(config);
        reports = new VoiceReportManager(config.voiceReport(), transcripts, recentAudio, ConcentusOpusAudioDecoder::new, speakers, storage,
                payload -> discord.sendReport(payload), scheduler,
                players, messages, new ChestReportMenus(messages), clock, ZoneId.systemDefault(), config.serverId(), getLogger());

        pipeline = new VoiceModerationPipeline(
                VoiceModerationPipeline.Settings.from(config),
                new VoiceModerationPipeline.Services(moderation, discord),
                scheduler, players, messages, wordlist, transcripts, speakers, strikeCounts, mutes, events,
                pluginManager::callEvent, Bukkit::isPrimaryThread,
                command -> getServer().dispatchCommand(getServer().getConsoleSender(), command),
                clock, getLogger());
        audio = new ShardedAudioPipeline(config.audio(), pipeline);
        pipeline.bindAudio(audio);
        pipeline.start();

        voice = new VoiceBackendManager(mutes, consent, VoiceAudioSink.of(audio, recentAudio, speakers, clock), ConfigBridges.packetSettings(config),
                pluginManager::isPluginEnabled, VoiceBackendManager.bukkitAdapters(getServer(), getLogger()), getLogger());
        voice.start(config.voiceBackend());

        VoiceWardenProvider.register(mutes);
        getServer().getServicesManager().register(VoiceWardenAPI.class, mutes, this, ServicePriority.Normal);
        placeholderUnregister = PlaceholderExpansionRegistrar.registerIfPresent(mutes, consent, version());

        pluginManager.registerEvents(new MutePlayerListener(mutes), this);
        pluginManager.registerEvents(new VoicePlayerListener(voice), this);
        pluginManager.registerEvents(new ConsentMenuListener(consent), this);
        pluginManager.registerEvents(new ReportMenuListener(reports), this);
        pluginManager.registerEvents(new ModerationQuitListener(pipeline), this);
        registerCommands();

        scheduler.runAsyncFixedRate(() -> {
            transcripts.prune(clock.instant());
            reports.pruneAudio();
        }, TRANSCRIPT_PRUNE_PERIOD, TRANSCRIPT_PRUNE_PERIOD);
        scheduler.runGlobalFixedRate(() -> voice.refreshBypass(players.onlinePlayers(), scheduler), BYPASS_REFRESH_TICKS, BYPASS_REFRESH_TICKS);
        scheduler.runGlobalFixedRate(() -> speakers.refresh(players.onlinePlayers(), scheduler),
                SpeakerContextTracker.REFRESH_TICKS, SpeakerContextTracker.REFRESH_TICKS);

        updates = new UpdateChecker(UpdateChecker.DEFAULT_RELEASES_URI, version(), UpdateChecker.DEFAULT_TIMEOUT);
        if (config.updateCheckEnabled()) {
            updates.check().thenAccept(result -> {
                if (result.status() == UpdateChecker.Status.UPDATE_AVAILABLE) {
                    getLogger().info("VoiceWarden " + result.latest() + " is available. You are running " + result.current() + ".");
                }
            });
        }
        long initialGen = engineGeneration.incrementAndGet();
        scheduler.runAsync(() -> startEngine(config, true, initialGen));
    }

    @Override
    public void onDisable() {
        synchronized (reloadLock) {
            shuttingDown = true;
            engineGeneration.incrementAndGet();
        }
        quietly("unregister the API", () -> {
            getServer().getServicesManager().unregisterAll(this);
            VoiceWardenProvider.unregister();
        });
        quietly("unregister placeholders", placeholderUnregister);
        if (voice != null) {
            quietly("unhook voice chat", voice::shutdown);
        }
        if (audio != null) {
            quietly("stop the audio pipeline", audio::close);
        }
        if (recentAudio != null) {
            quietly("clear buffered report audio", recentAudio::clearAll);
        }
        if (pipeline != null) {
            quietly("stop the moderation pipeline", pipeline::close);
        }
        quietly("stop the speech engine", this::stopEngine);
        if (mutes != null) {
            quietly("stop mute tasks", mutes::stop);
        }
        if (updates != null) {
            quietly("stop the update checker", updates::close);
        }
        if (scheduler != null) {
            quietly("cancel scheduled tasks", scheduler::cancelAll);
        }
        synchronized (shutdownHooks) {
            shutdownHooks.forEach(hook -> quietly("close a replaced client", hook));
            shutdownHooks.clear();
        }
        if (discord != null) {
            quietly("close the Discord client", discord::close);
        }
        if (storage != null) {
            quietly("close storage", storage::close);
        }
    }

    @Override
    public ReloadResult reload() {
        synchronized (reloadLock) {
            if (shuttingDown) {
                return ReloadResult.failed("VoiceWarden is shutting down.");
            }
            PluginFiles.Loaded loaded;
            DiscordWebhookConfig nextDiscord;
            StorageConfig nextStorage;
            VoiceModerationPipeline.Settings nextSettings;
            try {
                loaded = PluginFiles.load(dataFolder);
                Optional<String> fatal = loaded.unreadableFile();
                if (fatal.isPresent()) {
                    return ReloadResult.failed(fatal.get());
                }
                nextDiscord = discordConfig(loaded.config());
                nextStorage = ConfigBridges.storage(loaded.config(), dataFolder);
                nextSettings = VoiceModerationPipeline.Settings.from(loaded.config());
            } catch (IOException | RuntimeException e) {
                getLogger().log(Level.WARNING, "Reload failed; keeping the previous settings", e);
                return ReloadResult.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            if (shuttingDown) {
                return ReloadResult.failed("VoiceWarden is shutting down.");
            }
            VoiceWardenConfig previous = config;
            VoiceWardenConfig next = loaded.config();
            List<String> notes = new ArrayList<>(loaded.warnings());
            loaded.notices().forEach(getLogger()::info);
            loaded.warnings().forEach(getLogger()::warning);

            messages.update(loaded.messages(), PlaceholderHook.detect(next.placeholderApiEnabled()));
            wordlist.reload(loaded.wordlist());
            if (!nextDiscord.equals(discord.config())) {
                DiscordWebhookClient old = discord;
                discord = new DiscordWebhookClient(nextDiscord);
                retireLater(old::close);
            }
            if (moderationChanged(previous, next)) {
                moderation = buildModeration(next);
            }
            config = next;
            pipeline.update(nextSettings, new VoiceModerationPipeline.Services(moderation, discord));
            audio.updateConfig(next.audio());
            mutes.updateConfig(next.mutes());
            strikeCounts.setWindow(next.moderationSettings().window());
            consent.onConfigReload(next.consent(), players.onlinePlayers());
            reports.updateConfig(next.voiceReport());
            speakers.updateConfig(next.transcripts());
            voice.updateSettings(ConfigBridges.packetSettings(next));
            if (previous.voiceBackend() != next.voiceBackend()) {
                scheduler.runGlobal(() -> voice.start(next.voiceBackend()));
            }
            voice.refreshBypass(players.onlinePlayers(), scheduler);

            if (!nextStorage.equals(storageConfig)) {
                notes.add("Storage settings changed. Restart the server to connect with them.");
            }
            if (!previous.transcripts().sameRetention(next.transcripts())) {
                notes.add("Transcript retention changed. Restart the server to apply it.");
            }
            if (!previous.serverId().equals(next.serverId())) {
                notes.add("server_id changed. Restart the server so mutes and reports use the new id.");
            }
            if (ConfigBridges.engineRestartNeeded(previous.engine(), next.engine()) || supervisor == null) {
                notes.add("Restarting the speech engine with the new settings.");
                long gen = engineGeneration.incrementAndGet();
                scheduler.runAsync(() -> startEngine(next, false, gen));
            }
            return ReloadResult.ok(notes);
        }
    }

    @Override
    public NativeMuteService mutes() {
        return mutes;
    }

    @Override
    public ModerationEngine moderation() {
        return moderation;
    }

    @Override
    public DiscordWebhookClient discord() {
        return discord;
    }

    @Override
    public StorageProvider storage() {
        return storage;
    }

    @Override
    public StorageStatus storageStatus() {
        return new StorageStatus(storage.description(), storage.isAvailable(), storage.pendingWriteCount(), storage.lastFailure());
    }

    @Override
    public VoiceModerationPipeline pipeline() {
        return pipeline;
    }

    @Override
    public Optional<AudioPipelineStats> audioStats() {
        return audio == null ? Optional.empty() : Optional.of(audio.stats());
    }

    @Override
    public String engineState() {
        EngineProcessSupervisor current = supervisor;
        if (current == null) {
            return engineState;
        }
        String pid = current.pid().isPresent() ? ", pid " + current.pid().getAsLong() : "";
        return current.state().name().toLowerCase(Locale.ROOT) + pid + ", " + current.restartCount() + " restarts";
    }

    @Override
    public List<String> voiceBackends() {
        return voice.activeBackends();
    }

    @Override
    public AhoCorasickMatcher wordlist() {
        return wordlist.current();
    }

    @Override
    public RuleMatcher rules() {
        return wordlist.matcher();
    }

    @Override
    public Duration defaultMuteDuration() {
        return config.muteLadder().fallbackDuration();
    }

    @Override
    public UpdateChecker updates() {
        return updates;
    }

    @Override
    public ConsentManager consent() {
        return consent;
    }

    private DiscordWebhookConfig discordConfig(VoiceWardenConfig source) {
        return ConfigBridges.discord(source, source.serverId());
    }

    private ModerationEngine buildModeration(VoiceWardenConfig source) {
        return ConfigBridges.moderation(source,
                new StorageStrikePersistence(storage, source.serverId(), source.moderation().networkStrikes()));
    }

    private static boolean moderationChanged(VoiceWardenConfig previous, VoiceWardenConfig next) {
        return !previous.moderationSettings().equals(next.moderationSettings())
                || !previous.muteLadder().equals(next.muteLadder())
                || !previous.commands().equals(next.commands())
                || previous.moderation().networkStrikes() != next.moderation().networkStrikes()
                || !previous.serverId().equals(next.serverId());
    }

    private void startEngine(VoiceWardenConfig source, boolean extract, long generation) {
        synchronized (engineLock) {
            if (shuttingDown || engineGeneration.get() != generation) {
                return;
            }
            stopEngine();
            Path jar = EngineBinaryExtractor.target(dataFolder);
            try {
                if (extract || !Files.isRegularFile(jar)) {
                    EngineBinaryExtractor.Result result = EngineBinaryExtractor.extract(
                            () -> VoiceWardenPlugin.class.getResourceAsStream(EngineBinaryExtractor.RESOURCE), jar);
                    if (result == EngineBinaryExtractor.Result.EXTRACTED) {
                        getLogger().info("Installed the speech engine at " + dataFolder.relativize(jar) + ".");
                    }
                }
                if (!Files.isRegularFile(jar)) {
                    engineState = "not installed";
                    getLogger().severe("The speech engine is missing from this VoiceWarden jar. Voice is not transcribed.");
                    return;
                }
                if (shuttingDown || engineGeneration.get() != generation) {
                    return;
                }
                Files.createDirectories(ConfigBridges.modelsDirectory(source.engine(), dataFolder));
                EngineProcessConfig processConfig = ConfigBridges.engineProcess(source, dataFolder, jar, ConfigBridges.engineEndpoint(dataFolder));
                EngineProcessSupervisor next = new EngineProcessSupervisor(
                        processConfig,
                        IpcAuthToken.generate(),
                        generationResultListener(generation),
                        generationSupervisorListener(generation),
                        engineLogs());
                next.start();
                supervisor = next;
                getLogger().info("Starting the speech engine (" + source.engine().profile() + ", " + source.engine().threads() + " threads).");
            } catch (IOException | RuntimeException e) {
                engineState = "failed to start: " + e.getMessage();
                getLogger().log(Level.SEVERE, "Could not start the speech engine. Voice is not transcribed.", e);
            }
        }
    }

    private EngineResultListener generationResultListener(long generation) {
        return new EngineResultListener() {
            @Override
            public void onTranscript(IpcMessage.TranscriptResult result) {
                if (!shuttingDown && engineGeneration.get() == generation) {
                    pipeline.onTranscript(result);
                }
            }

            @Override
            public void onDisconnected(Throwable cause) {
                if (!shuttingDown && engineGeneration.get() == generation) {
                    pipeline.onDisconnected(cause);
                }
            }
        };
    }

    private EngineSupervisorListener generationSupervisorListener(long generation) {
        return new EngineSupervisorListener() {
            @Override
            public void onEngineReady(EngineClient engineClient, long pid) {
                if (!shuttingDown && engineGeneration.get() == generation) {
                    pipeline.onEngineReady(engineClient, pid);
                }
            }

            @Override
            public void onEngineLost(long pid, String reason) {
                if (!shuttingDown && engineGeneration.get() == generation) {
                    pipeline.onEngineLost(pid, reason);
                }
            }

            @Override
            public void onRestartScheduled(int restartsInWindow, Duration delay) {
                if (!shuttingDown && engineGeneration.get() == generation) {
                    pipeline.onRestartScheduled(restartsInWindow, delay);
                }
            }

            @Override
            public void onGaveUp(String reason) {
                if (!shuttingDown && engineGeneration.get() == generation) {
                    pipeline.onGaveUp(reason);
                }
            }
        };
    }

    private void stopEngine() {
        synchronized (engineLock) {
            EngineProcessSupervisor current = supervisor;
            supervisor = null;
            if (current != null) {
                engineState = "stopped";
                current.close();
            }
        }
    }

    private EngineLogSink engineLogs() {
        return (stream, pid, line) -> {
            if (line.contains(" ERROR ") || line.contains(" WARN ")) {
                getLogger().warning("[engine] " + line);
            } else if (stream == EngineLogSink.Stream.STDERR && !line.contains(" INFO ") && !line.contains(" DEBUG ")) {
                getLogger().warning("[engine] " + line);
            } else {
                getLogger().info("[engine] " + line);
            }
        };
    }

    private void retireLater(Runnable close) {
        synchronized (shutdownHooks) {
            shutdownHooks.add(close);
        }
        scheduler.runAsyncDelayed(() -> {
            boolean pending;
            synchronized (shutdownHooks) {
                pending = shutdownHooks.remove(close);
            }
            if (pending) {
                close.run();
            }
        }, OLD_WEBHOOK_CLIENT_GRACE);
    }

    private void registerCommands() {
        VoiceWardenCommand admin = new VoiceWardenCommand(this, players, messages, scheduler, clock, getLogger());
        command("voicewarden", admin, admin);
        VoiceConsentCommand consentCommand = new VoiceConsentCommand(consent, messages, scheduler);
        command("voiceconsent", consentCommand, consentCommand);
        ReportVoiceCommand report = new ReportVoiceCommand(reports, mutes::resolveTarget, players, messages, scheduler);
        command("reportvoice", report, report);
        ViewReportCommand view = new ViewReportCommand(reports, mutes::resolveTarget, players, messages, scheduler);
        command("viewreport", view, view);
        ReportInboxCommand inbox = new ReportInboxCommand(reports, messages, scheduler);
        command("reportinbox", inbox, inbox);
    }

    private void command(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), () -> name + " is missing from plugin.yml");
        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }

    private String version() {
        return getPluginMeta().getVersion();
    }

    private void quietly(String action, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.WARNING, "Could not " + action + " while disabling", e);
        }
    }
}
