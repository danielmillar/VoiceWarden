package dev.voicewarden.plugin.voice;

import dev.voicewarden.core.config.MutesConfig;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public final class VoicePacketPolicy {

    public enum Verdict {
        BLOCK,
        BLOCK_NO_CONSENT,
        MONITOR,
        PASS
    }

    public record Settings(MutesConfig.Block block, boolean monitorGroups) {

        public Settings {
            Objects.requireNonNull(block, "block");
        }
    }

    private final VoicePacketGate gate;
    private volatile Settings settings;

    public VoicePacketPolicy(VoicePacketGate gate, Settings settings) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public VoicePacketGate gate() {
        return gate;
    }

    public Settings settings() {
        return settings;
    }

    public void update(Settings newSettings) {
        this.settings = Objects.requireNonNull(newSettings, "newSettings");
    }

    public Verdict microphone(UUID sender, @Nullable String groupName, boolean openGroup, boolean whispering) {
        Settings current = settings;
        if (gate.isMuted(sender) && !mutedMaySend(current.block(), groupName, openGroup, whispering)) {
            return Verdict.BLOCK;
        }
        if (isBlockedForMissingConsent(sender)) {
            return Verdict.BLOCK_NO_CONSENT;
        }
        if (groupName != null && !current.monitorGroups()) {
            return Verdict.PASS;
        }
        return monitorVerdict(sender);
    }

    public Verdict fullMute(UUID sender) {
        if (gate.isMuted(sender)) {
            return Verdict.BLOCK;
        }
        if (isBlockedForMissingConsent(sender)) {
            return Verdict.BLOCK_NO_CONSENT;
        }
        return monitorVerdict(sender);
    }

    public boolean blocksListening(UUID receiver) {
        if (isBlockedForMissingConsent(receiver)) {
            return true;
        }
        return settings.block().listen() && gate.isMuted(receiver);
    }

    public boolean isBlockedForMissingConsent(UUID playerId) {
        return !gate.hasBypass(playerId) && !gate.hasConsentForMonitoring(playerId);
    }

    private Verdict monitorVerdict(UUID sender) {
        if (gate.hasBypass(sender)) {
            return Verdict.PASS;
        }
        return Verdict.MONITOR;
    }

    private boolean mutedMaySend(MutesConfig.Block block, @Nullable String groupName, boolean openGroup, boolean whispering) {
        if (groupName != null) {
            if (gate.isGroupAllowedWhileMuted(groupName)) {
                return true;
            }
            return switch (block.groups()) {
                case ALL -> false;
                case NONE -> true;
                case OPEN_ONLY -> !openGroup;
            };
        }
        return whispering ? !block.whisper() : !block.proximity();
    }
}
