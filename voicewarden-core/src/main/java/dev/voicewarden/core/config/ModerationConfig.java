package dev.voicewarden.core.config;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModerationConfig(
        int flagThreshold,
        int windowMinutes,
        boolean networkStrikes,
        Map<FlagSeverity, List<VoiceAction>> escalation,
        String appealUrl,
        boolean autoWarnBuiltin,
        boolean autoMuteBuiltin) {

    public ModerationConfig(
            int flagThreshold,
            int windowMinutes,
            boolean networkStrikes,
            Map<FlagSeverity, List<VoiceAction>> escalation,
            String appealUrl,
            boolean autoMuteBuiltin) {
        this(flagThreshold, windowMinutes, networkStrikes, escalation, appealUrl, true, autoMuteBuiltin);
    }

    public ModerationConfig {
        EnumMap<FlagSeverity, List<VoiceAction>> copy = new EnumMap<>(FlagSeverity.class);
        escalation.forEach((severity, actions) -> copy.put(severity, List.copyOf(actions)));
        escalation = Collections.unmodifiableMap(copy);
        Objects.requireNonNull(appealUrl, "appealUrl");
    }
}
