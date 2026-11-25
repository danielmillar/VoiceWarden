package dev.voicewarden.plugin.consent;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateConsentBridge implements BedrockConsentForms {

    static final int ACCEPT_BUTTON = 0;
    static final int RULES_BUTTON = 1;
    static final int DENY_BUTTON = 2;

    private final FloodgateApi floodgate;
    private final MessageRenderer messages;
    private final TaskScheduler scheduler;

    private FloodgateConsentBridge(FloodgateApi floodgate, MessageRenderer messages, TaskScheduler scheduler) {
        this.floodgate = floodgate;
        this.messages = messages;
        this.scheduler = scheduler;
    }

    static BedrockConsentForms create(MessageRenderer messages, TaskScheduler scheduler) {
        return new FloodgateConsentBridge(FloodgateApi.getInstance(), messages, scheduler);
    }

    @Override
    public boolean isBedrock(UUID playerId) {
        return floodgate.isFloodgatePlayer(playerId);
    }

    @Override
    public void open(Player player, ConsentManager manager) {
        int shownVersion = manager.config().rulesVersion();
        SimpleForm.Builder form = SimpleForm.builder()
                .title(text(MessageKey.CONSENT_BEDROCK_TITLE))
                .content(text(MessageKey.CONSENT_BEDROCK_CONTENT))
                .button(text(MessageKey.CONSENT_BEDROCK_ACCEPT_BUTTON))
                .button(text(MessageKey.CONSENT_BEDROCK_RULES_BUTTON))
                .button(text(MessageKey.CONSENT_BEDROCK_DENY_BUTTON))
                .validResultHandler(response -> {
                    switch (response.clickedButtonId()) {
                        case ACCEPT_BUTTON -> onPlayerThread(player, p -> manager.accept(p, shownVersion));
                        case RULES_BUTTON -> onPlayerThread(player, manager::showRules);
                        case DENY_BUTTON -> onPlayerThread(player, p -> manager.deny(p, shownVersion));
                        default -> onPlayerThread(player, manager::onPromptDismissed);
                    }
                })
                .closedOrInvalidResultHandler(() -> onPlayerThread(player, manager::onPromptDismissed));
        floodgate.sendForm(player.getUniqueId(), form);
    }

    private void onPlayerThread(Player player, Consumer<Player> action) {
        scheduler.runForEntity(player, () -> action.accept(player));
    }

    private String text(MessageKey key) {
        return messages.plain(key, Map.of());
    }
}
