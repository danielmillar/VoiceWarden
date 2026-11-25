package dev.voicewarden.core.config;

import java.util.List;
import java.util.Objects;

public record MutesConfig(
        Enforcement enforcement,
        boolean mirrorLuckPerms,
        Block block,
        Indicator indicator,
        boolean notifyOnAttempt,
        int attemptCooldownSeconds) {

    public enum Enforcement {
        NATIVE,
        LUCKPERMS
    }

    public enum GroupBlock {
        ALL,
        NONE,
        OPEN_ONLY
    }

    public enum Indicator {
        ACTION_BAR,
        BOSS_BAR,
        CHAT,
        NONE
    }

    public record Block(boolean proximity, boolean whisper, GroupBlock groups, List<String> allowedGroups, boolean listen) {

        public Block {
            Objects.requireNonNull(groups, "groups");
            allowedGroups = List.copyOf(allowedGroups);
        }
    }

    public MutesConfig {
        Objects.requireNonNull(enforcement, "enforcement");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(indicator, "indicator");
    }
}
