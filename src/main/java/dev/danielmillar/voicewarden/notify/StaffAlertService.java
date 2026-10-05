package dev.danielmillar.voicewarden.notify;

import dev.danielmillar.voicewarden.config.Durations;
import dev.danielmillar.voicewarden.config.Messages;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.moderation.ActionType;
import dev.danielmillar.voicewarden.moderation.Incident;
import dev.danielmillar.voicewarden.moderation.StaffAction;
import dev.danielmillar.voicewarden.moderation.TranscriptLine;
import dev.danielmillar.voicewarden.moderation.rules.RuleMatch;
import dev.danielmillar.voicewarden.player.OnlinePlayers;
import dev.danielmillar.voicewarden.report.VoiceReport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * In-game and console alerts. Called from worker threads: recipients come from {@link OnlinePlayers} (thread-safe),
 * permission checks go through LuckPerms' thread-safe permissible, and Paper's {@code sendMessage} is thread-safe.
 */
public final class StaffAlertService {

    public static final String ALERTS_PERMISSION = "voicewarden.alerts";
    public static final String REVIEW_PERMISSION = "voicewarden.report.review";

    private final Supplier<PluginConfig> config;
    private final Supplier<Messages> messages;
    private final OnlinePlayers players;
    private final Logger logger;
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    public StaffAlertService(Supplier<PluginConfig> config, Supplier<Messages> messages, OnlinePlayers players, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.players = players;
        this.logger = logger;
    }

    /** @return true if alerts are now enabled for the player */
    public boolean toggle(UUID player) {
        if (muted.remove(player)) {
            return true;
        }
        muted.add(player);
        return false;
    }

    public void incident(Incident incident, boolean alreadyMuted) {
        PluginConfig cfg = config.get();
        Messages m = messages.get();
        String key = alreadyMuted ? "alert.already-muted" : switch (incident.action().type()) {
            case NONE -> "alert.flag";
            case WARN -> "alert.warn";
            case MUTE -> "alert.mute";
        };
        String categories = categories(incident.matches());
        Component line = m.render(key,
                Messages.text("player", incident.playerName()),
                Messages.component("transcript", highlight(incident.transcript(), incident.matches())),
                Messages.text("categories", categories),
                Messages.text("duration", Durations.format(incident.action().duration())),
                Messages.text("offense", Integer.toString(incident.offense())),
                Messages.text("flags", Integer.toString(incident.flagsInWindow())),
                Messages.text("threshold", Integer.toString(incident.flagThreshold())),
                Messages.text("id", incident.shortId()));
        if (incident.dryRun() && incident.action().type() != ActionType.NONE) {
            line = line.append(m.render("alert.dry-run-suffix"));
        }
        Component hover = m.render("alert.hover",
                Messages.text("id", incident.shortId()),
                Messages.text("flags", Integer.toString(incident.flagsInWindow())),
                Messages.text("threshold", Integer.toString(incident.flagThreshold())),
                Messages.text("location", incident.location() == null ? "unknown" : incident.location().formatBlock()),
                Messages.component("context", context(m, incident.context(), incident.spokenAt())));
        line = line.hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand("/voicewarden vcmute " + incident.playerName() + " 30m "));

        if (cfg.alerts().staff()) {
            broadcast(line, ALERTS_PERMISSION);
        }
        if (cfg.alerts().console()) {
            logger.info("[" + incident.action().type() + (incident.dryRun() ? " dry-run" : "") + "] "
                    + incident.playerName() + ": \"" + incident.transcript() + "\" [" + categories + "] #" + incident.shortId());
        }
    }

    public void staffAction(StaffAction action) {
        if (action.actor().equals("System")) {
            return; // expiries are not interesting in chat
        }
        Messages m = messages.get();
        Component line = action.type() == StaffAction.Type.MUTE
                ? m.render("alert.staff-mute",
                Messages.text("actor", action.actor()),
                Messages.text("player", action.targetName()),
                Messages.text("duration", Durations.format(action.duration())),
                Messages.text("reason", action.reason() == null || action.reason().isBlank() ? "" : ": " + action.reason()))
                : m.render("alert.staff-unmute",
                Messages.text("actor", action.actor()),
                Messages.text("player", action.targetName()));
        broadcast(line, ALERTS_PERMISSION);
    }

    public void report(VoiceReport report) {
        Messages m = messages.get();
        Component line = m.renderWithLiterals("report.staff-alert", Map.of("id", report.shortId()),
                Messages.text("reporter", report.reporterName()),
                Messages.text("player", report.targetName()),
                Messages.text("lines", Integer.toString(report.lines().size())),
                Messages.text("window", Durations.format(report.window())));
        broadcast(line, REVIEW_PERMISSION);
        if (config.get().voiceReport().staffChatFullTranscript()) {
            for (TranscriptLine l : report.lines()) {
                broadcast(m.render("report.staff-transcript-line",
                        Messages.text("time", Formats.clock(l.spokenAt())),
                        Messages.text("text", l.text())), REVIEW_PERMISSION);
            }
        }
        logger.info("[REPORT] " + report.reporterName() + " reported " + report.targetName() + " (" + report.lines().size()
                + " lines) #" + report.shortId());
    }

    private void broadcast(Component message, String permission) {
        for (Player player : players.all()) {
            if (!muted.contains(player.getUniqueId()) && player.hasPermission(permission)) {
                player.sendMessage(message);
            }
        }
    }

    /** Transcript as plain text with every rule match highlighted. Offsets come from the rule engine. */
    public static Component highlight(String transcript, List<RuleMatch> matches) {
        TextComponent.Builder out = Component.text().color(NamedTextColor.WHITE);
        int pos = 0;
        for (RuleMatch match : matches) {
            if (match.start() < pos || match.end() > transcript.length()) {
                continue;
            }
            out.append(Component.text(transcript.substring(pos, match.start())));
            out.append(Component.text(transcript.substring(match.start(), match.end()), NamedTextColor.RED, TextDecoration.UNDERLINED)
                    .hoverEvent(HoverEvent.showText(Component.text(match.category() + " (" + match.ruleId() + ")", NamedTextColor.GRAY))));
            pos = match.end();
        }
        out.append(Component.text(transcript.substring(pos)));
        return out.build();
    }

    public static String categories(List<RuleMatch> matches) {
        Set<String> set = new LinkedHashSet<>();
        for (RuleMatch match : matches) {
            set.add(match.category());
        }
        return String.join(", ", set);
    }

    private static Component context(Messages m, List<TranscriptLine> lines, Instant reference) {
        if (lines.isEmpty()) {
            return m.render("alert.no-context");
        }
        TextComponent.Builder out = Component.text();
        for (int i = 0; i < lines.size(); i++) {
            TranscriptLine line = lines.get(i);
            if (i > 0) {
                out.append(Component.newline());
            }
            Duration ago = Duration.between(line.spokenAt(), reference);
            out.append(m.render("alert.context-line",
                    Messages.text("ago", "-" + Durations.formatShort(ago.isNegative() ? Duration.ZERO : ago)),
                    Messages.text("text", line.text())));
        }
        return out.build();
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
