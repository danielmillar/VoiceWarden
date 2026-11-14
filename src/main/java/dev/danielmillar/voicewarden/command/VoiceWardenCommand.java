package dev.danielmillar.voicewarden.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.danielmillar.voicewarden.VoiceWardenPlugin;
import dev.danielmillar.voicewarden.config.Durations;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.health.Metrics;
import dev.danielmillar.voicewarden.health.SetupCheck;
import dev.danielmillar.voicewarden.moderation.StaffModerationService;
import dev.danielmillar.voicewarden.moderation.TranscriptLine;
import dev.danielmillar.voicewarden.moderation.rules.RuleMatch;
import dev.danielmillar.voicewarden.mute.MuteEvent;
import dev.danielmillar.voicewarden.mute.MuteService;
import dev.danielmillar.voicewarden.notify.Formats;
import dev.danielmillar.voicewarden.notify.StaffAlertService;
import dev.danielmillar.voicewarden.notify.discord.WebhookTestResult;
import dev.danielmillar.voicewarden.notify.discord.WebhookTestTarget;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /voicewarden} (alias {@code /vw}). Permissions use the voicewarden namespace.
 * Executors only parse arguments; anything touching disk, LuckPerms or the network replies asynchronously.
 */
final class VoiceWardenCommand {

    private static final int HISTORY_PAGE_SIZE = 6;

    private final VoiceWardenPlugin plugin;

    VoiceWardenCommand(VoiceWardenPlugin plugin) {
        this.plugin = plugin;
    }

    void register(Commands commands) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("voicewarden")
                .requires(src -> hasAny(src.getSender(), "voicewarden.admin", "voicewarden.reload", "voicewarden.stats",
                        "voicewarden.vcmute", "voicewarden.alerts", "voicewarden.report.review"))
                .then(Commands.literal("reload").requires(perm("voicewarden.reload")).executes(this::reload))
                .then(Commands.literal("stats").requires(perm("voicewarden.stats")).executes(this::stats))
                .then(Commands.literal("alerts").requires(perm("voicewarden.alerts")).executes(this::alerts))
                .then(Commands.literal("vcmute").requires(perm("voicewarden.vcmute"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> Targets.suggest(plugin, b))
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            List.of("5m", "15m", "30m", "1h", "6h", "1d", "7d", "perm").forEach(b::suggest);
                                            return b.buildFuture();
                                        })
                                        .executes(c -> vcmute(c, null))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(c -> vcmute(c, StringArgumentType.getString(c, "reason")))))))
                .then(Commands.literal("unvcmute").requires(perm("voicewarden.vcmute"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> suggestMuted(b))
                                .executes(this::unvcmute)))
                .then(Commands.literal("pardon").requires(perm("voicewarden.vcmute"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> suggestMutedAndOnline(b))
                                .executes(c -> pardon(c, null))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(c -> pardon(c, StringArgumentType.getString(c, "reason"))))))
                .then(Commands.literal("mutes").requires(perm("voicewarden.vcmute")).executes(this::mutes))
                .then(Commands.literal("offenses").requires(perm("voicewarden.vcmute"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> Targets.suggest(plugin, b))
                                .executes(c -> offenses(c, false))
                                .then(Commands.literal("reset").executes(c -> offenses(c, true)))))
                .then(Commands.literal("history").requires(perm("voicewarden.vcmute"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> suggestMutedAndOnline(b))
                                .executes(c -> history(c, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(c -> history(c, IntegerArgumentType.getInteger(c, "page"))))))
                .then(Commands.literal("transcript").requires(perm("voicewarden.report.review"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> Targets.suggest(plugin, b))
                                .executes(c -> transcript(c, 10))
                                .then(Commands.argument("lines", IntegerArgumentType.integer(1, 100))
                                        .executes(c -> transcript(c, IntegerArgumentType.getInteger(c, "lines"))))))
                .then(Commands.literal("test").requires(perm("voicewarden.admin"))
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(this::test)))
                .then(Commands.literal("check").requires(perm("voicewarden.admin")).executes(this::check))
                .then(Commands.literal("forget").requires(perm("voicewarden.admin"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> suggestMutedAndOnline(b))
                                .executes(c -> forget(c, false))
                                .then(Commands.literal("confirm").executes(c -> forget(c, true)))))
                .then(Commands.literal("export").requires(perm("voicewarden.admin"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> suggestMutedAndOnline(b))
                                .executes(this::export)))
                .then(Commands.literal("discordtest").requires(perm("voicewarden.admin"))
                        .executes(c -> discordTest(c, WebhookTestTarget.ALL))
                        .then(Commands.literal("all").executes(c -> discordTest(c, WebhookTestTarget.ALL)))
                        .then(Commands.literal("flag").executes(c -> discordTest(c, WebhookTestTarget.FLAG)))
                        .then(Commands.literal("mute").executes(c -> discordTest(c, WebhookTestTarget.MUTE)))
                        .then(Commands.literal("report").executes(c -> discordTest(c, WebhookTestTarget.REPORT))));
        commands.register(root.build(), "VoiceWarden voice moderation", List.of("vw"));
    }

    // ── subcommands ──────────────────────────────────────────────────────────

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(msg().render("command.reloading"));
        plugin.reload().whenComplete((result, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                sender.sendMessage(msg().render("command.reload-failed", Messages.text("reason", String.valueOf(cause.getMessage()))));
                return;
            }
            sender.sendMessage(msg().render("command.reloaded", Messages.text("time", Long.toString(result.millis())),
                    Messages.text("details", result.summary())));
            result.warnings().stream().limit(10).forEach(w ->
                    sender.sendMessage(msg().render("command.reload-warning", Messages.text("warning", w))));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int stats(CommandContext<CommandSourceStack> ctx) {
        Metrics m = plugin.metrics();
        long[] latency = m.latencySummary();
        var transcription = plugin.transcription();
        String name = transcription.state().name();
        String state = name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT)
                +(transcription.stateDetail().isEmpty() || transcription.state().name().equals("READY") ? "" : ": " + transcription.stateDetail());
        long dropped = m.queueDropped.sum() + m.staleDropped.sum() + m.packetsDropped.sum();
        List<net.kyori.adventure.text.Component> lines = msg().renderList("stats",
                Messages.text("state", state),
                Messages.text("model", plugin.config().speechToText().model()),
                Messages.text("speakers", Integer.toString(plugin.ingest().activeSpeakers())),
                Messages.text("queue", Integer.toString(transcription.queueSize())),
                Messages.text("workers", Integer.toString(transcription.workers())),
                Messages.text("transcribed", Long.toString(m.transcribed.sum())),
                Messages.text("no-speech", Long.toString(m.noSpeech.sum())),
                Messages.text("dropped", Long.toString(dropped)),
                Messages.text("latency-avg", latency[0] < 0 ? "-" : latency[0] + "ms"),
                Messages.text("latency-p95", latency[1] < 0 ? "-" : latency[1] + "ms"),
                Messages.text("rtf", String.format(Locale.ROOT, "%.1f", m.speedFactor())),
                Messages.text("incidents", Long.toString(m.incidents.sum())),
                Messages.text("unconfirmed", Long.toString(m.unconfirmed.sum())),
                Messages.text("automutes", Long.toString(m.autoMutes.sum())),
                Messages.text("mutes", Integer.toString(plugin.mutes().active().size())),
                Messages.text("discord-sent", Long.toString(plugin.discord().sentCount())),
                Messages.text("discord-queue", Integer.toString(plugin.discord().queueSize())),
                Messages.text("discord-failed", Long.toString(plugin.discord().failedCount())));
        lines.forEach(ctx.getSource().getSender()::sendMessage);
        return Command.SINGLE_SUCCESS;
    }

    private int alerts(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return 0;
        }
        boolean on = plugin.alerts().toggle(player.getUniqueId());
        player.sendMessage(msg().render(on ? "command.alerts-on" : "command.alerts-off"));
        return Command.SINGLE_SUCCESS;
    }

    private int vcmute(CommandContext<CommandSourceStack> ctx, @Nullable String reason) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        String rawDuration = StringArgumentType.getString(ctx, "duration");
        Duration duration;
        try {
            duration = Durations.parse(rawDuration);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(msg().render("command.invalid-duration", Messages.text("input", rawDuration)));
            return 0;
        }
        String why = reason == null || reason.isBlank() ? "Muted by staff" : reason.strip();
        Targets.resolve(plugin, name).thenCompose(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.player-not-found", Messages.text("name", name)));
                return CompletableFuture.completedFuture(null);
            }
            Targets.Target t = target.get();
            return plugin.staffModeration().mute(t.id(), t.name(), duration, why, sender.getName()).thenAccept(result -> {
                if (result == StaffModerationService.Result.BYPASSED) {
                    sender.sendMessage(msg().render("command.cannot-mute-bypass", Messages.text("player", t.name())));
                } else {
                    sender.sendMessage(msg().render(duration == null ? "command.muted-permanent" : "command.muted",
                            Messages.text("player", t.name()), Messages.text("duration", Durations.format(duration))));
                }
            });
        }).whenComplete((ignored, error) -> actionError(sender, error));
        return Command.SINGLE_SUCCESS;
    }

    private int unvcmute(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        resolveMutedFirst(name).thenCompose(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.player-not-found", Messages.text("name", name)));
                return CompletableFuture.completedFuture(null);
            }
            Targets.Target t = target.get();
            return plugin.staffModeration().unmute(t.id(), t.name(), sender.getName()).thenAccept(result ->
                    sender.sendMessage(msg().render(result == StaffModerationService.Result.NOT_MUTED
                                    ? "command.not-muted" : "command.unmuted", Messages.text("player", t.name()))));
        }).whenComplete((ignored, error) -> actionError(sender, error));
        return Command.SINGLE_SUCCESS;
    }

    private int mutes(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<MuteService.Mute> active = plugin.mutes().active();
        if (active.isEmpty()) {
            sender.sendMessage(msg().render("command.mutes-empty"));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(msg().render("command.mutes-header", Messages.text("count", Integer.toString(active.size()))));
        long now = System.currentTimeMillis();
        for (MuteService.Mute m : active.stream().limit(50).toList()) {
            var remaining = m.remaining(now);
            sender.sendMessage(msg().render(remaining == null ? "command.mutes-entry-permanent" : "command.mutes-entry",
                    Messages.text("player", m.playerName()),
                    Messages.text("remaining", Durations.formatShort(remaining)),
                    Messages.text("reason", m.reason()),
                    Messages.text("actor", m.actor())));
        }
        sender.sendMessage(net.kyori.adventure.text.Component.empty());
        return Command.SINGLE_SUCCESS;
    }

    private int offenses(CommandContext<CommandSourceStack> ctx, boolean reset) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        PluginConfig.Moderation mod = plugin.config().moderation();
        Targets.resolve(plugin, name).thenAccept(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.player-not-found", Messages.text("name", name)));
                return;
            }
            Targets.Target t = target.get();
            if (reset) {
                plugin.moderation().offenses().reset(t.id()).thenRun(() ->
                        sender.sendMessage(msg().render("command.offenses-reset", Messages.text("player", t.name()))));
                return;
            }
            plugin.moderation().offenses().lookup(t.id(), mod.ladder().resetAfterDays()).thenAccept(o ->
                    sender.sendMessage(msg().render("command.offenses",
                            Messages.text("player", t.name()),
                            Messages.text("count", Integer.toString(o.count())),
                            Messages.text("next", Durations.format(mod.muteDurationFor(o.count() + 1))))));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int pardon(CommandContext<CommandSourceStack> ctx, @Nullable String reason) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        String why = reason == null ? "" : reason.strip();
        resolveMutedFirst(name).thenCompose(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.player-not-found", Messages.text("name", name)));
                return CompletableFuture.completedFuture(null);
            }
            Targets.Target t = target.get();
            return plugin.staffModeration().pardon(t.id(), t.name(), sender.getName(), why).thenAccept(result ->
                    sender.sendMessage(msg().render(result == StaffModerationService.Result.PARDONED
                            ? "command.pardoned" : "command.pardoned-not-muted", Messages.text("player", t.name()))));
        }).whenComplete((ignored, error) -> actionError(sender, error));
        return Command.SINGLE_SUCCESS;
    }

    private int history(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        resolveMutedFirst(name).thenCompose(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.player-not-found", Messages.text("name", name)));
                return CompletableFuture.completedFuture(null);
            }
            Targets.Target t = target.get();
            return plugin.mutes().history(t.id(), page, HISTORY_PAGE_SIZE).thenAccept(p -> showRecord(sender, t, p));
        }).whenComplete((ignored, error) -> actionError(sender, error));
        return Command.SINGLE_SUCCESS;
    }

    private void showRecord(CommandSender sender, Targets.Target target, MuteEvent.Page page) {
        var player = Messages.text("player", target.name());
        sender.sendMessage(msg().render("command.record-header", player));
        if (page.page() == 1) {
            PluginConfig.Moderation mod = plugin.config().moderation();
            var offenses = plugin.moderation().offenses().lookup(target.id(), mod.ladder().resetAfterDays()).join();
            sender.sendMessage(msg().render("command.record-offenses",
                    Messages.text("count", Integer.toString(offenses.count())),
                    Messages.text("next", Durations.format(mod.muteDurationFor(offenses.count() + 1)))));
            plugin.mutes().get(target.id()).ifPresentOrElse(m -> sender.sendMessage(msg().render(
                            m.expiresAt() == MuteService.PERMANENT ? "command.record-muted-permanent" : "command.record-muted",
                            Messages.text("remaining", Durations.formatShort(m.remaining(System.currentTimeMillis()))),
                            Messages.text("reason", m.reason()), Messages.text("actor", m.actor()))),
                    () -> sender.sendMessage(msg().render("command.record-not-muted")));
            sender.sendMessage(consentLine(target.id()));
        }
        if (page.events().isEmpty()) {
            sender.sendMessage(msg().render(page.page() == 1 ? "command.record-empty" : "command.record-page-empty"));
        }
        for (MuteEvent event : page.events()) {
            var time = Messages.text("time", Formats.dateTime(java.time.Instant.ofEpochMilli(event.at())));
            var actor = Messages.text("actor", event.actor());
            var reason = Messages.text("reason", event.reason().isBlank() ? "" : ": " + event.reason());
            sender.sendMessage(switch (event.type()) {
                case MUTE -> msg().render(event.duration() == null ? "command.record-mute-permanent" : "command.record-mute", time, actor, reason,
                        Messages.text("duration", Durations.format(event.duration())));
                case UNMUTE -> msg().render("command.record-unmute", time, actor, reason);
                case EXPIRE -> msg().render("command.record-expire", time);
                case PARDON -> msg().render("command.record-pardon", time, actor, reason);
            });
        }
        if (page.page() > 1 || page.more()) {
            var literals = java.util.Map.of("player", target.name());
            var newer = page.page() > 1 ? msg().renderWithLiterals("command.record-newer",
                    java.util.Map.of("player", target.name(), "page", Integer.toString(page.newer())))
                    : net.kyori.adventure.text.Component.empty();
            var older = page.more() ? msg().renderWithLiterals("command.record-older",
                    java.util.Map.of("player", target.name(), "page", Integer.toString(page.page() + 1)))
                    : net.kyori.adventure.text.Component.empty();
            sender.sendMessage(msg().renderWithLiterals("command.record-footer", literals,
                    Messages.text("page", Integer.toString(page.page())),
                    Messages.text("pages", Integer.toString(Math.max(1, page.pages()))),
                    Messages.component("newer", newer), Messages.component("older", older)));
        }
        sender.sendMessage(net.kyori.adventure.text.Component.empty());
    }

    private net.kyori.adventure.text.Component consentLine(java.util.UUID player) {
        var consent = plugin.consent();
        var answer = consent.answer(player);
        var time = Messages.text("time", answer.map(a -> Formats.dateTime(java.time.Instant.ofEpochMilli(a.decidedAt()))).orElse("-"));
        var version = Messages.text("version", answer.map(a -> Integer.toString(a.rulesVersion())).orElse("-"));
        return switch (consent.status(player)) {
            case NOT_REQUIRED -> msg().render("command.record-consent-notice");
            case ACCEPTED -> msg().render("command.record-consent-accepted", time, version);
            case DENIED -> msg().render("command.record-consent-denied", time, version);
            case PENDING -> msg().render("command.record-consent-pending");
        };
    }

    private int transcript(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        Targets.resolve(plugin, name).thenAccept(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.player-not-found", Messages.text("name", name)));
                return;
            }
            Targets.Target t = target.get();
            List<TranscriptLine> lines = plugin.transcriptBuffer().recentLines(t.id(), count);
            if (lines.isEmpty()) {
                sender.sendMessage(msg().render("command.history-empty", Messages.text("player", t.name())));
                return;
            }
            sender.sendMessage(msg().render("command.history-header", Messages.text("player", t.name()),
                    Messages.text("count", Integer.toString(lines.size()))));
            for (TranscriptLine line : lines) {
                sender.sendMessage(msg().render("command.history-line",
                        Messages.text("time", Formats.clock(line.spokenAt())), Messages.text("text", line.text())));
            }
            sender.sendMessage(net.kyori.adventure.text.Component.empty());
        });
        return Command.SINGLE_SUCCESS;
    }

    private int test(CommandContext<CommandSourceStack> ctx) {
        String text = StringArgumentType.getString(ctx, "text");
        List<RuleMatch> matches = plugin.rules().match(text);
        CommandSender sender = ctx.getSource().getSender();
        if (matches.isEmpty()) {
            sender.sendMessage(msg().render("command.test-none"));
        } else {
            sender.sendMessage(msg().render("command.test-result",
                    Messages.component("transcript", StaffAlertService.highlight(msg(), text, matches)),
                    Messages.text("categories", StaffAlertService.categories(matches))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int check(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(msg().render("check.start"));
        plugin.checkSetup().whenComplete((findings, error) -> {
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Setup check failed", error);
                sender.sendMessage(msg().render("check.error", Messages.text("reason", String.valueOf(error.getMessage()))));
                return;
            }
            for (SetupCheck.Finding f : findings) {
                var fix = f.fix().isEmpty() ? net.kyori.adventure.text.Component.empty()
                        : msg().render("check.fix", Messages.text("fix", f.fix()));
                sender.sendMessage(msg().render("check." + f.status().name().toLowerCase(Locale.ROOT),
                        Messages.text("area", f.area()), Messages.text("finding", f.text()), Messages.component("fix", fix)));
            }
            sender.sendMessage(msg().render("check.summary",
                    Messages.text("ok", Long.toString(count(findings, SetupCheck.Status.OK))),
                    Messages.text("warn", Long.toString(count(findings, SetupCheck.Status.WARN))),
                    Messages.text("fail", Long.toString(count(findings, SetupCheck.Status.FAIL)))));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int forget(CommandContext<CommandSourceStack> ctx, boolean confirmed) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        resolveAnyone(name).thenCompose(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.unknown-player", Messages.text("name", name)));
                return CompletableFuture.completedFuture(null);
            }
            Targets.Target t = target.get();
            var player = Messages.text("player", t.name());
            if (!confirmed) {
                sender.sendMessage(msg().renderWithLiterals("command.forget-confirm", java.util.Map.of("target", name), player));
                return CompletableFuture.completedFuture(null);
            }
            return plugin.playerData().forget(t.id()).handle((f, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not erase the VoiceWarden data of player " + t.id(), cause);
                    sender.sendMessage(msg().render("command.forget-failed", player,
                            Messages.text("reason", String.valueOf(cause.getMessage()))));
                    return null;
                }
                // The UUID only: the console log is not erased, so it must not keep the name.
                plugin.getLogger().info(sender.getName() + " erased the VoiceWarden data of player " + t.id());
                sender.sendMessage(msg().render("command.forgotten", player,
                        Messages.text("lifted", f.wasMuted() ? "yes" : "no"),
                        Messages.text("history", Integer.toString(f.muteEvents())),
                        Messages.text("offenses", Integer.toString(f.offences())),
                        Messages.text("consent", f.consent() ? "yes" : "no"),
                        Messages.text("reports", Integer.toString(f.reports())),
                        Messages.text("evidence", Integer.toString(f.evidenceFiles())),
                        Messages.text("buffered", Integer.toString(f.bufferedLines())),
                        Messages.text("logged", Integer.toString(f.logLines())),
                        Messages.text("exports", Integer.toString(f.exports()))));
                sender.sendMessage(msg().render("command.forgotten-elsewhere"));
                Player online = plugin.players().get(t.id());
                if (online != null) {
                    plugin.consentPrompt().askOnline(List.of(online));
                }
                return null;
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int export(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        resolveAnyone(name).thenCompose(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.unknown-player", Messages.text("name", name)));
                return CompletableFuture.completedFuture(null);
            }
            Targets.Target t = target.get();
            var player = Messages.text("player", t.name());
            return plugin.playerData().export(t.id(), t.name()).handle((path, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not export " + t.name() + " (" + t.id() + ")", cause);
                    sender.sendMessage(msg().render("command.export-failed", player,
                            Messages.text("reason", String.valueOf(cause.getMessage()))));
                    return null;
                }
                // Shown from the plugins folder's parent, e.g. plugins/VoiceWarden/exports/<uuid>-<time>.json
                java.nio.file.Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
                String shown = root.relativize(path.toAbsolutePath().normalize()).toString();
                sender.sendMessage(msg().render("command.exported", player, Messages.text("path", shown)));
                return null;
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    /** A UUID, for players long gone; otherwise a muted or known player's name. */
    private CompletableFuture<Optional<Targets.Target>> resolveAnyone(String input) {
        java.util.UUID id;
        try {
            id = input.length() == 36 ? java.util.UUID.fromString(input) : null;
        } catch (IllegalArgumentException e) {
            id = null;
        }
        if (id == null) {
            return resolveMutedFirst(input);
        }
        java.util.UUID uuid = id;
        String known = plugin.mutes().get(uuid).map(MuteService.Mute::playerName)
                .or(() -> plugin.transcriptBuffer().lastKnownName(uuid))
                .orElse(input);
        return CompletableFuture.completedFuture(Optional.of(new Targets.Target(uuid, known)));
    }

    private static long count(List<SetupCheck.Finding> findings, SetupCheck.Status status) {
        return findings.stream().filter(f -> f.status() == status).count();
    }

    private int discordTest(CommandContext<CommandSourceStack> ctx, WebhookTestTarget target) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(msg().render("command.discordtest-sent"));
        plugin.discord().sendTest(target).whenComplete((results, error) -> {
            if (error != null) {
                sender.sendMessage(msg().render("command.discordtest-stopped", Messages.text("reason", String.valueOf(error.getMessage()))));
                return;
            }
            for (WebhookTestResult result : results) {
                var webhook = Messages.text("webhook", result.webhook());
                var status = Messages.text("status", Integer.toString(result.status()));
                var reason = Messages.text("reason", result.status() == 0 ? result.detail()
                        : result.detail().isEmpty() ? "Discord answered " + result.status() + "."
                        : "Discord answered " + result.status() + ": " + result.detail());
                sender.sendMessage(result.webhook().isEmpty() ? msg().render("command.discordtest-stopped", reason)
                        : switch (result.outcome()) {
                            case SENT -> msg().render("command.discordtest-ok", webhook, status);
                            case OFF -> msg().render("command.discordtest-off", webhook);
                            case FAILED -> msg().render("command.discordtest-failed", webhook, reason);
                        });
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Muted players resolve by their stored name first, so offline players who were never cached still match. */
    private CompletableFuture<Optional<Targets.Target>> resolveMutedFirst(String name) {
        return plugin.mutes().active().stream()
                .filter(m -> m.playerName().equalsIgnoreCase(name)).findFirst()
                .map(m -> CompletableFuture.completedFuture(Optional.of(new Targets.Target(m.playerId(), m.playerName()))))
                .orElseGet(() -> Targets.resolve(plugin, name));
    }

    private CompletableFuture<Suggestions> suggestMutedAndOnline(SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        var names = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        plugin.mutes().active().forEach(m -> names.add(m.playerName()));
        plugin.players().all().forEach(p -> names.add(p.getName()));
        names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestMuted(SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        plugin.mutes().active().stream().map(MuteService.Mute::playerName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).forEach(builder::suggest);
        return builder.buildFuture();
    }

    private void actionError(CommandSender sender, Throwable error) {
        if (error != null) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Staff moderation failed", error);
            sender.sendMessage(msg().render("command.action-failed", Messages.text("reason", "see the console for why.")));
        }
    }

    private Messages msg() {
        return plugin.messages();
    }

    private static java.util.function.Predicate<CommandSourceStack> perm(String permission) {
        return src -> src.getSender().hasPermission(permission) || src.getSender().hasPermission("voicewarden.admin");
    }

    private static boolean hasAny(CommandSender sender, String... permissions) {
        for (String p : permissions) {
            if (sender.hasPermission(p)) {
                return true;
            }
        }
        return false;
    }
}
