package dev.voicewarden.plugin.voice;

import dev.voicewarden.core.config.VoiceWardenConfig;
import dev.voicewarden.plugin.consent.ConsentView;
import dev.voicewarden.plugin.mute.NativeMuteService;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Server;
import org.bukkit.entity.Player;

public final class VoiceBackendManager implements VoicePacketGate {

    public static final String BYPASS_PERMISSION = "voicewarden.bypass";

    public enum Backend {
        SVC(SimpleVoiceChatAdapter.PLUGIN_NAME),
        PLASMOVOICE(PlasmoVoiceAdapter.PLUGIN_NAME);

        private final String pluginName;

        Backend(String pluginName) {
            this.pluginName = pluginName;
        }

        public String pluginName() {
            return pluginName;
        }
    }

    @FunctionalInterface
    public interface AdapterFactory {
        VoiceBackendAdapter create(Backend backend, VoicePacketPolicy policy, VoiceAudioSink sink);
    }

    private final NativeMuteService mutes;
    private final ConsentView consent;
    private final VoiceAudioSink sink;
    private final Predicate<String> pluginEnabled;
    private final AdapterFactory factory;
    private final Logger logger;
    private final VoicePacketPolicy policy;
    private final Map<UUID, Boolean> bypass = new ConcurrentHashMap<>();
    private final List<VoiceBackendAdapter> registered = new ArrayList<>();
    private final Map<Backend, VoiceBackendAdapter> created = new EnumMap<>(Backend.class);
    private volatile Set<String> allowedGroups;

    public VoiceBackendManager(
            NativeMuteService mutes,
            ConsentView consent,
            VoiceAudioSink sink,
            VoicePacketPolicy.Settings settings,
            Predicate<String> pluginEnabled,
            AdapterFactory factory,
            Logger logger) {
        this.mutes = Objects.requireNonNull(mutes, "mutes");
        this.consent = Objects.requireNonNull(consent, "consent");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.pluginEnabled = Objects.requireNonNull(pluginEnabled, "pluginEnabled");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.policy = new VoicePacketPolicy(this, settings);
        this.allowedGroups = normalise(settings.block().allowedGroups());
    }

    public static AdapterFactory bukkitAdapters(Server server, Logger logger) {
        return (backend, policy, sink) -> switch (backend) {
            case SVC -> SimpleVoiceChatAdapter.create(server, policy, sink);
            case PLASMOVOICE -> PlasmoVoiceAdapter.create(server, policy, sink, logger);
        };
    }

    public VoicePacketPolicy policy() {
        return policy;
    }

    public synchronized List<String> activeBackends() {
        return registered.stream().map(VoiceBackendAdapter::name).toList();
    }

    public synchronized List<String> start(VoiceWardenConfig.VoiceBackend mode) {
        stop();
        for (Backend backend : candidates(mode)) {
            if (!pluginEnabled.test(backend.pluginName())) {
                if (mode != VoiceWardenConfig.VoiceBackend.AUTO) {
                    logger.warning("voice_backend is " + mode + " but the " + backend.pluginName()
                            + " plugin is not enabled. Voice chat is not monitored or mute-enforced.");
                }
                continue;
            }
            try {
                VoiceBackendAdapter adapter = created.computeIfAbsent(backend, kind -> factory.create(kind, policy, sink));
                adapter.register();
                registered.add(adapter);
                logger.info("Hooked into " + adapter.name() + " for voice monitoring and mutes.");
            } catch (RuntimeException | LinkageError e) {
                logger.log(Level.SEVERE, "Could not hook into " + backend.pluginName() + ". Check that a supported version is installed.", e);
            }
        }
        if (registered.isEmpty() && mode == VoiceWardenConfig.VoiceBackend.AUTO) {
            logger.warning("No voice chat plugin found. Install Simple Voice Chat or PlasmoVoice so VoiceWarden can monitor and mute voice.");
        }
        return activeBackends();
    }

    public synchronized void stop() {
        for (VoiceBackendAdapter adapter : registered) {
            try {
                adapter.unregister();
            } catch (RuntimeException | LinkageError e) {
                logger.log(Level.WARNING, "Could not unhook from " + adapter.name(), e);
            }
        }
        registered.clear();
    }

    public synchronized void shutdown() {
        stop();
        for (VoiceBackendAdapter adapter : created.values()) {
            try {
                adapter.close();
            } catch (RuntimeException | LinkageError e) {
                logger.log(Level.WARNING, "Could not close the " + adapter.name() + " hook", e);
            }
        }
        created.clear();
    }

    public void updateSettings(VoicePacketPolicy.Settings settings) {
        allowedGroups = normalise(settings.block().allowedGroups());
        policy.update(settings);
    }

    public void refreshBypass(Player player) {
        bypass.put(player.getUniqueId(), player.hasPermission(BYPASS_PERMISSION));
    }

    public void refreshBypass(Collection<? extends Player> players, TaskScheduler scheduler) {
        for (Player player : players) {
            scheduler.runForEntity(player, () -> refreshBypass(player));
        }
    }

    public void forget(UUID playerId) {
        bypass.remove(playerId);
        sink.playerLeft(playerId);
    }

    @Override
    public boolean isMuted(UUID playerId) {
        return mutes.isVoiceBlocked(playerId);
    }

    @Override
    public boolean hasBypass(UUID playerId) {
        return bypass.getOrDefault(playerId, Boolean.FALSE);
    }

    @Override
    public boolean hasConsentForMonitoring(UUID playerId) {
        return consent.isMonitoringAllowed(playerId);
    }

    @Override
    public boolean isGroupAllowedWhileMuted(String groupName) {
        return allowedGroups.contains(groupName.toLowerCase(Locale.ROOT));
    }

    @Override
    public void onMutedSpeakAttempt(UUID playerId) {
        mutes.onSpeakAttempt(playerId);
    }

    @Override
    public void onUnconsentedSpeakAttempt(UUID playerId) {
        consent.onUnconsentedSpeakAttempt(playerId);
    }

    private static List<Backend> candidates(VoiceWardenConfig.VoiceBackend mode) {
        return switch (mode) {
            case AUTO -> List.of(Backend.SVC, Backend.PLASMOVOICE);
            case SVC -> List.of(Backend.SVC);
            case PLASMOVOICE -> List.of(Backend.PLASMOVOICE);
        };
    }

    private static Set<String> normalise(List<String> groups) {
        return groups.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
