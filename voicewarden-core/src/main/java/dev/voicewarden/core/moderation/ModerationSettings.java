package dev.voicewarden.core.moderation;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ModerationSettings(
        int flagThreshold,
        Duration window,
        Map<FlagSeverity, List<VoiceAction>> escalation,
        boolean requireConfirmation,
        boolean autoWarnBuiltin,
        boolean autoMuteBuiltin) {

    public ModerationSettings(
            int flagThreshold,
            Duration window,
            Map<FlagSeverity, List<VoiceAction>> escalation,
            boolean requireConfirmation,
            boolean autoMuteBuiltin) {
        this(flagThreshold, window, escalation, requireConfirmation, true, autoMuteBuiltin);
    }

    public ModerationSettings {
        flagThreshold = Math.max(1, flagThreshold);
        Objects.requireNonNull(window, "window");
        if (!window.isPositive()) {
            throw new IllegalArgumentException("window must be positive");
        }
        EnumMap<FlagSeverity, List<VoiceAction>> copy = new EnumMap<>(FlagSeverity.class);
        for (FlagSeverity severity : FlagSeverity.values()) {
            copy.put(severity, List.copyOf(escalation.getOrDefault(severity, List.of())));
        }
        escalation = Collections.unmodifiableMap(copy);
    }

    public Optional<VoiceAction> actionFor(FlagSeverity severity, int strikes) {
        List<VoiceAction> ladder = escalation.get(severity);
        if (ladder.isEmpty()) {
            return Optional.empty();
        }
        int step = Math.min(Math.max(0, strikes) / flagThreshold, ladder.size() - 1);
        return Optional.of(ladder.get(step));
    }
}
