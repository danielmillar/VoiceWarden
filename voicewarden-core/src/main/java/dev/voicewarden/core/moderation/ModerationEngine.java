package dev.voicewarden.core.moderation;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class ModerationEngine {

    private static final int LOCK_STRIPES = 64;

    private final ModerationSettings settings;
    private final MuteLadder ladder;
    private final StrikeTracker tracker;
    private final CustomCommandPlanner commands;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public ModerationEngine(ModerationSettings settings, MuteLadder ladder, StrikeTracker tracker, CustomCommandPlanner commands) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ladder = Objects.requireNonNull(ladder, "ladder");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.commands = Objects.requireNonNull(commands, "commands");
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public static ModerationEngine create(
            ModerationSettings settings,
            MuteLadderSettings ladder,
            CustomCommandSettings commands,
            StrikePersistence persistence) {
        return new ModerationEngine(settings, new MuteLadder(ladder), new StrikeTracker(persistence, settings.window()),
                new CustomCommandPlanner(commands));
    }

    public ModerationSettings settings() {
        return settings;
    }

    public StrikeTracker strikes() {
        return tracker;
    }

    public ModerationDecision evaluate(Detection detection, Instant now) {
        Optional<FlagSeverity> highest = detection.highestSeverity();
        if (highest.isEmpty()) {
            return ModerationDecision.ignored(detection);
        }
        FlagSeverity severity = highest.get();
        boolean confirmed = isConfirmed(detection.confirmation());
        UUID playerId = detection.playerId();
        ReentrantLock lock = lockFor(playerId);
        lock.lock();
        try {
            StrikeTracker.Admission admission = tracker.admit(playerId, detection.utteranceId(), severity, confirmed, now);
            if (admission.kind() == StrikeTracker.AdmissionKind.DUPLICATE) {
                return ModerationDecision.duplicate(detection, severity, tracker.strikesInWindow(playerId, now));
            }
            if (!confirmed) {
                tracker.remember(playerId, detection.utteranceId(), UtteranceVerdict.unconfirmed(severity), now);
                return ModerationDecision.unconfirmed(detection, severity, tracker.strikesInWindow(playerId, now));
            }
            return act(detection, severity, admission, now);
        } finally {
            lock.unlock();
        }
    }

    public void clearSession(UUID playerId) {
        ReentrantLock lock = lockFor(playerId);
        lock.lock();
        try {
            commands.clearSession(playerId);
        } finally {
            lock.unlock();
        }
    }

    public void pardon(UUID playerId) {
        ReentrantLock lock = lockFor(playerId);
        lock.lock();
        try {
            tracker.pardon(playerId);
        } finally {
            lock.unlock();
        }
    }

    private ModerationDecision act(Detection detection, FlagSeverity severity, StrikeTracker.Admission admission, Instant now) {
        UUID playerId = detection.playerId();
        boolean escalation = admission.kind() == StrikeTracker.AdmissionKind.ESCALATION;
        List<String> words = detection.matchedWords();
        int strikes = escalation
                ? tracker.strikesInWindow(playerId, now)
                : tracker.recordStrike(playerId, detection.playerName(), severity, words, detection.transcript(), now);
        Optional<VoiceAction> action = settings.actionFor(severity, strikes);
        if (escalation) {
            Optional<VoiceAction> previous = admission.prior().flatMap(UtteranceVerdict::action);
            if (rank(action) <= rank(previous)) {
                tracker.remember(playerId, detection.utteranceId(), UtteranceVerdict.confirmed(severity, previous), now);
                return ModerationDecision.duplicate(detection, severity, strikes);
            }
        }
        tracker.remember(playerId, detection.utteranceId(), UtteranceVerdict.confirmed(severity, action), now);

        CustomCommandPlan plan = commands.plan(playerId, detection.playerName(), words, detection.transcript(), action, !escalation);
        boolean isMute = action.orElse(null) == VoiceAction.MUTE;
        Optional<Duration> muteDuration = isMute
                ? Optional.of(ladder.durationFor(tracker.mutesSince(playerId, ladder.ladderWindowStart(now))))
                : Optional.empty();
        return new ModerationDecision(
                ModerationDecision.Outcome.ACTED,
                detection,
                Optional.of(severity),
                action,
                !escalation,
                strikes,
                action.orElse(null) == VoiceAction.WARN && settings.autoWarnBuiltin() && builtinWarnAllowed(plan),
                isMute && settings.autoMuteBuiltin() && builtinMuteAllowed(plan),
                muteDuration,
                plan,
                true,
                false,
                true,
                false,
                isMute && detection.phase() == DetectionPhase.LIVE);
    }

    private boolean isConfirmed(ConfirmationState state) {
        return state != ConfirmationState.UNCONFIRMED || !settings.requireConfirmation();
    }

    private boolean builtinMuteAllowed(CustomCommandPlan plan) {
        CustomCommandSettings toggles = commands.settings();
        return !(plan.keywordCommandsRan() && !toggles.builtinOnKeywordCommand())
                && !(plan.muteCommandsRan() && !toggles.builtinOnMuteCommand());
    }

    private boolean builtinWarnAllowed(CustomCommandPlan plan) {
        CustomCommandSettings toggles = commands.settings();
        return !(plan.keywordCommandsRan() && !toggles.builtinOnKeywordCommand())
                && !(plan.profanityCommandsRan() && !toggles.builtinOnProfanityCommand());
    }

    private static int rank(Optional<VoiceAction> action) {
        return action.map(value -> value.ordinal() + 1).orElse(0);
    }

    private ReentrantLock lockFor(UUID playerId) {
        return locks[Math.floorMod(playerId.hashCode(), LOCK_STRIPES)];
    }
}
