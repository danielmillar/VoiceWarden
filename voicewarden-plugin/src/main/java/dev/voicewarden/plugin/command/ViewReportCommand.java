package dev.voicewarden.plugin.command;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.plugin.mute.KnownPlayer;
import dev.voicewarden.plugin.mute.PlayerDirectory;
import dev.voicewarden.plugin.report.VoiceReportManager;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ViewReportCommand implements CommandExecutor, TabCompleter {

    public static final String USAGE = "/viewreport <player> [minutes] | /viewreport #<id>";
    static final String NOT_FOUND = "<hold-up>Voice report <accent>#<id></accent> was not found.";

    private final VoiceReportManager reports;
    private final Function<String, Optional<KnownPlayer>> resolver;
    private final PlayerDirectory players;
    private final Replies replies;

    public ViewReportCommand(
            VoiceReportManager reports,
            Function<String, Optional<KnownPlayer>> resolver,
            PlayerDirectory players,
            MessageRenderer messages,
            TaskScheduler scheduler) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.players = Objects.requireNonNull(players, "players");
        this.replies = new Replies(messages, scheduler);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!Replies.allowed(sender, VoiceReportManager.REVIEW_PERMISSION, VoiceReportManager.ALERTS_PERMISSION)) {
            replies.send(sender, MessageKey.COMMANDS_NO_PERMISSION);
            return true;
        }
        if (!(sender instanceof Player viewer)) {
            replies.send(sender, MessageKey.COMMANDS_PLAYER_ONLY);
            return true;
        }
        if (args.length == 0 || args.length > 2) {
            replies.usage(sender, USAGE);
            return true;
        }
        String first = args[0];
        Optional<KnownPlayer> target = Replies.looksLikeReportId(first) ? Optional.empty() : resolver.apply(first);
        if (target.isEmpty() && args.length == 1 && (Replies.looksLikeReportId(first) || Replies.parseReportId(first).isPresent())) {
            OptionalLong id = Replies.parseReportId(first);
            if (id.isEmpty()) {
                replies.send(sender, MessageKey.COMMANDS_INVALID_NUMBER, Map.of("input", first));
                return true;
            }
            long reportId = id.getAsLong();
            reports.viewReport(viewer, reportId).thenAccept(found -> {
                if (!found) {
                    replies.template(sender, NOT_FOUND, Map.of("id", Long.toString(reportId)));
                }
            });
            return true;
        }
        if (target.isEmpty()) {
            replies.send(sender, MessageKey.COMMANDS_PLAYER_NOT_FOUND, Map.of("player", first));
            return true;
        }
        Integer minutes = null;
        if (args.length == 2) {
            try {
                minutes = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                replies.send(sender, MessageKey.COMMANDS_INVALID_NUMBER, Map.of("input", args[1]));
                return true;
            }
        }
        reports.viewTranscript(viewer, target.get(), minutes);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!Replies.allowed(sender, VoiceReportManager.REVIEW_PERMISSION, VoiceReportManager.ALERTS_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return Replies.matching(args[0], players.onlinePlayers().stream().map(Player::getName));
        }
        return List.of();
    }
}
