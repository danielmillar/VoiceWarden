package dev.voicewarden.core.moderation;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import java.util.Objects;
import java.util.Optional;

public record UtteranceVerdict(FlagSeverity severity, boolean confirmed, Optional<VoiceAction> action) {

    public UtteranceVerdict {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(action, "action");
        if (!confirmed && action.isPresent()) {
            throw new IllegalArgumentException("An unconfirmed verdict cannot carry an action");
        }
    }

    public static UtteranceVerdict unconfirmed(FlagSeverity severity) {
        return new UtteranceVerdict(severity, false, Optional.empty());
    }

    public static UtteranceVerdict confirmed(FlagSeverity severity, Optional<VoiceAction> action) {
        return new UtteranceVerdict(severity, true, action);
    }
}
