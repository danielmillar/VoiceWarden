package dev.voicewarden.plugin.command;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.plugin.report.VoiceReportManager;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

public final class ReportInboxCommand implements CommandExecutor, TabCompleter {

    public static final String USAGE = "/reportinbox [review <id>]";
    static final String NOT_REVIEWED = "<hold-up>Voice report <accent>#<id></accent> was not found or is already reviewed.";

    private final VoiceReportManager reports;
    private final Replies replies;

    public ReportInboxCommand(VoiceReportManager reports, MessageRenderer messages, TaskScheduler scheduler) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.replies = new Replies(messages, scheduler);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!Replies.allowed(sender, VoiceReportManager.REVIEW_PERMISSION)) {
            replies.send(sender, MessageKey.COMMANDS_NO_PERMISSION);
            return true;
        }
        if (args.length == 0) {
            reports.openInbox(sender);
            return true;
        }
        if (args.length != 2 || !"review".equals(args[0].toLowerCase(Locale.ROOT))) {
            replies.usage(sender, USAGE);
            return true;
        }
        OptionalLong id = Replies.parseReportId(args[1]);
        if (id.isEmpty()) {
            replies.send(sender, MessageKey.COMMANDS_INVALID_NUMBER, Map.of("input", args[1]));
            return true;
        }
        long reportId = id.getAsLong();
        reports.markReviewed(sender, reportId).thenAccept(changed -> {
            if (!changed) {
                replies.template(sender, NOT_REVIEWED, Map.of("id", Long.toString(reportId)));
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!Replies.allowed(sender, VoiceReportManager.REVIEW_PERMISSION)) {
            return List.of();
        }
        return args.length == 1 ? Replies.matching(args[0], Stream.of("review")) : List.of();
    }
}
