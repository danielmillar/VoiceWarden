package dev.danielmillar.voicewarden.moderation;

import dev.danielmillar.voicewarden.audio.AudioSegment;
import dev.danielmillar.voicewarden.config.Durations;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.evidence.EvidenceSettings;
import dev.danielmillar.voicewarden.evidence.EvidenceStore;
import dev.danielmillar.voicewarden.health.Metrics;
import dev.danielmillar.voicewarden.moderation.rules.RuleAction;
import dev.danielmillar.voicewarden.moderation.rules.RuleEngine;
import dev.danielmillar.voicewarden.moderation.rules.RuleMatch;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.notify.StaffAlertService;
import dev.danielmillar.voicewarden.notify.discord.DiscordWebhookService;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.stt.Transcript;
import dev.danielmillar.voicewarden.voice.AudioIngestService;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides what happens to each transcript. Runs on speech-to-text worker threads; it never runs on the server or
 * voice packet threads. Mute checks and counters are O(1); LuckPerms writes, Discord, evidence files and console
 * commands are handed off asynchronously. The one exception is an offense count that is not cached yet (an offline
 * player): loading it from LuckPerms can block this worker.
 *
 * <p>FLAG/profanity hits alert staff only. MUTE-list hits add flags; when
 * the flags inside {@code flag-window} reach {@code flag-threshold} the player is auto-muted for the ladder duration of
 * their offense number, otherwise they may be warned.
 */
public final class ModerationService {

    private final Supplier<PluginConfig> config;
    private final Supplier<Messages> messages;
    private final Supplier<RuleEngine> rules;
    private final TranscriptBuffer buffer;
    private final FlagTracker flags = new FlagTracker();
    private final Object[] stripes = new Object[64];
    private final OffenseTracker offenses;
    private final MuteService mutes;
    private final StaffAlertService alerts;
    private final DiscordWebhookService discord;
    private final EvidenceStore evidence;
    private final CustomCommandRunner commands;
    private final TranscriptLog transcriptLog;
    private final OnlinePlayers players;
    private final Metrics metrics;
    private final Logger logger;

    public ModerationService(Supplier<PluginConfig> config, Supplier<Messages> messages, Supplier<RuleEngine> rules,
                             TranscriptBuffer buffer, OffenseTracker offenses, MuteService mutes, StaffAlertService alerts,
                             DiscordWebhookService discord, EvidenceStore evidence, CustomCommandRunner commands,
                             TranscriptLog transcriptLog, OnlinePlayers players, Metrics metrics, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.rules = rules;
        this.buffer = buffer;
        this.offenses = offenses;
        this.mutes = mutes;
        this.alerts = alerts;
        this.discord = discord;
        this.evidence = evidence;
        this.commands = commands;
        this.transcriptLog = transcriptLog;
        this.players = players;
        this.metrics = metrics;
        this.logger = logger;
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
    }

    /** Speech-to-text worker thread. */
    public void onTranscript(Transcript transcript) {
        PluginConfig cfg = config.get();
        if (!cfg.enabled()) {
            return;
        }
        AudioSegment segment = transcript.segment();
        UUID id = segment.playerId();
        String text = transcript.text();
        Player online = players.get(id);
        if (online != null && online.hasPermission(AudioIngestService.BYPASS_PERMISSION)) {
            return;
        }

        PluginConfig.Moderation mod = cfg.moderation();
        List<TranscriptLine> context = buffer.recentLines(id, mod.contextLines());
        buffer.add(id, segment.playerName(), segment.spokenAt(), text, segment.pcm());
        if (cfg.logging().transcripts()) {
            transcriptLog.append(segment.spokenAt(), id, segment.playerName(), text);
        }
        if (cfg.logging().debug()) {
            logger.info("[transcript " + transcript.latencyMillis() + "ms] " + segment.playerName() + ": " + text);
        }
        if (cfg.recordings().mode() == EvidenceSettings.Mode.ALL) {
            evidence.saveUtterance(id, segment.playerName(), segment.spokenAt(), text, segment.pcm(), segment.sampleRate());
        }

        List<RuleMatch> matches = rules.get().match(text);
        if (matches.isEmpty()) {
            return;
        }

        int muteFlags = 0;
        for (RuleMatch match : matches) {
            if (match.action() == RuleAction.MUTE) {
                muteFlags += match.weight();
            }
        }
        var storedOffenses = muteFlags > 0 && !mutes.isMuted(id)
                ? offenses.lookup(id, mod.ladder().resetAfterDays()).join()
                : dev.danielmillar.voicewarden.luckperms.LuckPermsHook.Offenses.NONE;
        boolean dryRun = mod.dryRun();
        boolean alreadyMuted = false;
        ModerationAction action = ModerationAction.NONE;
        int inWindow = 0;
        int offense = 0;
        Incident incident;
        // Several workers may hold transcripts of the same player: check → count → mute must be atomic per player,
        // or two utterances could both cross the threshold and record two offenses. Striped lock.
        synchronized (stripes[Math.floorMod(id.hashCode(), stripes.length)]) {
            if (!config.get().enabled()) {
                return;
            }
            if (muteFlags > 0) {
                if (mutes.isMuted(id)) {
                    alreadyMuted = true; // e.g. speech queued before the mute landed: don't stack punishments
                } else {
                    inWindow = flags.add(id, muteFlags, System.currentTimeMillis(), mod.flagWindow().toMillis());
                    if (inWindow >= mod.flagThreshold()) {
                        int resetDays = mod.ladder().resetAfterDays();
                        offense = dryRun ? offenses.peekNext(id, resetDays, storedOffenses) : offenses.recordNext(id, resetDays, storedOffenses);
                        action = ModerationAction.mute(mod.muteDurationFor(offense));
                        flags.reset(id);
                    } else if (mod.warnBelowThreshold()) {
                        action = ModerationAction.WARN;
                    }
                }
            }
            incident = new Incident(UUID.randomUUID(), Instant.now(), segment.spokenAt(), segment.duration(), id,
                    segment.playerName(), text, matches, muteFlags, inWindow, mod.flagThreshold(), action, offense,
                    dryRun, context, segment.location(), segment.pcm(), segment.sampleRate());
            if (!dryRun && !alreadyMuted) {
                apply(cfg, incident, online); // the local mute is in place before the lock is released
            }
        }
        metrics.incidents.increment();
        alerts.incident(incident, alreadyMuted);
        discord.submitIncident(incident);
        if (cfg.recordings().mode() != EvidenceSettings.Mode.NONE) {
            evidence.saveIncident(incident).exceptionally(error -> {
                logger.log(Level.WARNING, "Could not save evidence for incident " + incident.shortId(), error);
                return null;
            });
        }
    }

    private void apply(PluginConfig cfg, Incident incident, Player online) {
        Map<String, String> placeholders = placeholders(incident);
        switch (incident.action().type()) {
            case MUTE -> {
                metrics.autoMutes.increment();
                mutes.mute(incident.playerId(), incident.playerName(), incident.action().duration(),
                        "Automatic: " + StaffAlertService.categories(incident.matches()), "VoiceWarden", true);
                commands.run(cfg.customCommands().mute(), placeholders);
            }
            case WARN -> {
                metrics.warnings.increment();
                if (online != null && cfg.mute().notifyPlayer()) {
                    online.sendMessage(messages.get().render("player.warned"));
                }
                commands.run(cfg.customCommands().warn(), placeholders);
            }
            case NONE -> commands.run(cfg.customCommands().profanity(), placeholders);
        }
    }

    private static Map<String, String> placeholders(Incident incident) {
        Set<String> words = new LinkedHashSet<>();
        for (RuleMatch match : incident.matches()) {
            words.add(match.matchedText());
        }
        Map<String, String> map = new LinkedHashMap<>();
        map.put("player", incident.playerName());
        map.put("uuid", incident.playerId().toString());
        map.put("words", String.join(", ", words));
        map.put("transcript", incident.transcript());
        map.put("duration", Durations.format(incident.action().duration()));
        map.put("offense", Integer.toString(incident.offense()));
        map.put("categories", StaffAlertService.categories(incident.matches()));
        return map;
    }

    /** Periodic cleanup (timer → IO thread). */
    public void housekeeping() {
        PluginConfig cfg = config.get();
        flags.prune(System.currentTimeMillis(), cfg.moderation().flagWindow().toMillis());
        buffer.prune(Instant.now());
    }

    public OffenseTracker offenses() {
        return offenses;
    }

    public void onQuit(UUID player) {
        offenses.forget(player);
    }
}
