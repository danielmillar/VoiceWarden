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
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>With {@code speech-to-text.double-check} on, a transcript with MUTE-list hits is decided only once the second
 * model has heard the same clip, on its thread. Only MUTE rules both models heard add flags; if none did, staff are
 * alerted and nobody is punished.
 */
public final class ModerationService {

    /** The double-check model: the text it hears in a clip, piece by piece. Never blocks. */
    @FunctionalInterface
    public interface SecondOpinion {
        CompletableFuture<List<String>> transcribe(AudioSegment segment);
    }

    private record Verdict(Incident.DoubleCheck check, Set<String> confirmedRules) {
    }

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
    private final SecondOpinion secondOpinion;
    private final AtomicLong lastCheckErrorLog = new AtomicLong();

    public ModerationService(Supplier<PluginConfig> config, Supplier<Messages> messages, Supplier<RuleEngine> rules,
                             TranscriptBuffer buffer, OffenseTracker offenses, MuteService mutes, StaffAlertService alerts,
                             DiscordWebhookService discord, EvidenceStore evidence, CustomCommandRunner commands,
                             TranscriptLog transcriptLog, OnlinePlayers players, Metrics metrics, Logger logger,
                             SecondOpinion secondOpinion) {
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
        this.secondOpinion = secondOpinion;
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
        if (!cfg.speechToText().doubleCheck() || matches.stream().noneMatch(m -> m.action() == RuleAction.MUTE)) {
            decide(cfg, segment, text, matches, context, null);
            return;
        }
        // Never run inference under the per-player lock: decide when the second model answers, on its thread.
        secondOpinion.transcribe(segment).whenComplete((heard, error) -> {
            if (error instanceof CancellationException) {
                return; // the engine stopped or the check was turned off before the second model heard it
            }
            try {
                decide(config.get(), segment, text, matches, context, verdict(segment, matches, heard, error));
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Moderation of a double-checked transcript failed", t);
            }
        });
    }

    /** A hit the second model could not hear, for whatever reason, is unconfirmed: never punish unchecked. */
    private Verdict verdict(AudioSegment segment, List<RuleMatch> matches, @Nullable List<String> heard,
                            @Nullable Throwable error) {
        if (error != null || heard == null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            long now = System.currentTimeMillis();
            long last = lastCheckErrorLog.get();
            if (now - last > 60_000 && lastCheckErrorLog.compareAndSet(last, now)) {
                logger.log(Level.WARNING, "Could not double-check a hit from " + segment.playerName()
                        + ", so it was not acted on (further errors suppressed for 60s)", cause);
            }
            return new Verdict(new Incident.DoubleCheck(false, null), Set.of());
        }
        RuleEngine engine = rules.get();
        Set<String> heardRules = new HashSet<>();
        for (String piece : heard) {
            for (RuleMatch match : engine.match(piece)) {
                if (match.action() == RuleAction.MUTE) {
                    heardRules.add(match.ruleId());
                }
            }
        }
        Set<String> confirmed = new HashSet<>();
        for (RuleMatch match : matches) {
            if (match.action() == RuleAction.MUTE && heardRules.contains(match.ruleId())) {
                confirmed.add(match.ruleId());
            }
        }
        return new Verdict(new Incident.DoubleCheck(!confirmed.isEmpty(), String.join(" … ", heard)), confirmed);
    }

    private void decide(PluginConfig cfg, AudioSegment segment, String text, List<RuleMatch> matches,
                        List<TranscriptLine> context, @Nullable Verdict verdict) {
        UUID id = segment.playerId();
        PluginConfig.Moderation mod = cfg.moderation();
        Player online = players.get(id);
        int muteFlags = 0;
        for (RuleMatch match : matches) {
            if (match.action() == RuleAction.MUTE && (verdict == null || verdict.confirmedRules().contains(match.ruleId()))) {
                muteFlags += match.weight();
            }
        }
        boolean unconfirmed = verdict != null && !verdict.check().confirmed();
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
                        offense = dryRun ? offenses.peekNext(id, resetDays) : offenses.recordNext(id, resetDays);
                        action = ModerationAction.mute(mod.muteDurationFor(offense));
                        flags.reset(id);
                    } else if (mod.warnBelowThreshold()) {
                        action = ModerationAction.WARN;
                    }
                }
            }
            incident = new Incident(UUID.randomUUID(), Instant.now(), segment.spokenAt(), segment.duration(), id,
                    segment.playerName(), text, matches, muteFlags, inWindow, mod.flagThreshold(), action, offense,
                    dryRun, context, segment.location(), segment.pcm(), segment.sampleRate(),
                    verdict == null ? null : verdict.check());
            if (!dryRun && !alreadyMuted && !unconfirmed) {
                apply(cfg, incident, online); // the local mute is in place before the lock is released
            }
        }
        metrics.incidents.increment();
        if (unconfirmed) {
            metrics.unconfirmed.increment();
            logger.info("Double check did not confirm " + segment.playerName() + "'s hit, no action taken: \""
                    + text + "\", second model: " + verdict.check().describe() + " #" + incident.shortId());
        }
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
}
