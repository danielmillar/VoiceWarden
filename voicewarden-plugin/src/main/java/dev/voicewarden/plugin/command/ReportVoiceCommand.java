package dev.voicewarden.plugin.command;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.core.config.VoiceReportConfig;
import dev.voicewarden.plugin.mute.KnownPlayer;
import dev.voicewarden.plugin.mute.PlayerDirectory;
import dev.voicewarden.plugin.report.VoiceReportManager;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ReportVoiceCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "voicewarden.report";
    public static final String USAGE = "/reportvoice <player> [minutes]";

    private final VoiceReportManager reports;
    private final Function<String, Optional<KnownPlayer>> resolver;
    private final PlayerDirectory players;
    private final Replies replies;

    public ReportVoiceCommand(
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
        if (!(sender instanceof Player reporter)) {
            replies.send(sender, MessageKey.COMMANDS_PLAYER_ONLY);
            return true;
        }
        if (!Replies.allowed(sender, PERMISSION)) {
            replies.send(sender, MessageKey.COMMANDS_NO_PERMISSION);
            return true;
        }
        if (args.length == 0) {
            reports.openReportMenu(reporter);
            return true;
        }
        Optional<KnownPlayer> target = resolver.apply(args[0]);
        if (target.isEmpty()) {
            replies.send(sender, MessageKey.COMMANDS_PLAYER_NOT_FOUND, Map.of("player", args[0]));
            return true;
        }
        if (args.length == 1 && reports.config().mode() == VoiceReportConfig.Mode.GUI) {
            reports.openWindowMenu(reporter, target.get());
            return true;
        }
        Integer minutes = null;
        if (args.length > 1) {
            try {
                minutes = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                replies.send(sender, MessageKey.COMMANDS_INVALID_NUMBER, Map.of("input", args[1]));
                return true;
            }
        }
        if (args.length > 2) {
            replies.usage(sender, USAGE);
            return true;
        }
        reports.submit(reporter, target.get(), minutes);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!Replies.allowed(sender, PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return Replies.matching(args[0], players.onlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player self) || !self.getUniqueId().equals(player.getUniqueId()))
                    .map(Player::getName));
        }
        if (args.length == 2) {
            VoiceReportConfig config = reports.config();
            List<Integer> presets = config.presetMinutes().isEmpty()
                    ? IntStream.of(config.defaultMinutes()).boxed().toList()
                    : config.presetMinutes();
            return Replies.matching(args[1], presets.stream().map(String::valueOf));
        }
        return List.of();
    }
}
