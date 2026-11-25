package dev.voicewarden.plugin.consent;

import dev.voicewarden.core.config.ConsentConfig;
import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.core.moderation.ConsentStatus;
import dev.voicewarden.core.moderation.ConsentStore;
import dev.voicewarden.plugin.mute.PlayerDirectory;
import dev.voicewarden.plugin.scheduler.TaskHandle;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import dev.voicewarden.plugin.voice.VoiceBackendManager;
import java.io.IOException;
import java.time.Clock;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.Nullable;

public final class ConsentManager implements ConsentView {

    public static final String DISABLED_STATUS = "disabled";
    public static final String COMMAND_USAGE = "/voiceconsent [accept|deny]";
    public static final long REOPEN_DELAY_TICKS = 10L;
    public static final long RULES_REOPEN_DELAY_TICKS = 100L;
    public static final long BLOCKED_NOTICE_COOLDOWN_MS = 10_000L;

    @FunctionalInterface
    interface ConsentWrite {
        void write(ConsentStore store, UUID playerId, int rulesVersion) throws IOException;
    }

    private final ConsentStore store;
    private final TaskScheduler scheduler;
    private final PlayerDirectory players;
    private final MessageRenderer messages;
    private final ConsentPrompt javaMenu;
    private final BedrockConsentForms bedrock;
    private final Clock clock;
    private final Consumer<UUID> onDenied;
    private final Logger logger;
    private final Map<UUID, TaskHandle> scheduledPrompts = new ConcurrentHashMap<>();
    private final Set<UUID> decisionsInFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastBlockedNotice = new ConcurrentHashMap<>();
    private volatile ConsentConfig config;

    public ConsentManager(
            ConsentStore store,
            ConsentConfig config,
            TaskScheduler scheduler,
            PlayerDirectory players,
            MessageRenderer messages,
            ConsentPrompt javaMenu,
            BedrockConsentForms bedrock,
            Clock clock,
            Consumer<UUID> onDenied,
            Logger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.players = Objects.requireNonNull(players, "players");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.javaMenu = Objects.requireNonNull(javaMenu, "javaMenu");
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onDenied = Objects.requireNonNull(onDenied, "onDenied");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ConsentConfig config() {
        return config;
    }

    public ConsentStatus status(UUID playerId) {
        return store.status(playerId, config.rulesVersion());
    }

    @Override
    public boolean isMonitoringAllowed(UUID playerId) {
        ConsentConfig current = config;
        if (!current.enabled()) {
            return true;
        }
        ConsentStatus status = store.status(playerId, current.rulesVersion());
        return switch (status) {
            case ACCEPTED -> true;
            case DENIED -> false;
            case PENDING -> current.mode() == ConsentConfig.Mode.NOTICE;
            case OUTDATED -> current.mode() == ConsentConfig.Mode.NOTICE && !previouslyDenied(playerId);
        };
    }

    @Override
    public String placeholderStatus(UUID playerId) {
        if (!config.enabled()) {
            return DISABLED_STATUS;
        }
        return status(playerId).name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void onUnconsentedSpeakAttempt(UUID playerId) {
        if (isMonitoringAllowed(playerId)) {
            return;
        }
        long now = clock.millis();
        if (!claimBlockedNotice(playerId, now)) {
            return;
        }
        players.online(playerId).ifPresent(player -> scheduler.runForEntity(
                player, () -> player.sendMessage(messages.render(MessageKey.CONSENT_VOICE_DISABLED, player, Map.of()))));
    }

    public boolean needsPrompt(UUID playerId) {
        ConsentConfig current = config;
        if (!current.enabled() || decisionsInFlight.contains(playerId)) {
            return false;
        }
        ConsentStatus status = store.status(playerId, current.rulesVersion());
        return status == ConsentStatus.PENDING || status == ConsentStatus.OUTDATED
                || (status == ConsentStatus.DENIED && current.onDeny() == ConsentConfig.OnDeny.KICK);
    }

    public void onJoin(Player player) {
        ConsentConfig current = config;
        if (player.hasPermission(VoiceBackendManager.BYPASS_PERMISSION) || !needsPrompt(player.getUniqueId())) {
            return;
        }
        if (current.mode() == ConsentConfig.Mode.NOTICE) {
            sendNoticeAndAutoAccept(player, current.rulesVersion());
            return;
        }
        schedulePrompt(player, current.guiDelayTicks());
    }

    public CompletableFuture<Void> reset(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.runAsync(() -> {
            try {
                store.reset(playerId);
                lastBlockedNotice.remove(playerId);
                future.complete(null);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not reset voice consent for " + playerId + " in " + store.file(), e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void onQuit(UUID playerId) {
        lastBlockedNotice.remove(playerId);
        TaskHandle pending = scheduledPrompts.remove(playerId);
        if (pending != null) {
            pending.cancel();
        }
    }

    public void accept(Player player) {
        accept(player, config.rulesVersion());
    }

    public void accept(Player player, int shownRulesVersion) {
        decide(player, ConsentStore::accept, MessageKey.CONSENT_ACCEPTED, false, false, shownRulesVersion);
    }

    public void deny(Player player) {
        deny(player, config.rulesVersion());
    }

    public void deny(Player player, int shownRulesVersion) {
        decide(player, ConsentStore::deny, MessageKey.CONSENT_DENIED, config.onDeny() == ConsentConfig.OnDeny.KICK, true, shownRulesVersion);
    }

    public void showRules(Player player) {
        closeConsentMenu(player);
        player.sendMessage(messages.render(MessageKey.PRIVACY_MSG, player, Map.of()));
        if (config.mode() == ConsentConfig.Mode.GUI) {
            schedulePrompt(player, RULES_REOPEN_DELAY_TICKS);
        }
    }

    public void onPromptDismissed(Player player) {
        if (config.mode() == ConsentConfig.Mode.GUI && needsPrompt(player.getUniqueId())) {
            schedulePrompt(player, REOPEN_DELAY_TICKS);
        }
    }

    public void onCommand(Player player, @Nullable String subcommand) {
        if (subcommand == null || subcommand.isBlank()) {
            player.sendMessage(messages.render(MessageKey.PRIVACY_MSG, player, Map.of()));
            player.sendMessage(messages.render(MessageKey.CONSENT_STATUS, player, Map.of("status", placeholderStatus(player.getUniqueId()))));
            if (config.mode() == ConsentConfig.Mode.GUI && needsPrompt(player.getUniqueId())) {
                openPrompt(player);
            }
            return;
        }
        switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "accept" -> accept(player);
            case "deny" -> deny(player);
            default -> player.sendMessage(messages.render(MessageKey.COMMANDS_USAGE, player, Map.of("usage", COMMAND_USAGE)));
        }
    }

    public void onConfigReload(ConsentConfig newConfig, Collection<? extends Player> onlinePlayers) {
        ConsentConfig previous = config;
        config = Objects.requireNonNull(newConfig, "newConfig");
        boolean bumped = newConfig.rulesVersion() > previous.rulesVersion();
        boolean newlyEnabled = newConfig.enabled() && !previous.enabled();
        if (!newConfig.enabled() || !(bumped || newlyEnabled)) {
            return;
        }
        for (Player player : onlinePlayers) {
            scheduler.runForEntity(player, () -> repromptAfterReload(player, newConfig));
        }
    }

    private void repromptAfterReload(Player player, ConsentConfig newConfig) {
        UUID playerId = player.getUniqueId();
        if (player.hasPermission(VoiceBackendManager.BYPASS_PERMISSION) || !needsPrompt(playerId)) {
            return;
        }
        if (newConfig.mode() == ConsentConfig.Mode.NOTICE) {
            sendNoticeAndAutoAccept(player, newConfig.rulesVersion());
            return;
        }
        if (store.status(playerId, newConfig.rulesVersion()) == ConsentStatus.OUTDATED) {
            player.sendMessage(messages.render(MessageKey.CONSENT_RULES_UPDATED, player, Map.of()));
        }
        openPrompt(player);
    }

    private void sendNoticeAndAutoAccept(Player player, int rulesVersion) {
        UUID playerId = player.getUniqueId();
        boolean outdated = store.status(playerId, rulesVersion) == ConsentStatus.OUTDATED;
        boolean deniedBefore = previouslyDenied(playerId);
        scheduler.runForEntity(player, () -> {
            if (outdated) {
                player.sendMessage(messages.render(MessageKey.CONSENT_RULES_UPDATED, player, Map.of()));
            }
            player.sendMessage(messages.render(MessageKey.PRIVACY_JOIN_MSG, player, Map.of()));
        });
        if (!deniedBefore) {
            scheduler.runAsync(() -> {
                try {
                    store.acceptIfNotDenied(playerId, rulesVersion);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not save voice consent for " + player.getName() + " to " + store.file(), e);
                }
            });
        }
    }

    private boolean claimBlockedNotice(UUID playerId, long now) {
        while (true) {
            Long previous = lastBlockedNotice.get(playerId);
            if (previous == null) {
                if (lastBlockedNotice.putIfAbsent(playerId, now) == null) {
                    return true;
                }
                continue;
            }
            if (now - previous < BLOCKED_NOTICE_COOLDOWN_MS) {
                return false;
            }
            if (lastBlockedNotice.replace(playerId, previous, now)) {
                return true;
            }
        }
    }

    private void schedulePrompt(Player player, long delayTicks) {
        UUID playerId = player.getUniqueId();
        TaskHandle task = scheduler.runForEntityDelayed(player, () -> {
            scheduledPrompts.remove(playerId);
            if (player.isOnline() && needsPrompt(playerId)) {
                openPrompt(player);
            }
        }, delayTicks);
        TaskHandle previous = scheduledPrompts.put(playerId, task);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void openPrompt(Player player) {
        if (bedrock.isBedrock(player.getUniqueId())) {
            bedrock.open(player, this);
        } else {
            javaMenu.open(player, this);
        }
    }

    private void decide(
            Player player,
            ConsentWrite write,
            MessageKey confirmation,
            boolean kick,
            boolean denied,
            int rulesVersion) {
        UUID playerId = player.getUniqueId();
        if (!decisionsInFlight.add(playerId)) {
            return;
        }
        onQuit(playerId);
        closeConsentMenu(player);
        scheduler.runAsync(() -> {
            boolean saved = false;
            try {
                write.write(store, playerId, rulesVersion);
                lastBlockedNotice.remove(playerId);
                if (denied) {
                    onDenied.accept(playerId);
                }
                saved = true;
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not save voice consent for " + player.getName() + " to " + store.file(), e);
            } finally {
                decisionsInFlight.remove(playerId);
            }
            boolean persisted = saved;
            scheduler.runForEntity(player, () -> {
                if (!persisted) {
                    if (config.mode() == ConsentConfig.Mode.GUI && needsPrompt(playerId)) {
                        schedulePrompt(player, REOPEN_DELAY_TICKS);
                    }
                    return;
                }
                if (kick) {
                    player.kick(messages.render(MessageKey.CONSENT_KICK_MESSAGE, player, Map.of()));
                    return;
                }
                player.sendMessage(messages.render(confirmation, player, Map.of()));
                if (needsPrompt(playerId)) {
                    repromptAfterReload(player, config);
                }
            });
        });
    }

    private static void closeConsentMenu(Player player) {
        InventoryView view = player.getOpenInventory();
        if (view != null && view.getTopInventory().getHolder(false) instanceof ConsentMenuHolder) {
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
        }
    }

    private boolean previouslyDenied(UUID playerId) {
        return store.record(playerId).map(record -> record.decision() == ConsentStore.Decision.DENIED).orElse(false);
    }
}
