package dev.voicewarden.plugin.consent;

import java.util.UUID;

public interface ConsentView {

    boolean isMonitoringAllowed(UUID playerId);

    String placeholderStatus(UUID playerId);

    default void onUnconsentedSpeakAttempt(UUID playerId) {
    }
}
