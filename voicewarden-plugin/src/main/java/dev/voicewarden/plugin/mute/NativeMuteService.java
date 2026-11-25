package dev.voicewarden.plugin.mute;

import dev.voicewarden.api.VoiceMuteRecord;
import dev.voicewarden.api.VoiceWardenAPI;
import dev.voicewarden.api.event.VoiceMuteEvent;
import dev.voicewarden.api.event.VoiceUnmuteEvent;
import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.core.config.MutesConfig;
import dev.voicewarden.core.moderation.DurationParser;
import dev.voicewarden.core.storage.PardonResult;
import dev.voicewarden.core.storage.StorageProvider;
import dev.voicewarden.plugin.scheduler.TaskHandle;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public final class NativeMuteService implements VoiceWardenAPI {

    public static final String NOT_MUTED_LABEL = "";
    public static final Duration EXPIRY_SWEEP_PERIOD = Duration.ofSeconds(1);
    public static final long INDICATOR_PERIOD_TICKS = 20L;

    private record ActiveMute(VoiceMuteRecord record, long expiresAtMillis, long version) {

        static final long NEVER = Long.MAX_VALUE;

        static ActiveMute of(VoiceMuteRecord record, long version) {
            return new ActiveMute(record, record.expiresAt() == null ? NEVER : record.expiresAt().toEpochMilli(), version);
        }

        boolean activeAt(long nowMillis) {
            return nowMillis < expiresAtMillis;
        }
    }

    private final StorageProvider storage;
    private final TaskScheduler scheduler;
    private final PlayerDirectory players;
    private final PlayerResolver resolver;
    private final MessageRenderer messages;
    private final MuteIndicators indicators;
    private final EventDispatcher events;
    private final MuteMirror luckPerms;
    private final StrikeCountCache strikes;
    private final Predicate<UUID> consent;
    private final Clock clock;
    private final String serverId;
    private final Logger logger;
    private final Executor async;
    private final Map<UUID, ActiveMute> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAttemptNotice = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> playerQueues = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerVersions = new ConcurrentHashMap<>();
    private final AtomicLong versionSeq = new AtomicLong();
    private final List<TaskHandle> tasks = new ArrayList<>();
    private volatile MutesConfig config;
    private volatile boolean nativeBlocking;
    private volatile boolean mirrorToLuckPerms;

    public NativeMuteService(
            MutesConfig config,
            StorageProvider storage,
            TaskScheduler scheduler,
            PlayerDirectory players,
            MessageRenderer messages,
            EventDispatcher events,
            MuteMirror luckPerms,
            StrikeCountCache strikes,
            Predicate<UUID> consent,
            Clock clock,
            String serverId,
            Logger logger) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.players = Objects.requireNonNull(players, "players");
        this.resolver = new PlayerResolver(players);
        this.messages = Objects.requireNonNull(messages, "messages");
        this.indicators = new MuteIndicators(messages);
        this.events = Objects.requireNonNull(events, "events");
        this.luckPerms = Objects.requireNonNull(luckPerms, "luckPerms");
        this.strikes = Objects.requireNonNull(strikes, "strikes");
        this.consent = Objects.requireNonNull(consent, "consent");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.async = scheduler::runAsync;
        applyConfig(config);
    }

    public void updateConfig(MutesConfig newConfig) {
        MutesConfig previous = this.config;
        applyConfig(newConfig);
        if (previous.indicator() != newConfig.indicator()) {
            for (Player player : players.onlinePlayers()) {
                scheduler.runForEntity(player, () -> indicators.clear(player));
            }
        }
    }

    private void applyConfig(MutesConfig newConfig) {
        this.config = Objects.requireNonNull(newConfig, "newConfig");
        boolean wantsLuckPerms = newConfig.enforcement() == MutesConfig.Enforcement.LUCKPERMS || newConfig.mirrorLuckPerms();
        this.mirrorToLuckPerms = wantsLuckPerms && luckPerms.isAvailable();
        if (newConfig.enforcement() == MutesConfig.Enforcement.LUCKPERMS && !luckPerms.isAvailable()) {
            logger.warning("mutes.enforcement is LUCKPERMS but LuckPerms is not installed. Voice mutes are blocked natively instead.");
            this.nativeBlocking = true;
        } else {
            this.nativeBlocking = newConfig.enforcement() == MutesConfig.Enforcement.NATIVE;
        }
        if (wantsLuckPerms && !luckPerms.isAvailable() && newConfig.enforcement() == MutesConfig.Enforcement.NATIVE) {
            logger.warning("mutes.mirror_luckperms is true but LuckPerms is not installed. Mutes are not mirrored.");
        }
    }

    public MutesConfig config() {
        return config;
    }

    public PlayerResolver resolver() {
        return resolver;
    }

    public void start() {
        synchronized (tasks) {
            tasks.add(scheduler.runAsyncFixedRate(this::sweepExpired, EXPIRY_SWEEP_PERIOD, EXPIRY_SWEEP_PERIOD));
            tasks.add(scheduler.runGlobalFixedRate(this::refreshIndicators, INDICATOR_PERIOD_TICKS, INDICATOR_PERIOD_TICKS));
        }
    }

    public void stop() {
        synchronized (tasks) {
            tasks.forEach(TaskHandle::cancel);
            tasks.clear();
        }
        for (Player player : players.onlinePlayers()) {
            if (indicators.hasBossBar(player.getUniqueId())) {
                try {
                    indicators.clear(player);
                } catch (RuntimeException e) {
                    logger.log(Level.FINE, "Could not clear mute indicator for " + player.getName() + " on stop", e);
                }
            }
        }
    }

    public CompletableFuture<Integer> loadActiveMutes() {
        return CompletableFuture.supplyAsync(() -> {
            List<VoiceMuteRecord> loaded = storage.findAllActiveMutes(clock.instant());
            for (VoiceMuteRecord record : loaded) {
                UUID playerId = record.playerId();
                if (!playerVersions.containsKey(playerId)) {
                    active.putIfAbsent(playerId, ActiveMute.of(record, 0L));
                }
                resolver.remember(playerId, record.playerName());
            }
            return loaded.size();
        }, async);
    }

    public boolean isVoiceBlocked(UUID playerId) {
        return nativeBlocking && isMuted(playerId);
    }

    @Override
    public boolean isMuted(UUID playerId) {
        ActiveMute mute = active.get(playerId);
        return mute != null && mute.activeAt(clock.millis());
    }

    @Override
    public Optional<VoiceMuteRecord> getActiveMute(UUID playerId) {
        ActiveMute mute = active.get(playerId);
        if (mute == null || !mute.activeAt(clock.millis())) {
            return Optional.empty();
        }
        return Optional.of(mute.record());
    }

    @Override
    public Optional<Duration> getRemainingMute(UUID playerId) {
        return getActiveMute(playerId).flatMap(record -> record.remainingDuration(clock.instant()));
    }

    @Override
    public String getFormattedMuteRemaining(UUID playerId) {
        Optional<VoiceMuteRecord> mute = getActiveMute(playerId);
        if (mute.isEmpty()) {
            return NOT_MUTED_LABEL;
        }
        return formatRemaining(mute.get());
    }

    @Override
    public int getActiveStrikes(UUID playerId) {
        return strikes.count(playerId);
    }

    @Override
    public boolean hasConsent(UUID playerId) {
        return consent.test(playerId);
    }

    @Override
    public Collection<VoiceMuteRecord> getActiveMutes() {
        long now = clock.millis();
        return active.values().stream().filter(mute -> mute.activeAt(now)).map(ActiveMute::record).toList();
    }

    @Override
    public List<VoiceMuteRecord> getMuteHistory(UUID playerId, int limit) {
        return storage.findMuteHistory(playerId, limit);
    }

    public Optional<KnownPlayer> resolveTarget(String input) {
        return resolver.resolve(input);
    }

    @Override
    public CompletableFuture<VoiceMuteRecord> mutePlayer(
            UUID playerId, String playerName, @Nullable Duration duration, String reason, String moderator) {
        return mutePlayer(playerId, playerName, duration, reason, moderator, false);
    }

    public CompletableFuture<VoiceMuteRecord> mutePlayer(
            UUID playerId, String playerName, @Nullable Duration duration, String reason, String moderator, boolean automatic) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(moderator, "moderator");
        if (duration != null && !duration.isPositive()) {
            throw new IllegalArgumentException("A mute duration must be positive, got " + duration);
        }
        Instant now = clock.instant();
        VoiceMuteRecord record = new VoiceMuteRecord(
                playerId, playerName, reason, moderator, now, duration == null ? null : now.plus(duration), true, serverId);
        long version = nextVersion(playerId);
        active.put(playerId, ActiveMute.of(record, version));
        resolver.remember(playerId, playerName);
        players.online(playerId).ifPresent(player -> scheduler.runForEntity(player, () -> announceMute(player, record, true)));
        events.callAsync(new VoiceMuteEvent(record, automatic));
        return enqueuePlayerOp(playerId, () -> {
            storage.deactivateActiveMutes(playerId);
            storage.saveMute(record);
            if (mirrorToLuckPerms) {
                luckPerms.applyAsync(playerId, duration).join();
            }
            return record;
        }).whenComplete((saved, error) -> logFailure("save the voice mute for " + playerName, error));
    }

    @Override
    public CompletableFuture<Boolean> unmutePlayer(UUID playerId, String moderator) {
        Objects.requireNonNull(moderator, "moderator");
        nextVersion(playerId);
        ActiveMute removed = active.remove(playerId);
        if (removed != null) {
            finishUnmute(removed.record(), moderator, false, MessageKey.MUTE_UNMUTED);
        }
        return enqueuePlayerOp(playerId, () -> {
            int deactivated = storage.deactivateActiveMutes(playerId);
            if (mirrorToLuckPerms) {
                luckPerms.removeAsync(playerId).join();
            }
            return removed != null || deactivated > 0;
        }).whenComplete((changed, error) -> logFailure("remove the voice mute for " + playerId, error));
    }

    @Override
    public CompletableFuture<Boolean> pardonPlayer(UUID playerId, String moderator) {
        Objects.requireNonNull(moderator, "moderator");
        nextVersion(playerId);
        ActiveMute removed = active.remove(playerId);
        if (removed != null) {
            finishUnmute(removed.record(), moderator, false, MessageKey.MUTE_UNMUTED);
        }
        return enqueuePlayerOp(playerId, () -> {
            PardonResult result = storage.pardonPlayer(playerId);
            if (mirrorToLuckPerms) {
                luckPerms.removeAsync(playerId).join();
            }
            strikes.put(playerId, 0);
            players.online(playerId).ifPresent(player -> scheduler.runForEntity(
                    player, () -> player.sendMessage(messages.render(MessageKey.MUTE_PARDONED, player, Map.of()))));
            return removed != null || result.changedAnything();
        }).whenComplete((changed, error) -> logFailure("pardon " + playerId, error));
    }

    public void sweepExpired() {
        long now = clock.millis();
        for (Map.Entry<UUID, ActiveMute> entry : active.entrySet()) {
            ActiveMute mute = entry.getValue();
            if (mute.activeAt(now) || !active.remove(entry.getKey(), mute)) {
                continue;
            }
            VoiceMuteRecord record = mute.record();
            UUID playerId = record.playerId();
            long expiredVersion = mute.version();
            finishUnmute(record, "EXPIRED", true, MessageKey.MUTE_EXPIRED);
            enqueuePlayerOp(playerId, () -> {
                if (currentVersion(playerId) != expiredVersion) {
                    return null;
                }
                storage.deactivateActiveMutes(playerId);
                if (mirrorToLuckPerms) {
                    luckPerms.removeAsync(playerId).join();
                }
                return null;
            }).whenComplete((ignored, error) -> logFailure("expire the voice mute for " + record.playerName(), error));
        }
    }

    public void refreshIndicators() {
        MutesConfig current = config;
        if (current.indicator() != MutesConfig.Indicator.ACTION_BAR && current.indicator() != MutesConfig.Indicator.BOSS_BAR) {
            return;
        }
        long now = clock.millis();
        for (ActiveMute mute : active.values()) {
            if (!mute.activeAt(now)) {
                continue;
            }
            UUID playerId = mute.record().playerId();
            players.online(playerId).ifPresent(player -> scheduler.runForEntity(player, () -> {
                MutesConfig latestConfig = config;
                ActiveMute latestMute = active.get(playerId);
                if (latestMute == null || !latestMute.activeAt(clock.millis())
                        || (latestConfig.indicator() != MutesConfig.Indicator.ACTION_BAR
                                && latestConfig.indicator() != MutesConfig.Indicator.BOSS_BAR)) {
                    return;
                }
                indicators.refresh(player, latestConfig.indicator(), formatRemaining(latestMute.record()),
                        latestMute.record().isPermanent(), progress(latestMute.record()));
            }));
        }
    }

    public void onSpeakAttempt(UUID playerId) {
        MutesConfig current = config;
        if (!current.notifyOnAttempt()) {
            return;
        }
        ActiveMute mute = active.get(playerId);
        long now = clock.millis();
        if (mute == null || !mute.activeAt(now) || !claimAttemptNotice(playerId, now, current.attemptCooldownSeconds() * 1000L)) {
            return;
        }
        players.online(playerId).ifPresent(player -> scheduler.runForEntity(player, () -> player.sendMessage(
                messages.render(MessageKey.MUTE_ATTEMPT_WHILE_MUTED, player, Map.of("remaining", formatRemaining(mute.record()))))));
    }

    private boolean claimAttemptNotice(UUID playerId, long now, long cooldownMillis) {
        while (true) {
            Long previous = lastAttemptNotice.get(playerId);
            if (previous == null) {
                if (lastAttemptNotice.putIfAbsent(playerId, now) == null) {
                    return true;
                }
                continue;
            }
            if (now - previous < cooldownMillis) {
                return false;
            }
            if (lastAttemptNotice.replace(playerId, previous, now)) {
                return true;
            }
        }
    }

    public void onJoin(Player player) {
        UUID playerId = player.getUniqueId();
        resolver.remember(playerId, player.getName());
        strikes.refresh(playerId);
        ActiveMute cached = active.get(playerId);
        if (cached != null && cached.activeAt(clock.millis())) {
            scheduler.runForEntity(player, () -> announceMute(player, cached.record()));
            return;
        }
        long beforeVersion = currentVersion(playerId);
        enqueuePlayerOp(playerId, () -> {
            if (currentVersion(playerId) != beforeVersion) {
                return null;
            }
            storage.findActiveMute(playerId, clock.instant()).ifPresent(record -> {
                if (currentVersion(playerId) == beforeVersion
                        && active.putIfAbsent(playerId, ActiveMute.of(record, beforeVersion)) == null) {
                    scheduler.runForEntity(player, () -> announceMute(player, record));
                }
            });
            return null;
        }).whenComplete((ignored, error) -> logFailure("load the voice mute for " + player.getName(), error));
    }

    private long nextVersion(UUID playerId) {
        long version = versionSeq.incrementAndGet();
        playerVersions.put(playerId, version);
        return version;
    }

    private long currentVersion(UUID playerId) {
        return playerVersions.getOrDefault(playerId, 0L);
    }

    private <T> CompletableFuture<T> enqueuePlayerOp(UUID playerId, Supplier<T> work) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> previous = playerQueues.put(playerId, gate);
        CompletableFuture<Void> base = previous == null ? CompletableFuture.completedFuture(null) : previous;
        CompletableFuture<T> result = new CompletableFuture<>();
        base.whenComplete((ignored, err) -> async.execute(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            } finally {
                playerQueues.remove(playerId, gate);
                gate.complete(null);
            }
        }));
        return result;
    }

    public void onQuit(UUID playerId) {
        indicators.forget(playerId);
        lastAttemptNotice.remove(playerId);
        strikes.forget(playerId);
    }

    private void announceMute(Player player, VoiceMuteRecord record) {
        announceMute(player, record, false);
    }

    private void announceMute(Player player, VoiceMuteRecord record, boolean initial) {
        MessageKey key = record.isPermanent() ? MessageKey.MUTE_MUTED_PERMANENT : MessageKey.MUTE_MUTED;
        String shownDuration = initial
                ? DurationParser.format(record.remainingDuration(record.createdAt()).or(() ->
                        record.isPermanent() ? Optional.empty() : Optional.of(Duration.ZERO)))
                : formatRemaining(record);
        player.sendMessage(messages.render(key, player, Map.of("duration", shownDuration, "reason", record.reason())));
        indicators.show(player, config.indicator(), formatRemaining(record), record.isPermanent(), progress(record));
    }

    private void finishUnmute(VoiceMuteRecord record, String moderator, boolean expired, MessageKey notice) {
        lastAttemptNotice.remove(record.playerId());
        players.online(record.playerId()).ifPresent(player -> scheduler.runForEntity(player, () -> {
            indicators.clear(player);
            player.sendMessage(messages.render(notice, player, Map.of()));
        }));
        events.callAsync(new VoiceUnmuteEvent(record.playerId(), record.playerName(), moderator, expired));
    }

    private String formatRemaining(VoiceMuteRecord record) {
        return DurationParser.format(record.remainingDuration(clock.instant()).or(() ->
                record.isPermanent() ? Optional.empty() : Optional.of(Duration.ZERO)));
    }

    private float progress(VoiceMuteRecord record) {
        if (record.expiresAt() == null) {
            return 1f;
        }
        long total = Duration.between(record.createdAt(), record.expiresAt()).toMillis();
        long left = record.expiresAt().toEpochMilli() - clock.millis();
        return total <= 0 ? 0f : (float) left / total;
    }

    private void logFailure(String action, @Nullable Throwable error) {
        if (error != null) {
            logger.log(Level.WARNING, "Could not " + action, error);
        }
    }
}
