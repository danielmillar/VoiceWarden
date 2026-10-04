package dev.danielmillar.nevusvoice.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.danielmillar.nevusvoice.NevusVoicePlugin;
import dev.danielmillar.nevusvoice.config.Durations;
import dev.danielmillar.nevusvoice.config.Messages;
import dev.danielmillar.nevusvoice.moderation.TranscriptLine;
import dev.danielmillar.nevusvoice.notify.Formats;
import dev.danielmillar.nevusvoice.report.ReportService;
import dev.danielmillar.nevusvoice.report.ReportStore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@code /reportvoice}, {@code /viewreport} and {@code /reportinbox}, matching the original plugin. */
final class ReportCommands {

    private static final int LINES_PER_PAGE = 6;

    private final NevusVoicePlugin plugin;

    ReportCommands(NevusVoicePlugin plugin) {
        this.plugin = plugin;
    }

    void register(Commands commands) {
        commands.register(Commands.literal("reportvoice")
                .requires(src -> src.getSender().hasPermission("nevusvoice.report"))
                .executes(c -> {
                    c.getSource().getSender().sendMessage(plugin.messages().render("report.usage"));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((c, b) -> Targets.suggest(plugin, b))
                        .executes(c -> report(c, null))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(c -> report(c, Duration.ofMinutes(IntegerArgumentType.getInteger(c, "minutes"))))))
                .build(), "Report a player's recent voice chat to staff", List.of("rvoice"));

        commands.register(Commands.literal("viewreport")
                .requires(src -> src.getSender().hasPermission("nevusvoice.report.review"))
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((c, b) -> {
                            plugin.reports().store().open().stream().limit(20).forEach(r -> b.suggest(r.shortId()));
                            return Targets.suggest(plugin, b);
                        })
                        .executes(c -> view(c, null))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(c -> view(c, Duration.ofMinutes(IntegerArgumentType.getInteger(c, "minutes"))))))
                .build(), "View a voice report or a player's recent speech", List.of("vr"));

        commands.register(Commands.literal("reportinbox")
                .requires(src -> src.getSender().hasPermission("nevusvoice.report.review"))
                .executes(this::inbox)
                .then(Commands.literal("review")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(this::review)))
                .build(), "List open voice reports", List.of("rinbox", "vreports"));
    }

    private int report(CommandContext<CommandSourceStack> ctx, @Nullable Duration window) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        UUID reporterId = sender instanceof Player p ? p.getUniqueId() : null;
        Targets.resolve(plugin, name).thenCompose(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.player-not-found", Messages.text("name", name)));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            Targets.Target t = target.get();
            return plugin.reports().submit(reporterId, sender.getName(), t.id(), t.name(), window).thenAccept(result -> {
                switch (result) {
                    case ReportService.Result.Disabled d -> sender.sendMessage(msg().render("report.disabled"));
                    case ReportService.Result.Self s -> sender.sendMessage(msg().render("report.self"));
                    case ReportService.Result.Cooldown c -> sender.sendMessage(msg().render("report.cooldown",
                            Messages.text("remaining", Durations.formatShort(c.remaining()))));
                    case ReportService.Result.Nothing n -> sender.sendMessage(msg().render("report.nothing",
                            Messages.text("player", t.name())));
                    case ReportService.Result.Submitted s -> sender.sendMessage(msg().render("report.submitted",
                            Messages.text("player", t.name()),
                            Messages.text("lines", Integer.toString(s.report().lines().size()))));
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int view(CommandContext<CommandSourceStack> ctx, @Nullable Duration window) {
        CommandSender sender = ctx.getSource().getSender();
        String target = StringArgumentType.getString(ctx, "target");
        Optional<ReportStore.StoredReport> stored = plugin.reports().store().find(target);
        if (stored.isPresent() && window == null) {
            ReportStore.StoredReport r = stored.get();
            show(sender, r.targetName(), "Report " + r.shortId() + " by " + r.reporterName(), r.lines());
            return Command.SINGLE_SUCCESS;
        }
        Duration w = plugin.config().voiceReport().clamp(window);
        Targets.resolve(plugin, target).thenAccept(resolved -> {
            if (resolved.isEmpty()) {
                sender.sendMessage(msg().render("report.not-found", Messages.text("id", target)));
                return;
            }
            Targets.Target t = resolved.get();
            show(sender, t.name(), "Last " + Durations.format(w), plugin.reports().liveLines(t.id(), w));
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Players get a written book (opened on their own scheduler, i.e. their region/main thread); console gets text. */
    private void show(CommandSender sender, String targetName, String subtitle, List<TranscriptLine> lines) {
        if (lines.isEmpty()) {
            sender.sendMessage(msg().render("command.history-empty", Messages.text("player", targetName)));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(targetName + " - " + subtitle, NamedTextColor.GRAY));
            for (TranscriptLine line : lines) {
                sender.sendMessage(msg().render("command.history-line",
                        Messages.text("time", Formats.clock(line.spokenAt())), Messages.text("text", line.text())));
            }
            return;
        }
        List<Component> pages = new ArrayList<>();
        pages.add(Component.text(targetName, NamedTextColor.DARK_BLUE).append(Component.newline())
                .append(Component.text(subtitle, NamedTextColor.DARK_GRAY)).append(Component.newline())
                .append(Component.text(lines.size() + " line(s)", NamedTextColor.DARK_GRAY)));
        for (int i = 0; i < lines.size() && pages.size() < 100; i += LINES_PER_PAGE) {
            var page = Component.text();
            for (TranscriptLine line : lines.subList(i, Math.min(lines.size(), i + LINES_PER_PAGE))) {
                page.append(Component.text(Formats.clock(line.spokenAt()) + " ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(truncate(line.text(), 90), NamedTextColor.BLACK))
                        .append(Component.newline());
            }
            pages.add(page.build());
        }
        Book book = Book.book(msg().render("report.book-title", Messages.text("player", targetName)),
                Component.text("NevusVoice"), pages);
        player.getScheduler().run(plugin, task -> player.openBook(book), null);
    }

    private int inbox(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<ReportStore.StoredReport> open = plugin.reports().store().open();
        if (open.isEmpty()) {
            sender.sendMessage(msg().render("report.inbox-empty"));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(msg().render("report.inbox-header", Messages.text("count", Integer.toString(open.size()))));
        for (ReportStore.StoredReport r : open.stream().limit(15).toList()) {
            sender.sendMessage(msg().renderWithLiterals("report.inbox-entry", Map.of("id", r.shortId()),
                    Messages.text("player", r.targetName()),
                    Messages.text("reporter", r.reporterName()),
                    Messages.text("ago", Durations.formatShort(Duration.between(r.createdAt(), Instant.now()))),
                    Messages.text("lines", Integer.toString(r.lines().size()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int review(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String id = StringArgumentType.getString(ctx, "id");
        Optional<ReportStore.StoredReport> report = plugin.reports().store().find(id);
        if (report.isEmpty() || !plugin.reports().store().markReviewed(report.get().id(), sender.getName())) {
            sender.sendMessage(msg().render("report.not-found", Messages.text("id", id)));
        } else {
            sender.sendMessage(msg().render("report.reviewed", Messages.text("id", report.get().shortId())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
