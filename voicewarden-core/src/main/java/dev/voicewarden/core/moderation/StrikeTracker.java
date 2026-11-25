package dev.voicewarden.core.moderation;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class StrikeTracker {

    public static final int DEFAULT_TRACKED_UTTERANCES = 4096;
    public static final Duration DEFAULT_UTTERANCE_TTL = Duration.ofMinutes(5);

    public enum AdmissionKind {
        NEW,
        ESCALATION,
        DUPLICATE
    }

    public record Admission(AdmissionKind kind, Optional<UtteranceVerdict> prior) {

        public Admission {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(prior, "prior");
        }
    }

    private record UtteranceKey(UUID playerId, String utteranceId) {
    }

    private record Remembered(UtteranceVerdict verdict, Instant at) {
    }

    private final StrikePersistence persistence;
    private final Duration window;
    private final Duration utteranceTtl;
    private final Map<UtteranceKey, Remembered> verdicts;

    public StrikeTracker(StrikePersistence persistence, Duration window) {
        this(persistence, window, DEFAULT_TRACKED_UTTERANCES, DEFAULT_UTTERANCE_TTL);
    }

    public StrikeTracker(StrikePersistence persistence, Duration window, int maxTrackedUtterances, Duration utteranceTtl) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        if (!window.isPositive() || !utteranceTtl.isPositive() || maxTrackedUtterances < 1) {
            throw new IllegalArgumentException("window, utteranceTtl and maxTrackedUtterances must be positive");
        }
        this.window = window;
        this.utteranceTtl = utteranceTtl;
        this.verdicts = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UtteranceKey, Remembered> eldest) {
                return size() > maxTrackedUtterances;
            }
        };
    }

    public Duration window() {
        return window;
    }

    public Admission admit(UUID playerId, String utteranceId, FlagSeverity severity, boolean confirmed, Instant now) {
        Optional<UtteranceVerdict> prior = verdict(playerId, utteranceId, now);
        return new Admission(classify(prior, severity, confirmed), prior);
    }

    static AdmissionKind classify(Optional<UtteranceVerdict> prior, FlagSeverity severity, boolean confirmed) {
        if (prior.isEmpty()) {
            return AdmissionKind.NEW;
        }
        UtteranceVerdict previous = prior.get();
        boolean higher = severity.compareTo(previous.severity()) > 0;
        if (!previous.confirmed()) {
            return confirmed || higher ? AdmissionKind.NEW : AdmissionKind.DUPLICATE;
        }
        if (!confirmed || !higher || previous.action().orElse(null) == VoiceAction.MUTE) {
            return AdmissionKind.DUPLICATE;
        }
        return AdmissionKind.ESCALATION;
    }

    public Optional<UtteranceVerdict> verdict(UUID playerId, String utteranceId, Instant now) {
        UtteranceKey key = new UtteranceKey(playerId, utteranceId);
        synchronized (verdicts) {
            Remembered remembered = verdicts.get(key);
            if (remembered == null) {
                return Optional.empty();
            }
            if (remembered.at().plus(utteranceTtl).isBefore(now)) {
                verdicts.remove(key);
                return Optional.empty();
            }
            return Optional.of(remembered.verdict());
        }
    }

    public void remember(UUID playerId, String utteranceId, UtteranceVerdict verdict, Instant now) {
        synchronized (verdicts) {
            verdicts.put(new UtteranceKey(playerId, utteranceId), new Remembered(verdict, now));
        }
    }

    public int recordStrike(UUID playerId, String playerName, FlagSeverity severity, List<String> matchedWords, String transcript, Instant now) {
        persistence.recordStrike(playerId, playerName, severity, List.copyOf(matchedWords), transcript, now);
        return strikesInWindow(playerId, now);
    }

    public int strikesInWindow(UUID playerId, Instant now) {
        return persistence.countRecentStrikes(playerId, now.minus(window));
    }

    public int mutesSince(UUID playerId, Instant since) {
        return persistence.countRecentMutes(playerId, since);
    }

    public void pardon(UUID playerId) {
        persistence.pardonStrikes(playerId);
    }

    int trackedUtterances() {
        synchronized (verdicts) {
            return verdicts.size();
        }
    }
}
