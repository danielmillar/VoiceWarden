package dev.voicewarden.plugin.voice;

import java.util.UUID;

public interface VoicePacketGate {

    boolean isMuted(UUID playerId);

    boolean hasBypass(UUID playerId);

    boolean hasConsentForMonitoring(UUID playerId);

    boolean isGroupAllowedWhileMuted(String groupName);

    void onMutedSpeakAttempt(UUID playerId);

    default void onUnconsentedSpeakAttempt(UUID playerId) {
    }
}
