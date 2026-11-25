package dev.voicewarden.plugin.mute;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.core.config.MutesConfig;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public final class MuteIndicators {

    private final MessageRenderer messages;
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();

    public MuteIndicators(MessageRenderer messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public void show(Player player, MutesConfig.Indicator indicator, String remaining, boolean permanent, float progress) {
        Component text = permanent
                ? messages.render(MessageKey.MUTE_INDICATOR_PERMANENT, player, Map.of())
                : messages.render(MessageKey.MUTE_INDICATOR, player, Map.of("remaining", remaining));
        switch (indicator) {
            case ACTION_BAR -> player.sendActionBar(text);
            case BOSS_BAR -> showBossBar(player, text, progress);
            case CHAT -> player.sendMessage(text);
            case NONE -> {
            }
        }
    }

    public void refresh(Player player, MutesConfig.Indicator indicator, String remaining, boolean permanent, float progress) {
        if (indicator == MutesConfig.Indicator.ACTION_BAR || indicator == MutesConfig.Indicator.BOSS_BAR) {
            show(player, indicator, remaining, permanent, progress);
        }
    }

    public void clear(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
        player.sendActionBar(Component.empty());
    }

    public void forget(UUID playerId) {
        bossBars.remove(playerId);
    }

    public boolean hasBossBar(UUID playerId) {
        return bossBars.containsKey(playerId);
    }

    private void showBossBar(Player player, Component text, float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(),
                id -> BossBar.bossBar(text, clamped, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS));
        bar.name(text);
        bar.progress(clamped);
        player.showBossBar(bar);
    }
}
