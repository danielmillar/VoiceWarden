package dev.voicewarden.plugin.command;

import dev.voicewarden.api.VoiceMuteRecord;
import dev.voicewarden.core.audio.AudioPipelineStats;
import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.core.discord.MuteWebhookPayload;
import dev.voicewarden.core.discord.TestTarget;
import dev.voicewarden.core.discord.WebhookResult;
import dev.voicewarden.core.moderation.DurationParser;
import dev.voicewarden.core.wordlist.RuleMatcher;
import dev.voicewarden.plugin.mute.KnownPlayer;
import dev.voicewarden.plugin.mute.NativeMuteService;
import dev.voicewarden.plugin.mute.PlayerDirectory;
import dev.voicewarden.plugin.pipeline.VoiceModerationPipeline;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class VoiceWardenCommand implements CommandExecutor, TabCompleter {

    public static final String ADMIN = Replies.ADMIN_PERMISSION;
    public static final int DEFAULT_HISTORY_LIMIT = 10;
    public static final int MAX_HISTORY_LIMIT = 50;
    public static final String DEFAULT_MUTE_REASON = "Muted by staff";

    static final String STATS_HEADER = "<newline> <primary><b>Voice stats</b></primary><newline> <dim>ᴇɴɢɪɴᴇ</dim><newline>";
    static final String STAT_LINE = " <primary>|</primary> <body><name>: <accent><value></accent></body>";
    static final String TEST_HEADER = "<newline> <primary><b>VoiceWarden</b></primary><newline> <dim>ᴄʜᴇᴄᴋ</dim><newline>";
    static final String TEST_PASS = " <success>✔</success> <body><name></body><dim>:</dim> <muted><detail>";
    static final String TEST_FAIL = " <error>✘</error> <body><name></body><dim>:</dim> <body><detail>";
    static final String HISTORY_HEADER = "<newline> <primary><b><player></b></primary><newline> <dim>ᴠᴏɪᴄᴇ ʀᴇᴄᴏʀᴅ</dim><newline><newline> <primary>|</primary> <body>Active strikes: <accent><strikes></accent></body><dim> · </dim><muted><count> mutes shown";
    static final String HISTORY_ENTRY = " <dim><date></dim> <primary>Muted</primary> <body>for <duration></body> <muted>by <moderator><dim>:</dim> <reason><state>";
    static final String HISTORY_EMPTY = "<primary><b>HISTORY</b></primary> <body>No voice mutes recorded for <accent><player></accent>.<newline>   <muted>Active strikes: <strikes>.";
    static final String MUTES_HEADER = "<newline> <primary><b>Voice mutes</b></primary><newline> <dim>ᴀᴄᴛɪᴠᴇ</dim><newline><newline> <primary>|</primary> <body>Muted: <accent><count></accent>";
    static final String MUTES_ENTRY = " <primary>|</primary> <accent><player></accent><dim>:</dim> <body><remaining></body><dim> · </dim><muted><reason>, by <moderator>";
    static final String MUTES_EMPTY = "<primary><b>MUTES</b></primary> <body>Nobody is voice-muted.";
    static final String DISCORD_HEADER = "<primary><b>WEBHOOK</b></primary> <body>Discord webhook test finished (<accent><status></accent>).";
    static final String DISCORD_PART = "   <muted><summary>";
    static final String DISCORD_SENDING = "<primary><b>WEBHOOK</b></primary> <body>Sending test webhooks (<accent><target></accent>)…";
    static final String DEBUG_ON = "<primary><b>DEBUG</b></primary> <body>Streaming live transcripts for <accent><player></accent>.<newline>   <muted>Run the command again to stop.";
    static final String DEBUG_OFF = "<primary><b>DEBUG</b></primary> <body>Stopped streaming transcripts for <accent><player></accent>.";
    static final String RELOAD_NOTE = "<warning><b>NOTE</b></warning> <body><note>";
    static final String UPDATE_CHECKING = "<primary><b>UPDATE</b></primary> <body>Checking for updates…";
    static final String UPDATE_CURRENT = "<success><b>UP TO DATE</b></success> <body>VoiceWarden <accent><current></accent> is up to date.";
    static final String UPDATE_AVAILABLE = "<primary><b>UPDATE!</b></primary> <body>VoiceWarden <accent><latest></accent> is available.<newline>   <muted>You are running <current>.";
    static final String UPDATE_FAILED = "<oops>The update check failed.<newline>   <muted><reason>";
    static final String CONSENT_VIEW = "<primary><b>CONSENT</b></primary> <body><accent><player></accent>'s voice consent is <accent><status></accent>.";
    static final String CONSENT_RESET = "<success><b>RESET!</b></success> <body>Reset voice consent for <accent><player></accent>.";
    static final String CONSENT_RESET_FAILED = "<oops>Could not reset voice consent for <accent><player></accent>.<newline>   <muted><reason>";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> DURATION_SUGGESTIONS = List.of("5m", "15m", "30m", "1h", "1d", "perm");
    private static final List<String> HISTORY_SUGGESTIONS = List.of("5", "10", "25", "50");

    enum Subcommand {
        HELP(null, ""),
        RELOAD("voicewarden.reload", "/vw reload"),
        STATS("voicewarden.stats", "/vw stats"),
        VCMUTE("voicewarden.vcmute", "/vw vcmute <player> [duration] [reason]"),
        UNVCMUTE("voicewarden.vcmute", "/vw unvcmute <player>"),
        PARDON("voicewarden.pardon", "/vw pardon <player>"),
        HISTORY("voicewarden.history", "/vw history <player> [limit]"),
        MUTES("voicewarden.mutes", "/vw mutes"),
        DISCORDTEST(ADMIN, "/vw discordtest <all|flag|mute>"),
        TEST(ADMIN, "/vw test [webhook]"),
        DEBUG(ADMIN, "/vw debug <player>"),
        CONSENT(ADMIN, "/vw consent <player> [reset]"),
        UPDATECHECK(ADMIN, "/vw updatecheck");

        private final String permission;
        private final String usage;

        Subcommand(String permission, String usage) {
            this.permission = permission;
            this.usage = usage;
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        boolean allows(CommandSender sender) {
            return permission == null || Replies.allowed(sender, permission);
        }

        boolean takesPlayer() {
            return this == VCMUTE || this == UNVCMUTE || this == PARDON || this == HISTORY || this == DEBUG || this == CONSENT;
        }

        static Optional<Subcommand> find(String input) {
            String lower = input.toLowerCase(Locale.ROOT);
            return Arrays.stream(values()).filter(sub -> sub.label().equals(lower)).findFirst();
        }
    }

    private final AdminRuntime runtime;
    private final PlayerDirectory players;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Logger logger;
    private final Replies replies;

    public VoiceWardenCommand(
            AdminRuntime runtime,
            PlayerDirectory players,
            MessageRenderer messages,
            TaskScheduler scheduler,
            Clock clock,
            Logger logger) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.players = Objects.requireNonNull(players, "players");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.replies = new Replies(messages, scheduler);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            replies.send(sender, MessageKey.COMMANDS_HELP);
            return true;
        }
        Optional<Subcommand> found = Subcommand.find(args[0]);
        if (found.isEmpty()) {
            replies.send(sender, MessageKey.COMMANDS_UNKNOWN_SUBCOMMAND);
            return true;
        }
        Subcommand sub = found.get();
        if (!sub.allows(sender)) {
            replies.send(sender, MessageKey.COMMANDS_NO_PERMISSION);
            return true;
        }
        if (sub.takesPlayer() && args.length < 2) {
            replies.usage(sender, sub.usage);
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case HELP -> replies.send(sender, MessageKey.COMMANDS_HELP);
            case RELOAD -> reload(sender);
            case STATS -> stats(sender);
            case VCMUTE -> vcmute(sender, rest);
            case UNVCMUTE -> unvcmute(sender, rest[0]);
            case PARDON -> pardon(sender, rest[0]);
            case HISTORY -> history(sender, rest);
            case MUTES -> mutes(sender);
            case DISCORDTEST -> discordTest(sender, rest.length == 0 ? null : rest[0], sub.usage);
            case TEST -> selfTest(sender, rest);
            case DEBUG -> debug(sender, rest[0]);
            case CONSENT -> consent(sender, rest);
            case UPDATECHECK -> updateCheck(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Replies.matching(args[0], Arrays.stream(Subcommand.values()).filter(sub -> sub.allows(sender)).map(Subcommand::label));
        }
        Optional<Subcommand> found = Subcommand.find(args[0]).filter(sub -> sub.allows(sender));
        if (found.isEmpty()) {
            return List.of();
        }
        Subcommand sub = found.get();
        String current = args[args.length - 1];
        if (args.length == 2) {
            if (sub == Subcommand.UNVCMUTE) {
                return Replies.matching(current, runtime.mutes().getActiveMutes().stream().map(VoiceMuteRecord::playerName));
            }
            if (sub.takesPlayer()) {
                return Replies.matching(current, players.onlinePlayers().stream().map(Player::getName));
            }
            if (sub == Subcommand.DISCORDTEST) {
                return Replies.matching(current, Arrays.stream(TestTarget.values()).map(target -> target.name().toLowerCase(Locale.ROOT)));
            }
            if (sub == Subcommand.TEST) {
                return Replies.matching(current, Stream.of("webhook"));
            }
        }
        if (args.length == 3 && sub == Subcommand.VCMUTE) {
            return Replies.matching(current, DURATION_SUGGESTIONS.stream());
        }
        if (args.length == 3 && sub == Subcommand.HISTORY) {
            return Replies.matching(current, HISTORY_SUGGESTIONS.stream());
        }
        if (args.length == 3 && sub == Subcommand.CONSENT) {
            return Replies.matching(current, Stream.of("reset"));
        }
        return List.of();
    }

    private void reload(CommandSender sender) {
        scheduler.runAsync(() -> {
            AdminRuntime.ReloadResult result;
            try {
                result = runtime.reload();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Reload failed", e);
                result = AdminRuntime.ReloadResult.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            if (!result.success()) {
                replies.send(sender, MessageKey.COMMANDS_RELOAD_FAILED, Map.of("error", result.error()));
                return;
            }
            AdminRuntime.ReloadResult ok = result;
            replies.send(sender, MessageKey.COMMANDS_RELOADED);
            if (!ok.notes().isEmpty()) {
                replies.deliverDeferred(sender, viewer -> {
                    List<Component> notes = new ArrayList<>(ok.notes().size());
                    for (String note : ok.notes()) {
                        notes.add(replies.lineFor(viewer, RELOAD_NOTE, Map.of("note", note)));
                    }
                    return notes;
                });
            }
        });
    }

    private void stats(CommandSender sender) {
        VoiceModerationPipeline.Status status = runtime.pipeline().status();
        List<Component> lines = new ArrayList<>();
        lines.add(replies.line(sender, STATS_HEADER, Map.of()));
        lines.add(stat(sender, "Engine", engineSummary(status)));
        lines.add(stat(sender, "Audio", runtime.audioStats().map(VoiceWardenCommand::audioSummary).orElse("not running")));
        lines.add(stat(sender, "Moderation", status.transcripts() + " transcripts, " + status.flags() + " flags, "
                + status.actions() + " actions, " + status.unconfirmed() + " unconfirmed, "
                + status.pendingVerifications() + " Double Checks in flight"));
        lines.add(stat(sender, "Buffers", status.liveBuffers() + " live utterances, " + status.cachedUtterances() + " finished utterances"));
        lines.add(stat(sender, "Mutes", runtime.mutes().getActiveMutes().size() + " active"));
        lines.add(stat(sender, "Storage", storageSummary(runtime.storageStatus())));
        lines.add(stat(sender, "Voice", runtime.voiceBackends().isEmpty() ? "no voice plugin hooked" : String.join(", ", runtime.voiceBackends())));
        lines.add(Component.empty());
        replies.deliver(sender, lines);
    }

    private void selfTest(CommandSender sender, String[] args) {
        boolean webhook = args.length > 0 && "webhook".equalsIgnoreCase(args[0]);
        if (args.length > 0 && !webhook) {
            replies.usage(sender, Subcommand.TEST.usage);
            return;
        }
        VoiceModerationPipeline.Status status = runtime.pipeline().status();
        List<Component> lines = new ArrayList<>();
        lines.add(replies.line(sender, TEST_HEADER, Map.of()));
        lines.add(check(sender, status.engineConnected(), "Speech engine", engineSummary(status)));
        lines.add(check(sender, true, "Double Check", status.doubleCheck()
                ? "on, hits are verified before anyone is punished"
                : "off or not supported by this profile, hits are acted on directly"));
        Optional<AudioPipelineStats> audio = runtime.audioStats();
        lines.add(check(sender, audio.isPresent() && audio.get().processingErrors() == 0 && audio.get().decodeErrors() == 0,
                "Audio pipeline", audio.map(VoiceWardenCommand::audioSummary).orElse("not running")));
        AdminRuntime.StorageStatus storage = runtime.storageStatus();
        lines.add(check(sender, storage.available(), "Storage", storageSummary(storage)));
        List<String> backends = runtime.voiceBackends();
        lines.add(check(sender, !backends.isEmpty(), "Voice chat", backends.isEmpty()
                ? "no Simple Voice Chat or PlasmoVoice hook is active"
                : String.join(", ", backends)));
        RuleMatcher rules = runtime.rules();
        String wordSummary = rules.flaggedPatternCount() + " flagged patterns, " + rules.allowPatternCount() + " allowed patterns";
        if (rules.ruleCount() > 0) {
            wordSummary += ", " + rules.ruleCount() + " rules (" + rules.regexPatternCount() + " regex)";
        }
        lines.add(check(sender, rules.flaggedPatternCount() > 0 || rules.regexPatternCount() > 0, "Word list", wordSummary));
        lines.add(Component.empty());
        replies.deliver(sender, lines);
        if (webhook) {
            discordTest(sender, "all", Subcommand.DISCORDTEST.usage);
        }
    }

    private void vcmute(CommandSender sender, String[] args) {
        Optional<KnownPlayer> target = resolve(sender, args[0]);
        if (target.isEmpty()) {
            return;
        }
        KnownPlayer player = target.get();
        NativeMuteService mutes = runtime.mutes();
        if (mutes.isMuted(player.id())) {
            replies.send(sender, MessageKey.MUTE_ALREADY_MUTED, Map.of("player", player.name()));
            return;
        }
        Duration duration = runtime.defaultMuteDuration();
        if (args.length > 1) {
            try {
                duration = DurationParser.parse(args[1]).orElse(null);
            } catch (IllegalArgumentException e) {
                replies.send(sender, MessageKey.COMMANDS_INVALID_DURATION, Map.of("input", args[1]));
                return;
            }
        }
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : DEFAULT_MUTE_REASON;
        String moderator = sender.getName();
        Duration finalDuration = duration;
        mutes.mutePlayer(player.id(), player.name(), duration, reason, moderator, false).whenComplete((record, error) -> {
            if (record != null) {
                runtime.discord().sendMute(MuteWebhookPayload.muted(record, null, null, clock.instant()));
            }
        });
        replies.send(sender, MessageKey.MUTE_STAFF_MUTED, Map.of(
                "player", player.name(),
                "duration", DurationParser.format(Optional.ofNullable(finalDuration)),
                "reason", reason,
                "staff", moderator));
    }

    private void unvcmute(CommandSender sender, String input) {
        Optional<KnownPlayer> target = resolve(sender, input);
        if (target.isEmpty()) {
            return;
        }
        KnownPlayer player = target.get();
        NativeMuteService mutes = runtime.mutes();
        Optional<VoiceMuteRecord> active = mutes.getActiveMute(player.id());
        if (active.isEmpty()) {
            replies.send(sender, MessageKey.MUTE_NOT_MUTED, Map.of("player", player.name()));
            return;
        }
        String moderator = sender.getName();
        mutes.unmutePlayer(player.id(), moderator).whenComplete((changed, error) -> {
            if (Boolean.TRUE.equals(changed)) {
                runtime.discord().sendMute(MuteWebhookPayload.unmuted(active.get(), moderator, clock.instant()));
            }
        });
        replies.send(sender, MessageKey.MUTE_STAFF_UNMUTED, Map.of("player", player.name(), "staff", moderator));
    }

    private void pardon(CommandSender sender, String input) {
        Optional<KnownPlayer> target = resolve(sender, input);
        if (target.isEmpty()) {
            return;
        }
        KnownPlayer player = target.get();
        NativeMuteService mutes = runtime.mutes();
        Optional<VoiceMuteRecord> active = mutes.getActiveMute(player.id());
        String moderator = sender.getName();
        scheduler.runAsync(() -> {
            try {
                runtime.moderation().pardon(player.id());
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not clear strikes for " + player.name(), e);
            }
        });
        mutes.pardonPlayer(player.id(), moderator).whenComplete((changed, error) -> {
            if (active.isPresent() && error == null) {
                runtime.discord().sendMute(MuteWebhookPayload.unmuted(active.get(), moderator, clock.instant()));
            }
        });
        replies.send(sender, MessageKey.MUTE_STAFF_PARDONED, Map.of("player", player.name(), "staff", moderator));
    }

    private void history(CommandSender sender, String[] args) {
        Optional<KnownPlayer> target = resolve(sender, args[0]);
        if (target.isEmpty()) {
            return;
        }
        int limit = DEFAULT_HISTORY_LIMIT;
        if (args.length > 1) {
            try {
                limit = Math.max(1, Math.min(MAX_HISTORY_LIMIT, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                replies.send(sender, MessageKey.COMMANDS_INVALID_NUMBER, Map.of("input", args[1]));
                return;
            }
        }
        KnownPlayer player = target.get();
        int finalLimit = limit;
        scheduler.runAsync(() -> {
            List<VoiceMuteRecord> records;
            try {
                records = runtime.storage().findMuteHistory(player.id(), finalLimit);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not load voice history for " + player.name(), e);
                records = List.of();
            }
            List<VoiceMuteRecord> finalRecords = records;
            String strikes = Integer.toString(runtime.mutes().getActiveStrikes(player.id()));
            replies.deliverDeferred(sender, viewer -> historyLines(viewer, player, finalRecords, strikes));
        });
    }

    private List<Component> historyLines(@org.jetbrains.annotations.Nullable Player viewer, KnownPlayer player,
            List<VoiceMuteRecord> records, String strikes) {
        if (records.isEmpty()) {
            return List.of(replies.lineFor(viewer, HISTORY_EMPTY, Map.of("player", player.name(), "strikes", strikes)));
        }
        Instant now = clock.instant();
        List<Component> lines = new ArrayList<>();
        lines.add(replies.lineFor(viewer, HISTORY_HEADER, Map.of(
                "player", player.name(), "strikes", strikes, "count", Integer.toString(records.size()))));
        for (VoiceMuteRecord record : records) {
            lines.add(replies.lineFor(viewer, HISTORY_ENTRY, Map.of(
                    "date", DATE.format(record.createdAt().atZone(clock.getZone())),
                    "duration", record.expiresAt() == null
                            ? "good"
                            : DurationParser.format(Duration.between(record.createdAt(), record.expiresAt())),
                    "reason", record.reason(),
                    "moderator", record.moderator(),
                    "state", record.isEffectivelyActive(now) ? " · active" : "")));
        }
        lines.add(Component.empty());
        return lines;
    }

    private void mutes(CommandSender sender) {
        NativeMuteService mutes = runtime.mutes();
        List<VoiceMuteRecord> active = mutes.getActiveMutes().stream()
                .sorted(Comparator.comparing(VoiceMuteRecord::playerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (active.isEmpty()) {
            replies.template(sender, MUTES_EMPTY, Map.of());
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(replies.line(sender, MUTES_HEADER, Map.of("count", Integer.toString(active.size()))));
        for (VoiceMuteRecord record : active) {
            lines.add(replies.line(sender, MUTES_ENTRY, Map.of(
                    "player", record.playerName(),
                    "remaining", mutes.getFormattedMuteRemaining(record.playerId()),
                    "reason", record.reason(),
                    "moderator", record.moderator())));
        }
        lines.add(Component.empty());
        replies.deliver(sender, lines);
    }

    private void discordTest(CommandSender sender, String input, String usage) {
        if (input == null) {
            replies.usage(sender, usage);
            return;
        }
        TestTarget target;
        try {
            target = TestTarget.parse(input);
        } catch (IllegalArgumentException e) {
            replies.usage(sender, usage);
            return;
        }
        replies.template(sender, DISCORD_SENDING, Map.of("target", target.name().toLowerCase(Locale.ROOT)));
        runtime.discord().sendTest(target).whenComplete((result, error) -> replies.deliverDeferred(sender, viewer -> {
            List<Component> lines = new ArrayList<>();
            if (error != null || result == null) {
                lines.add(replies.lineFor(viewer, DISCORD_HEADER, Map.of("status", "failed")));
                lines.add(replies.lineFor(viewer, DISCORD_PART, Map.of("summary", error == null ? "no result" : String.valueOf(error.getMessage()))));
            } else {
                lines.add(replies.lineFor(viewer, DISCORD_HEADER, Map.of("status", result.status().name().toLowerCase(Locale.ROOT))));
                List<WebhookResult> parts = result.parts().isEmpty() ? List.of(result) : result.parts();
                for (WebhookResult part : parts) {
                    lines.add(replies.lineFor(viewer, DISCORD_PART, Map.of("summary", part.summary())));
                }
            }
            return lines;
        }));
    }

    private void debug(CommandSender sender, String input) {
        Optional<KnownPlayer> target = resolve(sender, input);
        if (target.isEmpty()) {
            return;
        }
        boolean enabled = runtime.pipeline().toggleDebug(sender, target.get().id());
        replies.template(sender, enabled ? DEBUG_ON : DEBUG_OFF, Map.of("player", target.get().name()));
    }

    private void consent(CommandSender sender, String[] args) {
        Optional<KnownPlayer> target = resolve(sender, args[0]);
        if (target.isEmpty()) {
            return;
        }
        KnownPlayer player = target.get();
        if (args.length == 1) {
            String status = runtime.consent().placeholderStatus(player.id());
            replies.template(sender, CONSENT_VIEW, Map.of("player", player.name(), "status", status));
            return;
        }
        if (args.length == 2 && "reset".equalsIgnoreCase(args[1])) {
            runtime.consent().reset(player.id()).whenComplete((ignored, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    replies.template(sender, CONSENT_RESET_FAILED, Map.of("player", player.name(), "reason", reason));
                } else {
                    replies.template(sender, CONSENT_RESET, Map.of("player", player.name()));
                }
            });
            return;
        }
        replies.usage(sender, Subcommand.CONSENT.usage);
    }

    private void updateCheck(CommandSender sender) {
        replies.template(sender, UPDATE_CHECKING, Map.of());
        runtime.updates().check().whenComplete((result, error) -> {
            if (error != null || result == null) {
                replies.template(sender, UPDATE_FAILED, Map.of("reason", error == null ? "no result" : String.valueOf(error.getMessage())));
                return;
            }
            switch (result.status()) {
                case UP_TO_DATE -> replies.template(sender, UPDATE_CURRENT, Map.of("current", result.current()));
                case UPDATE_AVAILABLE -> replies.template(sender, UPDATE_AVAILABLE, Map.of("current", result.current(), "latest", result.latest()));
                case FAILED -> replies.template(sender, UPDATE_FAILED, Map.of("reason", result.message()));
            }
        });
    }

    private Optional<KnownPlayer> resolve(CommandSender sender, String input) {
        Optional<KnownPlayer> target = runtime.mutes().resolveTarget(input);
        if (target.isEmpty()) {
            replies.send(sender, MessageKey.COMMANDS_PLAYER_NOT_FOUND, Map.of("player", input));
        }
        return target;
    }

    private Component stat(CommandSender sender, String name, String value) {
        return replies.line(sender, STAT_LINE, Map.of("name", name, "value", value));
    }

    private Component check(CommandSender sender, boolean ok, String name, String detail) {
        return replies.line(sender, ok ? TEST_PASS : TEST_FAIL, Map.of("name", name, "detail", detail));
    }

    private String engineSummary(VoiceModerationPipeline.Status status) {
        if (!status.engineConnected()) {
            return "not connected (" + runtime.engineState() + ")";
        }
        String latency = status.engineLatencyMs() < 0 ? "latency unknown" : String.format(Locale.ROOT, "%.1f ms latency", status.engineLatencyMs());
        return "connected, profile " + status.engineProfile() + ", version " + status.engineVersion() + ", " + latency;
    }

    private static String audioSummary(AudioPipelineStats stats) {
        return stats.activeSessions() + " speaking, " + stats.framesProcessed() + " frames, "
                + (stats.framesDroppedQueueFull() + stats.framesDroppedPoolExhausted()) + " dropped, "
                + stats.queuedFrames() + " queued, " + (stats.decodeErrors() + stats.processingErrors()) + " errors";
    }

    private static String storageSummary(AdminRuntime.StorageStatus storage) {
        if (storage.available()) {
            return storage.description() + " connected";
        }
        String failure = storage.lastFailure().isBlank() ? "" : ": " + storage.lastFailure();
        return storage.description() + " unavailable, using in-memory fallback with " + storage.pendingWrites() + " writes queued" + failure;
    }
}
