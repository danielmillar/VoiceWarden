package dev.danielmillar.voicewarden.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.danielmillar.voicewarden.VoiceWardenPlugin;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.consent.ConsentService;
import dev.danielmillar.voicewarden.notify.Formats;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;

/** {@code /voiceconsent}: a player sees their answer and, in confirm mode, gets the menu again to change it. */
final class ConsentCommand {

    private final VoiceWardenPlugin plugin;

    ConsentCommand(VoiceWardenPlugin plugin) {
        this.plugin = plugin;
    }

    void register(Commands commands) {
        commands.register(Commands.literal("voiceconsent")
                .requires(src -> src.getSender() instanceof Player p && p.hasPermission("voicewarden.consent"))
                .executes(this::run)
                .build(), "Review or change your voice moderation consent", List.of());
    }

    private int run(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return 0;
        }
        ConsentService consent = plugin.consent();
        Messages m = plugin.messages();
        var time = Messages.text("time", consent.answer(player.getUniqueId())
                .map(a -> Formats.dateTime(Instant.ofEpochMilli(a.decidedAt()))).orElse("-"));
        ConsentService.Status status = consent.status(player.getUniqueId());
        player.sendMessage(switch (status) {
            case NOT_REQUIRED -> m.render("consent.status-notice");
            case ACCEPTED -> m.render("consent.status-accepted", time);
            case DENIED -> m.render("consent.status-denied", time);
            case PENDING -> m.render("consent.status-pending");
        });
        if (status != ConsentService.Status.NOT_REQUIRED) {
            plugin.consentPrompt().show(player);
        }
        return Command.SINGLE_SUCCESS;
    }
}
