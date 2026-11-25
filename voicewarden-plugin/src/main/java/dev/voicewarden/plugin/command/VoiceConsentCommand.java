package dev.voicewarden.plugin.command;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.plugin.consent.ConsentManager;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class VoiceConsentCommand implements CommandExecutor, TabCompleter {

    private final ConsentManager consent;
    private final Replies replies;

    public VoiceConsentCommand(ConsentManager consent, MessageRenderer messages, TaskScheduler scheduler) {
        this.consent = Objects.requireNonNull(consent, "consent");
        this.replies = new Replies(messages, scheduler);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            replies.send(sender, MessageKey.COMMANDS_PLAYER_ONLY);
            return true;
        }
        consent.onCommand(player, args.length == 0 ? null : args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return args.length == 1 ? Replies.matching(args[0], Stream.of("accept", "deny")) : List.of();
    }
}
