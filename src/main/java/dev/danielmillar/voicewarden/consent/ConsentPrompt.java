package dev.danielmillar.voicewarden.consent;

import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The consent menu: a Paper dialog with Accept and Deny that the player cannot close with Escape. The buttons send
 * fixed custom-click keys, so an answer still lands after {@code /voicewarden reload}.
 */
public final class ConsentPrompt implements Listener {

    static final Key ACCEPT = Key.key("voicewarden", "consent/accept");
    static final Key DENY = Key.key("voicewarden", "consent/deny");
    /** The client is still on the loading screen during the join event and would drop the dialog. */
    private static final long JOIN_DELAY_TICKS = 20;

    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final Supplier<Messages> messages;
    private final ConsentService consent;
    private final Logger logger;

    public ConsentPrompt(Plugin plugin, Supplier<PluginConfig> config, Supplier<Messages> messages,
                         ConsentService consent, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.consent = consent;
        this.logger = logger;
    }

    /** Main thread, on join: ask, or remind a player who denied that their voice is off. */
    public void onJoin(Player player) {
        if (consent.shouldAsk(player.getUniqueId())) {
            run(player, this::show, JOIN_DELAY_TICKS);
        } else if (consent.status(player.getUniqueId()) == ConsentService.Status.DENIED) {
            player.sendMessage(messages.get().render(deniedKey()));
        }
    }

    /** After a reload that changed the consent settings: ask online players whose answer no longer counts. */
    public void askOnline(Iterable<? extends Player> players) {
        for (Player player : players) {
            if (consent.shouldAsk(player.getUniqueId())) {
                run(player, this::show, 1);
            }
        }
    }

    /** Opens the menu. Must run on the player's thread. */
    public void show(Player player) {
        Messages m = messages.get();
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(m.render("consent.dialog.title"))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(m.render("consent.dialog.body"), 300)))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(m.render("consent.dialog.accept"))
                                .action(DialogAction.customClick(ACCEPT, null)).build(),
                        ActionButton.builder(m.render("consent.dialog.deny"))
                                .action(DialogAction.customClick(DENY, null)).build())));
        player.showDialog(dialog);
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        boolean accept = ACCEPT.equals(event.getIdentifier());
        if (!accept && !DENY.equals(event.getIdentifier())) {
            return;
        }
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection) || !config.get().consent().confirm()) {
            return;
        }
        Player player = connection.getPlayer();
        ConsentService.Outcome outcome = consent.answer(player.getUniqueId(), accept);
        run(player, p -> {
            switch (outcome) {
                case ACCEPTED -> p.sendMessage(messages.get().render("consent.accepted"));
                case DENIED -> p.sendMessage(messages.get().render(deniedKey()));
                case KICK -> p.kick(messages.get().render("consent.kick"));
            }
        }, 1);
    }

    private String deniedKey() {
        return config.get().consent().speakWithoutConsent() ? "consent.denied-heard" : "consent.denied";
    }

    private void run(Player player, java.util.function.Consumer<Player> action, long delayTicks) {
        try {
            player.getScheduler().runDelayed(plugin, task -> action.accept(player), null, delayTicks);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not show the voice consent menu to " + player.getName(), e);
        }
    }
}
