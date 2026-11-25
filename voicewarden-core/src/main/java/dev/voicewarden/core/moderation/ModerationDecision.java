package dev.voicewarden.core.moderation;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ModerationDecision(
        Outcome outcome,
        Detection detection,
        Optional<FlagSeverity> severity,
        Optional<VoiceAction> action,
        boolean strikeRecorded,
        int strikeCount,
        boolean warn,
        boolean mute,
        Optional<Duration> muteDuration,
        CustomCommandPlan commandPlan,
        boolean staffAlert,
        boolean staffAlertUnconfirmed,
        boolean discordAlert,
        boolean discordAlertUnconfirmed,
        boolean shouldDropActiveUtterance) {

    public enum Outcome {
        IGNORED,
        DUPLICATE,
        UNCONFIRMED,
        ACTED
    }

    public ModerationDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detection, "detection");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(muteDuration, "muteDuration");
        Objects.requireNonNull(commandPlan, "commandPlan");
    }

    static ModerationDecision ignored(Detection detection) {
        return passive(Outcome.IGNORED, detection, Optional.empty(), 0, false);
    }

    static ModerationDecision duplicate(Detection detection, FlagSeverity severity, int strikes) {
        return passive(Outcome.DUPLICATE, detection, Optional.of(severity), strikes, false);
    }

    static ModerationDecision unconfirmed(Detection detection, FlagSeverity severity, int strikes) {
        return passive(Outcome.UNCONFIRMED, detection, Optional.of(severity), strikes, true);
    }

    private static ModerationDecision passive(
            Outcome outcome, Detection detection, Optional<FlagSeverity> severity, int strikes, boolean alertUnconfirmed) {
        return new ModerationDecision(outcome, detection, severity, Optional.empty(), false, strikes, false, false,
                Optional.empty(), CustomCommandPlan.empty(), false, alertUnconfirmed, false, alertUnconfirmed, false);
    }

    public List<String> matchedWords() {
        return detection.matchedWords();
    }

    public boolean acted() {
        return outcome == Outcome.ACTED;
    }
}
