package dev.danielmillar.voicesentinel.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.danielmillar.voicesentinel.VoiceSentinelPlugin;
import dev.danielmillar.voicesentinel.config.Durations;
import dev.danielmillar.voicesentinel.config.Messages;
import dev.danielmillar.voicesentinel.config.PluginConfig;
import dev.danielmillar.voicesentinel.health.Metrics;
import dev.danielmillar.voicesentinel.moderation.StaffAction;
import dev.danielmillar.voicesentinel.moderation.TranscriptLine;
import dev.danielmillar.voicesentinel.moderation.rules.RuleMatch;
import dev.danielmillar.voicesentinel.mute.MuteService;
import dev.danielmillar.voicesentinel.notify.Formats;
import dev.danielmillar.voicesentinel.notify.StaffAlertService;
import dev.danielmillar.voicesentinel.notify.discord.WebhookTestTarget;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * {@code /nevusvoice} (alias {@code /nv}). Existing permission nodes are retained for compatibility.
 * Executors only parse arguments; anything touching disk, LuckPerms or the network replies asynchronously.
 */
final class VoiceSentinelCommand {

    private final VoiceSentinelPlugin plugin;

    VoiceSentinelCommand(VoiceSentinelPlugin plugin) {
        this.plugin = plugin;
    }

    void register(Commands commands) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("nevusvoice")
                .requires(src -> hasAny(src.getSender(), "voicesentinel.admin", "voicesentinel.reload", "voicesentinel.stats",
                        "voicesentinel.vcmute", "voicesentinel.alerts", "voicesentinel.report.review"))
                .then(Commands.literal("reload").requires(perm("voicesentinel.reload")).executes(this::reload))
                .then(Commands.literal("stats").requires(perm("voicesentinel.stats")).executes(this::stats))
                .then(Commands.literal("alerts").requires(perm("voicesentinel.alerts")).executes(this::alerts))
                .then(Commands.literal("vcmute").requires(perm("voicesentinel.vcmute"))
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
                .then(Commands.literal("unvcmute").requires(perm("voicesentinel.vcmute"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    plugin.mutes().active().forEach(m -> b.suggest(m.playerName()));
                                    return b.buildFuture();
                                })
                                .executes(this::unvcmute)))
                .then(Commands.literal("mutes").requires(perm("voicesentinel.vcmute")).executes(this::mutes))
                .then(Commands.literal("offenses").requires(perm("voicesentinel.vcmute"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> Targets.suggest(plugin, b))
                                .executes(c -> offenses(c, false))
                                .then(Commands.literal("reset").executes(c -> offenses(c, true)))))
                .then(Commands.literal("history").requires(perm("voicesentinel.report.review"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> Targets.suggest(plugin, b))
                                .executes(c -> history(c, 10))
                                .then(Commands.argument("lines", IntegerArgumentType.integer(1, 100))
                                        .executes(c -> history(c, IntegerArgumentType.getInteger(c, "lines"))))))
                .then(Commands.literal("test").requires(perm("voicesentinel.admin"))
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(this::test)))
                .then(Commands.literal("discordtest").requires(perm("voicesentinel.admin"))
                        .executes(c -> discordTest(c, WebhookTestTarget.ALL))
                        .then(Commands.literal("all").executes(c -> discordTest(c, WebhookTestTarget.ALL)))
                        .then(Commands.literal("flag").executes(c -> discordTest(c, WebhookTestTarget.FLAG)))
                        .then(Commands.literal("mute").executes(c -> discordTest(c, WebhookTestTarget.MUTE)))
                        .then(Commands.literal("report").executes(c -> discordTest(c, WebhookTestTarget.REPORT))));
        commands.register(root.build(), "NevusVoice voice moderation", List.of("nv"));
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
        String state = transcription.state().name()
                + (transcription.stateDetail().isEmpty() || transcription.state().name().equals("READY") ? "" : ": " + transcription.stateDetail());
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
        Targets.resolve(plugin, name).thenAccept(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.player-not-found", Messages.text("name", name)));
                return;
            }
            Targets.Target t = target.get();
            if (plugin.isBypassed(plugin.players().get(t.id()))) {
                sender.sendMessage(msg().render("command.cannot-mute-bypass", Messages.text("player", t.name())));
                return;
            }
            plugin.mutes().mute(t.id(), t.name(), duration, why, sender.getName(), false);
            sender.sendMessage(msg().render("command.muted", Messages.text("player", t.name()),
                    Messages.text("duration", Durations.format(duration))));
            StaffAction action = new StaffAction(StaffAction.Type.MUTE, t.id(), t.name(), sender.getName(), duration, why, Instant.now());
            plugin.alerts().staffAction(action);
            plugin.discord().submitStaffAction(action);
        });
        return Command.SINGLE_SUCCESS;
    }

    private int unvcmute(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        MuteService.Mute local = plugin.mutes().active().stream()
                .filter(m -> m.playerName().equalsIgnoreCase(name)).findFirst().orElse(null);
        var resolved = local != null
                ? java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.of(new Targets.Target(local.playerId(), local.playerName())))
                : Targets.resolve(plugin, name);
        resolved.thenCompose(target -> {
            if (target.isEmpty()) {
                sender.sendMessage(msg().render("command.player-not-found", Messages.text("name", name)));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            Targets.Target t = target.get();
            return plugin.mutes().unmute(t.id()).thenAccept(wasMuted -> {
                if (!wasMuted && !plugin.config().mute().luckPerms()) {
                    sender.sendMessage(msg().render("command.not-muted", Messages.text("player", t.name())));
                    return;
                }
                sender.sendMessage(msg().render("command.unmuted", Messages.text("player", t.name())));
                StaffAction action = new StaffAction(StaffAction.Type.UNMUTE, t.id(), t.name(), sender.getName(), null, null, Instant.now());
                plugin.alerts().staffAction(action);
                plugin.discord().submitStaffAction(action);
            });
        });
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
            sender.sendMessage(msg().render("command.mutes-entry",
                    Messages.text("player", m.playerName()),
                    Messages.text("remaining", Durations.formatShort(m.remaining(now))),
                    Messages.text("reason", m.reason()),
                    Messages.text("actor", m.actor())));
        }
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

    private int history(CommandContext<CommandSourceStack> ctx, int count) {
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
                    Messages.component("transcript", StaffAlertService.highlight(text, matches)),
                    Messages.text("categories", StaffAlertService.categories(matches))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int discordTest(CommandContext<CommandSourceStack> ctx, WebhookTestTarget target) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(msg().render("command.discordtest-sent"));
        plugin.discord().sendTest(target).whenComplete((lines, error) -> {
            if (error != null) {
                sender.sendMessage(msg().render("command.discordtest-line", Messages.text("line", "failed: " + error.getMessage())));
                return;
            }
            lines.forEach(line -> sender.sendMessage(msg().render("command.discordtest-line", Messages.text("line", line))));
        });
        return Command.SINGLE_SUCCESS;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Messages msg() {
        return plugin.messages();
    }

    private static java.util.function.Predicate<CommandSourceStack> perm(String permission) {
        return src -> src.getSender().hasPermission(permission) || src.getSender().hasPermission("voicesentinel.admin");
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
