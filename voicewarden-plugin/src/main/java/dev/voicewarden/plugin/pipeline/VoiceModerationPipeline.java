package dev.voicewarden.plugin.pipeline;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.api.VoiceMuteRecord;
import dev.voicewarden.api.event.VoiceFlagEvent;
import dev.voicewarden.api.event.VoiceStrikeEvent;
import dev.voicewarden.core.audio.AcousticAbuseEvent;
import dev.voicewarden.core.audio.AudioEventSink;
import dev.voicewarden.core.audio.FinalUtterance;
import dev.voicewarden.core.audio.LiveAudioChunk;
import dev.voicewarden.core.audio.MicSpamConfig;
import dev.voicewarden.core.audio.ShardedAudioPipeline;
import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.core.config.VoiceWardenConfig;
import dev.voicewarden.core.discord.DiscordWebhookClient;
import dev.voicewarden.core.discord.FlagWebhookPayload;
import dev.voicewarden.core.discord.MicSpamWebhookPayload;
import dev.voicewarden.core.discord.MuteWebhookPayload;
import dev.voicewarden.core.ipc.EngineClient;
import dev.voicewarden.core.ipc.EngineResultListener;
import dev.voicewarden.core.ipc.EngineSupervisorListener;
import dev.voicewarden.core.ipc.IpcMessage;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.moderation.Detection;
import dev.voicewarden.core.moderation.DetectionPhase;
import dev.voicewarden.core.moderation.DurationParser;
import dev.voicewarden.core.moderation.MatchedWord;
import dev.voicewarden.core.moderation.ModerationDecision;
import dev.voicewarden.core.moderation.ModerationEngine;
import dev.voicewarden.core.moderation.TranscriptBuffer;
import dev.voicewarden.core.moderation.TranscriptEntry;
import dev.voicewarden.core.wordlist.WordMatch;
import dev.voicewarden.core.wordlist.WordlistMatchResult;
import dev.voicewarden.core.wordlist.WordlistRegistry;
import dev.voicewarden.plugin.mute.EventDispatcher;
import dev.voicewarden.plugin.mute.NativeMuteService;
import dev.voicewarden.plugin.mute.PlayerDirectory;
import dev.voicewarden.plugin.mute.StrikeCountCache;
import dev.voicewarden.plugin.scheduler.TaskHandle;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import dev.voicewarden.plugin.voice.SpeakerContextTracker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public final class VoiceModerationPipeline implements AudioEventSink, EngineResultListener, EngineSupervisorListener, AutoCloseable {

    public static final String ALERTS_PERMISSION = "voicewarden.alerts";
    public static final String AUTO_MODERATOR = "VoiceWarden";
    public static final String AUTO_MUTE_REASON = "Inappropriate voice chat";
    public static final String MIC_SPAM_MUTE_REASON = "Mic spam / excessive volume";
    public static final Duration LIVE_AUDIO_TTL = Duration.ofSeconds(90);
    public static final int MAX_LIVE_BUFFERS = 256;
    public static final Duration FINAL_AUDIO_TTL = Duration.ofMinutes(2);
    public static final int MAX_FINAL_AUDIO = 64;
    public static final Duration HANDLED_TTL = Duration.ofMinutes(10);
    public static final int MAX_HANDLED = 4096;
    public static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(30);
    public static final int MAX_PENDING_VERIFICATIONS = 1024;
    public static final Duration SWEEP_PERIOD = Duration.ofSeconds(1);

    static final String DEBUG_TEMPLATE =
            "<dim><b>DEBUG</b></dim> <muted><kind></muted> <dim>·</dim> <accent><player></accent><dim>:</dim> <body><text></body> <warning><words></warning>";
    static final String DEBUG_VERIFY_CONFIRMED_TEMPLATE =
            "<dim><b>DEBUG</b></dim> <success><kind></success> <dim>·</dim> <accent><player></accent><dim>:</dim> <body><text></body>";
    static final String DEBUG_VERIFY_UNCONFIRMED_TEMPLATE =
            "<dim><b>DEBUG</b></dim> <warning><kind></warning> <dim>·</dim> <accent><player></accent><dim>:</dim> <muted><text></muted>";

    public record Settings(boolean doubleCheck, String appealUrl, int maxUtteranceSamples, String serverId, MicSpamConfig micSpam) {

        public Settings {
            Objects.requireNonNull(appealUrl, "appealUrl");
            Objects.requireNonNull(serverId, "serverId");
            Objects.requireNonNull(micSpam, "micSpam");
            maxUtteranceSamples = Math.max(1, maxUtteranceSamples);
        }

        public static Settings from(VoiceWardenConfig config) {
            return new Settings(
                    config.engine().doubleCheckActive(),
                    config.moderation().appealUrl(),
                    config.audio().maxUtteranceSamples16k(),
                    config.serverId(),
                    config.audio().micSpam());
        }
    }

    public record Status(
            boolean engineConnected,
            String engineProfile,
            String engineVersion,
            double engineLatencyMs,
            boolean doubleCheck,
            int liveBuffers,
            int cachedUtterances,
            int pendingVerifications,
            long transcripts,
            long flags,
            long actions,
            long unconfirmed,
            int debugTargets) {
    }

    public record Services(ModerationEngine moderation, DiscordWebhookClient discord) {

        public Services {
            Objects.requireNonNull(moderation, "moderation");
            Objects.requireNonNull(discord, "discord");
        }
    }

    record UtteranceKey(UUID playerId, long utteranceId) {
    }

    private record Hit(
            UtteranceKey key,
            String playerName,
            String transcript,
            DetectionPhase phase,
            List<MatchedWord> matches,
            short[] pcm,
            Instant detectedAt) {

        List<String> words() {
            return matches.stream().map(MatchedWord::word).distinct().toList();
        }

        Hit withMatches(List<MatchedWord> updated) {
            return new Hit(key, playerName, transcript, phase, List.copyOf(updated), pcm, detectedAt);
        }

        Hit withPcm(short[] audio) {
            return new Hit(key, playerName, transcript, phase, matches, audio, detectedAt);
        }
    }

    private static final class Pending {
        private final Hit hit;
        private Hit parked;
        private Hit finalBackup;

        Pending(Hit hit) {
            this.hit = hit;
        }
    }

    private static final class PcmBuffer {
        private short[] samples = new short[0];
        private int length;

        synchronized void append(short[] chunk, int maxSamples) {
            int room = maxSamples - length;
            int count = Math.min(room, chunk.length);
            if (count <= 0) {
                return;
            }
            if (length + count > samples.length) {
                samples = Arrays.copyOf(samples, Math.min(maxSamples, Math.max(length + count, samples.length * 2)));
            }
            System.arraycopy(chunk, 0, samples, length, count);
            length += count;
        }

        synchronized short[] snapshot() {
            return Arrays.copyOf(samples, length);
        }
    }

    private final TaskScheduler scheduler;
    private final PlayerDirectory players;
    private final MessageRenderer messages;
    private final WordlistRegistry wordlist;
    private final TranscriptBuffer transcripts;
    private final SpeakerContextTracker speakers;
    private final StrikeCountCache strikeCounts;
    private final NativeMuteService mutes;
    private final EventDispatcher asyncEvents;
    private final Consumer<Event> callEvent;
    private final BooleanSupplier primaryThread;
    private final Consumer<String> consoleCommand;
    private final Clock clock;
    private final Logger logger;
    private final ExpiringCache<UtteranceKey, PcmBuffer> liveAudio;
    private final ExpiringCache<UtteranceKey, short[]> finalAudio;
    private final ExpiringCache<UtteranceKey, Set<String>> handled;
    private final ExpiringCache<UtteranceKey, Set<String>> retryOnFinal;
    private final ExpiringCache<UtteranceKey, Pending> pending;
    private final ExpiringCache<UtteranceKey, Integer> staleVerifications;
    private final Map<UUID, Set<CommandSender>> debugViewers = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> micSpamCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Instant>> micSpamHits = new ConcurrentHashMap<>();
    private final AtomicLong transcriptCount = new AtomicLong();
    private final AtomicLong flagCount = new AtomicLong();
    private final AtomicLong actionCount = new AtomicLong();
    private final AtomicLong unconfirmedCount = new AtomicLong();
    private final List<TaskHandle> tasks = new ArrayList<>();
    private volatile Settings settings;
    private volatile Services services;
    private volatile @Nullable EngineClient client;
    private volatile @Nullable ShardedAudioPipeline audio;
    private volatile boolean closed;

    public VoiceModerationPipeline(
            Settings settings,
            Services services,
            TaskScheduler scheduler,
            PlayerDirectory players,
            MessageRenderer messages,
            WordlistRegistry wordlist,
            TranscriptBuffer transcripts,
            SpeakerContextTracker speakers,
            StrikeCountCache strikeCounts,
            NativeMuteService mutes,
            EventDispatcher asyncEvents,
            Consumer<Event> callEvent,
            BooleanSupplier primaryThread,
            Consumer<String> consoleCommand,
            Clock clock,
            Logger logger) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.services = Objects.requireNonNull(services, "services");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.players = Objects.requireNonNull(players, "players");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.wordlist = Objects.requireNonNull(wordlist, "wordlist");
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.speakers = Objects.requireNonNull(speakers, "speakers");
        this.strikeCounts = Objects.requireNonNull(strikeCounts, "strikeCounts");
        this.mutes = Objects.requireNonNull(mutes, "mutes");
        this.asyncEvents = Objects.requireNonNull(asyncEvents, "asyncEvents");
        this.callEvent = Objects.requireNonNull(callEvent, "callEvent");
        this.primaryThread = Objects.requireNonNull(primaryThread, "primaryThread");
        this.consoleCommand = Objects.requireNonNull(consoleCommand, "consoleCommand");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.liveAudio = new ExpiringCache<>(LIVE_AUDIO_TTL, MAX_LIVE_BUFFERS, clock);
        this.finalAudio = new ExpiringCache<>(FINAL_AUDIO_TTL, MAX_FINAL_AUDIO, clock);
        this.handled = new ExpiringCache<>(HANDLED_TTL, MAX_HANDLED, clock);
        this.retryOnFinal = new ExpiringCache<>(HANDLED_TTL, MAX_HANDLED, clock);
        this.pending = new ExpiringCache<>(VERIFY_TIMEOUT, MAX_PENDING_VERIFICATIONS, clock);
        this.staleVerifications = new ExpiringCache<>(HANDLED_TTL, MAX_PENDING_VERIFICATIONS, clock);
    }

    public void bindAudio(ShardedAudioPipeline pipeline) {
        this.audio = Objects.requireNonNull(pipeline, "pipeline");
    }

    public void start() {
        synchronized (tasks) {
            tasks.add(scheduler.runAsyncFixedRate(this::sweep, SWEEP_PERIOD, SWEEP_PERIOD));
        }
    }

    public void update(Settings newSettings, Services newServices) {
        this.settings = Objects.requireNonNull(newSettings, "newSettings");
        this.services = Objects.requireNonNull(newServices, "newServices");
    }

    public Settings settings() {
        return settings;
    }

    public Services services() {
        return services;
    }

    public Optional<EngineClient> client() {
        EngineClient current = client;
        return current != null && current.isConnected() ? Optional.of(current) : Optional.empty();
    }

    @Override
    public void onEngineReady(EngineClient engineClient, long pid) {
        if (closed) {
            return;
        }
        this.client = engineClient;
        logger.info("Speech engine is ready (pid " + pid + ", profile " + engineClient.engineProfile()
                + ", version " + engineClient.engineVersion() + ").");
    }

    @Override
    public void onEngineLost(long pid, String reason) {
        if (closed) {
            return;
        }
        this.client = null;
        logger.warning("Speech engine stopped (pid " + pid + "): " + reason + ". Voice is not transcribed until it restarts.");
        flushPending();
    }

    @Override
    public void onRestartScheduled(int restartsInWindow, Duration delay) {
        if (closed) {
            return;
        }
        logger.info("Restarting the speech engine in " + DurationParser.format(delay) + " (restart " + restartsInWindow + " in the window).");
    }

    @Override
    public void onGaveUp(String reason) {
        if (closed) {
            return;
        }
        this.client = null;
        logger.severe("The speech engine kept failing and will not be restarted: " + reason + ". Run /vw reload after fixing it.");
        flushPending();
    }

    @Override
    public void onDisconnected(Throwable cause) {
        if (closed) {
            return;
        }
        flushPending();
    }

    @Override
    public void onLiveChunk(LiveAudioChunk chunk) {
        if (closed) {
            return;
        }
        UtteranceKey key = new UtteranceKey(chunk.playerId(), chunk.utteranceId());
        int max = settings.maxUtteranceSamples();
        liveAudio.update(key, ignored -> new PcmBuffer(), buffer -> {
            buffer.append(chunk.pcm16k(), max);
            return buffer;
        });
        client().ifPresent(engine -> engine.sendLiveChunk(chunk));
    }

    @Override
    public void onFinalUtterance(FinalUtterance utterance) {
        if (closed) {
            return;
        }
        UtteranceKey key = new UtteranceKey(utterance.playerId(), utterance.utteranceId());
        finalAudio.put(key, utterance.pcm16k());
        liveAudio.remove(key);
        client().ifPresent(engine -> engine.sendFinalUtterance(utterance, false));
    }

    @Override
    public void onAcousticAbuse(AcousticAbuseEvent event) {
        if (closed) {
            return;
        }
        MicSpamConfig config = settings.micSpam();
        if (!config.enabled() || mutes.isMuted(event.playerId()) || !claimMicSpam(event.playerId(), config)) {
            return;
        }
        scheduler.runAsync(() -> handleMicSpam(event, config));
    }

    @Override
    public void onTranscript(IpcMessage.TranscriptResult result) {
        if (closed) {
            return;
        }
        UtteranceKey key = new UtteranceKey(result.playerId(), result.utteranceId());
        if (result.isVerificationResult()) {
            notifyDebug(result, WordlistMatchResult.EMPTY);
            scheduler.runAsync(() -> resolveVerification(key, result));
            return;
        }
        boolean blank = result.text().isBlank();
        WordlistMatchResult match = blank ? WordlistMatchResult.EMPTY : wordlist.match(result.text());
        notifyDebug(result, match);
        Instant now = clock.instant();
        if (result.isFinal() && !blank) {
            transcriptCount.incrementAndGet();
            transcripts.append(result.playerId(), match.hasMatches()
                    ? TranscriptEntry.flagged(now, result.text(), match.highestSeverity())
                    : TranscriptEntry.clean(now, result.text()));
        }
        DetectionPhase phase = result.isFinal() ? DetectionPhase.FINAL : DetectionPhase.LIVE;
        if (!match.hasMatches()) {
            if (result.isFinal()) {
                finalAudio.remove(key);
            }
            return;
        }
        short[] snapshot = audioFor(key);
        if (!claim(key, match.matchedWords(), phase)) {
            if (phase == DetectionPhase.FINAL) {
                Hit backup = new Hit(key, nameOf(result.playerId()), result.text(), phase, toMatches(match), snapshot, now);
                attachFinalBackup(backup);
            }
            return;
        }
        Hit hit = new Hit(key, nameOf(result.playerId()), result.text(), phase, toMatches(match), snapshot, now);
        scheduler.runAsync(() -> handleHit(hit));
    }

    public boolean toggleDebug(CommandSender viewer, UUID targetId) {
        Set<CommandSender> viewers = debugViewers.computeIfAbsent(targetId, id -> new CopyOnWriteArraySet<>());
        if (viewers.remove(viewer)) {
            debugViewers.computeIfPresent(targetId, (id, set) -> set.isEmpty() ? null : set);
            return false;
        }
        viewers.add(viewer);
        return true;
    }

    public boolean isDebugging(CommandSender viewer, UUID targetId) {
        Set<CommandSender> viewers = debugViewers.get(targetId);
        return viewers != null && viewers.contains(viewer);
    }

    public void removeViewer(UUID viewerId) {
        for (Map.Entry<UUID, Set<CommandSender>> entry : debugViewers.entrySet()) {
            entry.getValue().removeIf(viewer -> viewer instanceof Player player && player.getUniqueId().equals(viewerId));
        }
        debugViewers.values().removeIf(Set::isEmpty);
    }

    public void onPlayerQuit(UUID playerId) {
        services.moderation().clearSession(playerId);
        liveAudio.removeKeys(key -> key.playerId().equals(playerId) && !pending.containsKey(key));
        finalAudio.removeKeys(key -> key.playerId().equals(playerId) && !pending.containsKey(key));
        removeViewer(playerId);
        debugViewers.remove(playerId);
    }

    public void sweep() {
        if (closed) {
            return;
        }
        liveAudio.removeExpired();
        finalAudio.removeExpired();
        handled.removeExpired();
        retryOnFinal.removeExpired();
        Instant cooledDown = clock.instant().minus(settings.micSpam().cooldown());
        micSpamCooldowns.values().removeIf(last -> !last.isAfter(cooledDown));
        Instant hitCutoff = clock.instant().minus(settings.micSpam().window());
        micSpamHits.forEach((id, ignored) -> micSpamHits.computeIfPresent(id, (key, history) -> {
            while (!history.isEmpty() && !history.peekFirst().isAfter(hitCutoff)) {
                history.pollFirst();
            }
            return history.isEmpty() ? null : history;
        }));
        List<Map.Entry<UtteranceKey, Pending>> expiredEntries;
        synchronized (pending) {
            staleVerifications.removeExpired();
            expiredEntries = pending.removeExpired();
            for (Map.Entry<UtteranceKey, Pending> expired : expiredEntries) {
                staleVerifications.update(expired.getKey(), ignored -> 0, count -> count + 1);
            }
        }
        for (Map.Entry<UtteranceKey, Pending> expired : expiredEntries) {
            logger.fine("Double Check timed out for " + expired.getValue().hit.playerName() + "; reporting the hit as unconfirmed.");
            settleUnanswered(expired.getValue());
        }
    }

    public Status status() {
        EngineClient current = client;
        boolean connected = current != null && current.isConnected();
        return new Status(
                connected,
                connected ? current.engineProfile() : "",
                connected ? current.engineVersion() : "",
                connected ? current.averageLatencyMs() : -1,
                settings.doubleCheck(),
                liveAudio.size(),
                finalAudio.size(),
                pending.size(),
                transcriptCount.get(),
                flagCount.get(),
                actionCount.get(),
                unconfirmedCount.get(),
                debugViewers.size());
    }

    @Override
    public void close() {
        closed = true;
        synchronized (tasks) {
            tasks.forEach(TaskHandle::cancel);
            tasks.clear();
        }
        client = null;
        liveAudio.drain();
        finalAudio.drain();
        handled.drain();
        retryOnFinal.drain();
        synchronized (pending) {
            pending.drain();
            staleVerifications.drain();
        }
        debugViewers.clear();
        micSpamCooldowns.clear();
        micSpamHits.clear();
    }

    private boolean claimMicSpam(UUID playerId, MicSpamConfig config) {
        Instant now = clock.instant();
        AtomicBoolean fresh = new AtomicBoolean();
        Duration cooldown = config.cooldown().minusMillis(config.minDurationMs());
        micSpamCooldowns.compute(playerId, (id, last) -> {
            if (last != null && now.isBefore(last.plus(cooldown))) {
                return last;
            }
            fresh.set(true);
            return now;
        });
        return fresh.get();
    }

    private int recordMicSpamHit(UUID playerId, MicSpamConfig config) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(config.window());
        int[] count = new int[1];
        micSpamHits.compute(playerId, (id, history) -> {
            Deque<Instant> hits = history != null ? history : new ArrayDeque<>();
            while (!hits.isEmpty() && !hits.peekFirst().isAfter(cutoff)) {
                hits.pollFirst();
            }
            hits.addLast(now);
            count[0] = hits.size();
            return hits;
        });
        return count[0];
    }

    private void handleMicSpam(AcousticAbuseEvent event, MicSpamConfig config) {
        if (closed || mutes.isMuted(event.playerId())) {
            return;
        }
        Services current = services;
        UUID playerId = event.playerId();
        String playerName = nameOf(playerId);
        int hitNumber = recordMicSpamHit(playerId, config);
        VoiceAction action = config.actionForHit(hitNumber);
        flagCount.incrementAndGet();
        actionCount.incrementAndGet();
        Map<String, String> values = Map.of(
                "player", playerName,
                "kind", event.kind().label(),
                "peak", percent(AcousticAbuseEvent.peakLevel(event.peakRms())),
                "clipping", percent(event.clippingRatio()),
                "duration", String.format(Locale.ROOT, "%.1fs", event.durationMs() / 1000.0),
                "action", action.name().toLowerCase(Locale.ROOT),
                "server", settings.serverId());
        alertStaff(MessageKey.ALERTS_MIC_SPAM, values);
        logger.info(messages.plain(MessageKey.ALERTS_MIC_SPAM, values).replaceAll("\\s+", " ").trim());
        switch (action) {
            case REPORT -> {
            }
            case WARN -> tellPlayer(playerId, MessageKey.MIC_SPAM_WARNING, Map.of("player", playerName));
            case MUTE -> autoMute(playerId, playerName, config.muteDuration(), MIC_SPAM_MUTE_REASON, "", event.pcm16k(), current, List.of());
        }
        if (config.notifyDiscord()) {
            send(current.discord().sendMicSpam(MicSpamWebhookPayload.of(event, playerName, action, clock.instant())), "mic spam");
        }
    }

    private static String percent(double ratio) {
        return Math.round(Math.max(0, Math.min(1, ratio)) * 100) + "%";
    }

    private boolean claim(UtteranceKey key, List<String> words, DetectionPhase phase) {
        AtomicBoolean fresh = new AtomicBoolean();
        handled.update(key, ignored -> new LinkedHashSet<>(), seen -> {
            Set<String> next = new LinkedHashSet<>(seen);
            fresh.set(next.addAll(words));
            return next;
        });
        if (phase == DetectionPhase.FINAL) {
            Set<String> retry = retryOnFinal.remove(key);
            if (retry != null && words.stream().anyMatch(retry::contains)) {
                return true;
            }
        }
        return fresh.get();
    }

    private void unclaim(UtteranceKey key, List<String> words) {
        handled.update(key, ignored -> new LinkedHashSet<>(), seen -> {
            Set<String> next = new LinkedHashSet<>(seen);
            words.forEach(next::remove);
            return next;
        });
    }

    private void attachFinalBackup(Hit backup) {
        boolean dispatchNow = false;
        synchronized (pending) {
            Pending inFlight = pending.get(backup.key());
            if (inFlight != null && inFlight.hit.phase() == DetectionPhase.LIVE) {
                inFlight.finalBackup = backup;
                return;
            }
            Set<String> retry = retryOnFinal.remove(backup.key());
            if (retry != null && backup.words().stream().anyMatch(retry::contains)) {
                dispatchNow = true;
            }
        }
        if (dispatchNow) {
            scheduler.runAsync(() -> handleHit(backup));
        }
    }

    private void handleHit(Hit hit) {
        if (!settings.doubleCheck()) {
            adjudicate(hit, ConfirmationState.UNCHECKED);
            return;
        }
        Optional<EngineClient> engine = client();
        if (engine.isEmpty()) {
            settleUnverifiable(hit);
            return;
        }
        requestVerification(engine.get(), hit);
    }

    private void requestVerification(EngineClient engine, Hit hit) {
        short[] pcm = hit.pcm().length > 0 ? hit.pcm() : audioFor(hit.key());
        Hit resolvedHit = pcm.length > 0 && hit.pcm().length == 0 ? hit.withPcm(pcm) : hit;
        synchronized (pending) {
            Pending inFlight = pending.get(resolvedHit.key());
            if (inFlight != null) {
                inFlight.parked = inFlight.parked == null ? resolvedHit : merge(inFlight.parked, resolvedHit);
                return;
            }
            if (pcm.length > 0) {
                pending.put(resolvedHit.key(), new Pending(resolvedHit));
            }
        }
        if (pcm.length == 0) {
            settleUnverifiable(resolvedHit);
            return;
        }
        if (!engine.sendVerifyRequest(resolvedHit.key().playerId(), resolvedHit.key().utteranceId(),
                String.join(" ", resolvedHit.words()), pcm)) {
            Pending removed;
            synchronized (pending) {
                removed = pending.remove(resolvedHit.key());
            }
            if (removed != null) {
                settleUnverifiable(resolvedHit);
                followUp(removed, false);
            }
        }
    }

    private void resolveVerification(UtteranceKey key, IpcMessage.TranscriptResult result) {
        Pending resolved;
        synchronized (pending) {
            Integer stale = staleVerifications.get(key);
            if (stale != null && stale > 0) {
                if (stale == 1) {
                    staleVerifications.remove(key);
                } else {
                    staleVerifications.put(key, stale - 1);
                }
                return;
            }
            resolved = pending.remove(key);
        }
        if (resolved == null) {
            return;
        }
        boolean confirmed;
        if (result.confirmationState() == ConfirmationState.CONFIRMED) {
            confirmed = true;
            adjudicate(resolved.hit, ConfirmationState.CONFIRMED);
        } else {
            List<MatchedWord> corroborated = corroboratedMatches(resolved.hit, result.text());
            if (!corroborated.isEmpty()) {
                confirmed = true;
                adjudicate(resolved.hit.withMatches(corroborated), ConfirmationState.CONFIRMED);
            } else {
                confirmed = false;
                settleUnverifiable(resolved.hit);
            }
        }
        followUp(resolved, confirmed);
    }

    private List<MatchedWord> corroboratedMatches(Hit hit, String verifierText) {
        if (verifierText == null || verifierText.isBlank()) {
            return List.of();
        }
        WordlistMatchResult verified = wordlist.match(verifierText);
        if (!verified.hasMatches()) {
            return List.of();
        }
        Set<String> verifiedWords = new LinkedHashSet<>();
        for (String word : verified.matchedWords()) {
            verifiedWords.add(word.toLowerCase(Locale.ROOT));
        }
        List<MatchedWord> corroborated = new ArrayList<>();
        for (MatchedWord match : hit.matches()) {
            String primaryWord = match.word().toLowerCase(Locale.ROOT);
            if (verifiedWords.contains(primaryWord) || matchesVariant(primaryWord, verifiedWords)) {
                corroborated.add(match);
            }
        }
        return corroborated;
    }

    private static boolean matchesVariant(String primaryWord, Set<String> verifiedWords) {
        String primaryStem = nonRhoticStem(primaryWord);
        if (primaryStem == null) {
            return false;
        }
        for (String verifiedWord : verifiedWords) {
            if (primaryStem.equals(nonRhoticStem(verifiedWord))) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String nonRhoticStem(String word) {
        String base = word.endsWith("s") || word.endsWith("z") ? word.substring(0, word.length() - 1) : word;
        String stem;
        if (base.endsWith("er")) {
            stem = base.substring(0, base.length() - 2);
        } else if (base.endsWith("ah")) {
            stem = base.substring(0, base.length() - 2);
        } else if (base.endsWith("a")) {
            stem = base.substring(0, base.length() - 1);
        } else {
            return null;
        }
        return stem.length() >= 4 ? stem : null;
    }

    private void followUp(Pending resolved, boolean confirmed) {
        Hit next;
        synchronized (pending) {
            Hit parked = resolved.parked;
            Hit backup = confirmed ? null : resolved.finalBackup;
            if (backup != null) {
                retryOnFinal.remove(backup.key());
                next = parked == null ? backup : merge(backup, parked);
            } else {
                next = parked;
            }
        }
        if (next != null) {
            handleHit(next);
        }
    }

    private void flushPending() {
        List<Map.Entry<UtteranceKey, Pending>> unanswered;
        synchronized (pending) {
            unanswered = pending.drain();
            staleVerifications.drain();
        }
        for (Map.Entry<UtteranceKey, Pending> entry : unanswered) {
            scheduler.runAsync(() -> settleUnanswered(entry.getValue()));
        }
    }

    private void settleUnanswered(Pending unanswered) {
        settleUnverifiable(unanswered.hit);
        Hit parked = unanswered.parked;
        if (parked != null) {
            settleUnverifiable(parked);
        }
        Hit backup = unanswered.finalBackup;
        if (backup != null) {
            settleUnverifiable(backup);
        }
    }

    private void settleUnverifiable(Hit hit) {
        if (hit.phase() == DetectionPhase.LIVE) {
            retryOnFinal.update(hit.key(), ignored -> new LinkedHashSet<>(), words -> {
                Set<String> next = new LinkedHashSet<>(words);
                next.addAll(hit.words());
                return next;
            });
        }
        adjudicate(hit, ConfirmationState.UNCONFIRMED);
    }

    private static Hit merge(Hit first, Hit second) {
        Map<String, MatchedWord> words = new LinkedHashMap<>();
        for (MatchedWord word : first.matches()) {
            words.put(word.word(), word);
        }
        for (MatchedWord word : second.matches()) {
            words.merge(word.word(), word, (a, b) -> a.severity().isAtLeast(b.severity()) ? a : b);
        }
        String transcript = second.transcript().length() >= first.transcript().length() ? second.transcript() : first.transcript();
        DetectionPhase phase = first.phase() == DetectionPhase.FINAL || second.phase() == DetectionPhase.FINAL
                ? DetectionPhase.FINAL : DetectionPhase.LIVE;
        short[] audio = second.pcm().length >= first.pcm().length ? second.pcm() : first.pcm();
        Instant detectedAt = first.detectedAt().isBefore(second.detectedAt()) ? first.detectedAt() : second.detectedAt();
        return new Hit(second.key(), second.playerName(), transcript, phase, List.copyOf(words.values()), audio, detectedAt);
    }

    private void adjudicate(Hit hit, ConfirmationState state) {
        Detection detection = new Detection(hit.key().playerId(), hit.playerName(), Long.toString(hit.key().utteranceId()),
                hit.phase(), hit.transcript(), hit.matches(), state);
        flagCount.incrementAndGet();
        Runnable continuation = () -> scheduler.runAsync(() -> act(hit, detection));
        Optional<Player> online = players.online(hit.key().playerId());
        if (online.isPresent()) {
            Player player = online.get();
            AtomicBoolean fired = new AtomicBoolean();
            Runnable retired = () -> {
                if (fired.compareAndSet(false, true)) {
                    scheduler.runAsync(() -> {
                        if (fireFlagEvent(detection, true)) {
                            act(hit, detection);
                        }
                    });
                }
            };
            TaskHandle handle = scheduler.runForEntity(player, () -> {
                if (fired.compareAndSet(false, true) && fireFlagEvent(detection, !primaryThread.getAsBoolean())) {
                    continuation.run();
                }
            }, retired);
            if (handle != TaskHandle.NONE || fired.get()) {
                return;
            }
            if (fired.compareAndSet(false, true) && fireFlagEvent(detection, true)) {
                act(hit, detection);
            }
            return;
        }
        if (fireFlagEvent(detection, true)) {
            act(hit, detection);
        }
    }

    private boolean fireFlagEvent(Detection detection, boolean async) {
        VoiceFlagEvent event = new VoiceFlagEvent(async, detection.playerId(), detection.playerName(), detection.transcript(),
                detection.matchedWords(), detection.highestSeverity().orElse(FlagSeverity.MILD), detection.confirmation(),
                detection.phase() == DetectionPhase.LIVE);
        try {
            callEvent.accept(event);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "A VoiceFlagEvent listener failed; moderating " + detection.playerName() + " anyway", e);
        }
        return !event.isCancelled();
    }

    private void act(Hit hit, Detection detection) {
        UtteranceKey key = hit.key();
        Services current = services;
        ModerationDecision decision;
        try {
            decision = current.moderation().evaluate(detection, clock.instant());
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not moderate speech from " + detection.playerName(), e);
            return;
        }
        if (decision.shouldDropActiveUtterance()) {
            dropUtterance(key);
        }
        switch (decision.outcome()) {
            case IGNORED, DUPLICATE -> {
            }
            case UNCONFIRMED -> reportUnconfirmed(hit, decision, current);
            case ACTED -> enforce(hit, decision, current);
        }
    }

    private void reportUnconfirmed(Hit hit, ModerationDecision decision, Services current) {
        unconfirmedCount.incrementAndGet();
        Detection detection = decision.detection();
        if (decision.staffAlertUnconfirmed()) {
            alertStaff(MessageKey.ALERTS_FLAGGED_UNCONFIRMED, alertValues(decision));
        }
        if (decision.discordAlertUnconfirmed()) {
            send(current.discord().sendFlag(flagPayload(hit, decision, ConfirmationState.UNCONFIRMED, null, nearbyConversation(hit))), "flag");
        }
        logger.info("[unconfirmed] " + detection.playerName() + " may have said " + String.join(", ", detection.matchedWords())
                + ". No action taken.");
    }

    private void enforce(Hit hit, ModerationDecision decision, Services current) {
        actionCount.incrementAndGet();
        Detection detection = decision.detection();
        UUID playerId = detection.playerId();
        FlagSeverity severity = decision.severity().orElse(FlagSeverity.MILD);
        strikeCounts.put(playerId, decision.strikeCount());
        if (decision.strikeRecorded()) {
            asyncEvents.callAsync(new VoiceStrikeEvent(playerId, detection.playerName(), severity, decision.strikeCount(),
                    current.moderation().settings().flagThreshold(), decision.action().orElse(VoiceAction.REPORT),
                    detection.transcript(), detection.matchedWords()));
        }
        List<ConversationLine> context = decision.mute() || decision.discordAlert() ? nearbyConversation(hit) : List.of();
        Map<String, String> values = alertValues(decision);
        if (decision.staffAlert()) {
            alertStaff(MessageKey.ALERTS_FLAGGED, values);
            logger.info(messages.plain(MessageKey.ALERTS_FLAGGED, values).replaceAll("\\s+", " ").trim());
        }
        if (decision.warn()) {
            tellPlayer(playerId, MessageKey.WARN_MESSAGE, Map.of("words", values.get("words"), "player", detection.playerName()));
        }
        if (decision.mute()) {
            mute(hit, decision, current, context);
        }
        for (String command : decision.commandPlan().commands()) {
            scheduler.runGlobal(() -> {
                try {
                    consoleCommand.accept(command);
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Custom command failed: " + command, e);
                }
            });
        }
        if (decision.discordAlert()) {
            send(current.discord().sendFlag(flagPayload(hit, decision, detection.confirmation(), decision.action().orElse(null), context)), "flag");
        }
    }

    private void mute(Hit hit, ModerationDecision decision, Services current, List<ConversationLine> context) {
        Detection detection = decision.detection();
        short[] pcm = hit.pcm().length > 0 ? hit.pcm() : audioFor(hit.key());
        autoMute(detection.playerId(), detection.playerName(), decision.muteDuration().orElse(null), AUTO_MUTE_REASON,
                detection.transcript(), pcm, current, context);
    }

    private void autoMute(
            UUID playerId,
            String playerName,
            @Nullable Duration duration,
            String reason,
            String transcript,
            short[] pcm,
            Services current,
            List<ConversationLine> context) {
        mutes.mutePlayer(playerId, playerName, duration, reason, AUTO_MODERATOR, true)
                .whenComplete((record, error) -> {
                    if (record != null) {
                        sendMuteWebhook(current, record, transcript, pcm, context);
                    }
                });
        alertStaff(MessageKey.ALERTS_MUTED, Map.of(
                "player", playerName,
                "duration", DurationParser.format(Optional.ofNullable(duration)),
                "server", settings.serverId()));
        String appealUrl = settings.appealUrl().trim();
        if (!appealUrl.isEmpty()) {
            tellPlayer(playerId, MessageKey.APPEAL_MESSAGE, Map.of("appeal_url", appealUrl));
        }
    }

    private void sendMuteWebhook(Services current, VoiceMuteRecord record, String transcript, short[] pcm, List<ConversationLine> context) {
        send(current.discord().sendMute(MuteWebhookPayload.muted(record, transcript, pcm.length == 0 ? null : pcm, clock.instant(), context)),
                "mute");
    }

    private FlagWebhookPayload flagPayload(
            Hit hit, ModerationDecision decision, ConfirmationState state, @Nullable VoiceAction action, List<ConversationLine> context) {
        Detection detection = decision.detection();
        short[] pcm = hit.pcm().length > 0 ? hit.pcm() : audioFor(hit.key());
        return new FlagWebhookPayload(detection.playerId(), detection.playerName(), decision.severity().orElse(FlagSeverity.MILD),
                state, detection.matchedWords(), detection.transcript(), action, pcm.length == 0 ? null : pcm, clock.instant(), context);
    }

    private List<ConversationLine> nearbyConversation(Hit hit) {
        return speakers.recentConversation(hit.key().playerId(), hit.detectedAt(), clock.instant());
    }

    private void send(CompletableFuture<?> future, String kind) {
        future.whenComplete((result, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Could not send the Discord " + kind + " webhook", error);
            }
        });
    }

    private Map<String, String> alertValues(ModerationDecision decision) {
        Detection detection = decision.detection();
        return Map.of(
                "player", detection.playerName(),
                "words", String.join(", ", detection.matchedWords()),
                "severity", decision.severity().map(value -> value.name().toLowerCase(Locale.ROOT)).orElse("none"),
                "action", decision.action().map(value -> value.name().toLowerCase(Locale.ROOT)).orElse("none"),
                "strikes", Integer.toString(decision.strikeCount()),
                "transcript", detection.transcript(),
                "server", settings.serverId());
    }

    private void alertStaff(MessageKey key, Map<String, String> values) {
        for (Player staff : players.onlinePlayers()) {
            scheduler.runForEntity(staff, () -> {
                if (staff.hasPermission(ALERTS_PERMISSION)) {
                    staff.sendMessage(messages.render(key, staff, values));
                }
            });
        }
    }

    private void tellPlayer(UUID playerId, MessageKey key, Map<String, String> values) {
        players.online(playerId).ifPresent(player -> scheduler.runForEntity(player,
                () -> player.sendMessage(messages.render(key, player, values))));
    }

    private void dropUtterance(UtteranceKey key) {
        ShardedAudioPipeline pipeline = audio;
        if (pipeline != null) {
            pipeline.dropUtterance(key.playerId(), key.utteranceId());
        }
        client().ifPresent(engine -> engine.sendDropUtterance(key.playerId(), key.utteranceId()));
    }

    private short[] audioFor(UtteranceKey key) {
        short[] finished = finalAudio.get(key);
        if (finished != null) {
            return finished;
        }
        PcmBuffer live = liveAudio.get(key);
        return live == null ? new short[0] : live.snapshot();
    }

    private void notifyDebug(IpcMessage.TranscriptResult result, WordlistMatchResult match) {
        Set<CommandSender> viewers = debugViewers.get(result.playerId());
        if (viewers == null || viewers.isEmpty()) {
            return;
        }
        String kind;
        String template;
        if (result.isVerificationResult()) {
            kind = "verify " + result.confirmationState().name().toLowerCase(Locale.ROOT);
            template = result.confirmationState() == ConfirmationState.CONFIRMED
                    ? DEBUG_VERIFY_CONFIRMED_TEMPLATE
                    : DEBUG_VERIFY_UNCONFIRMED_TEMPLATE;
        } else {
            kind = result.isFinal() ? "final" : "live";
            template = DEBUG_TEMPLATE;
        }
        Map<String, String> values = Map.of(
                "kind", kind + " " + result.latencyMs() + "ms",
                "player", nameOf(result.playerId()),
                "text", result.text().isBlank() ? "(silence)" : result.text(),
                "words", match.hasMatches() ? "matched: " + String.join(", ", match.matchedWords()) : "");
        for (CommandSender viewer : viewers) {
            if (viewer instanceof Player player) {
                scheduler.runForEntity(player, () -> player.sendMessage(debugLine(template, player, values)));
            } else {
                viewer.sendMessage(debugLine(template, null, values));
            }
        }
    }

    private Component debugLine(String template, @Nullable Player viewer, Map<String, String> values) {
        return messages.renderTemplate(template, viewer, values);
    }

    private String nameOf(UUID playerId) {
        return players.nameOf(playerId).orElse(playerId.toString());
    }

    private static List<MatchedWord> toMatches(WordlistMatchResult match) {
        Map<String, MatchedWord> words = new HashMap<>();
        for (WordMatch hit : match.matches()) {
            words.merge(hit.word(), new MatchedWord(hit.word(), hit.severity()),
                    (a, b) -> a.severity().isAtLeast(b.severity()) ? a : b);
        }
        List<MatchedWord> ordered = new ArrayList<>();
        for (String word : match.matchedWords()) {
            ordered.add(words.get(word));
        }
        return ordered;
    }
}
