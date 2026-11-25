package dev.voicewarden.plugin.command;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import dev.voicewarden.plugin.text.MessageRenderer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

final class Replies {

    static final String ADMIN_PERMISSION = "voicewarden.admin";

    private final MessageRenderer messages;
    private final TaskScheduler scheduler;

    Replies(MessageRenderer messages, TaskScheduler scheduler) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    MessageRenderer messages() {
        return messages;
    }

    void send(CommandSender sender, MessageKey key, Map<String, String> values) {
        if (sender instanceof Player player) {
            scheduler.runForEntity(player, () -> player.sendMessage(messages.render(key, player, values)));
        } else {
            sender.sendMessage(messages.render(key, null, values));
        }
    }

    void send(CommandSender sender, MessageKey key) {
        send(sender, key, Map.of());
    }

    void template(CommandSender sender, String template, Map<String, String> values) {
        if (sender instanceof Player player) {
            scheduler.runForEntity(player, () -> player.sendMessage(messages.renderTemplate(template, player, values)));
        } else {
            sender.sendMessage(messages.renderTemplate(template, null, values));
        }
    }

    Component line(CommandSender sender, String template, Map<String, String> values) {
        return messages.renderTemplate(template, viewer(sender), values);
    }

    Component lineFor(@Nullable Player viewer, String template, Map<String, String> values) {
        return messages.renderTemplate(template, viewer, values);
    }

    void usage(CommandSender sender, String usage) {
        send(sender, MessageKey.COMMANDS_USAGE, Map.of("usage", usage));
    }

    void deliver(CommandSender sender, List<Component> lines) {
        if (sender instanceof Player player) {
            scheduler.runForEntity(player, () -> lines.forEach(player::sendMessage));
        } else {
            lines.forEach(sender::sendMessage);
        }
    }

    void deliverDeferred(CommandSender sender, Function<@Nullable Player, List<Component>> render) {
        if (sender instanceof Player player) {
            scheduler.runForEntity(player, () -> render.apply(player).forEach(player::sendMessage));
        } else {
            render.apply(null).forEach(sender::sendMessage);
        }
    }

    static boolean allowed(CommandSender sender, String... permissions) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        for (String permission : permissions) {
            if (sender.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    static OptionalLong parseReportId(String input) {
        String text = input.trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        try {
            return OptionalLong.of(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    static boolean looksLikeReportId(String input) {
        return input.startsWith("#") || input.startsWith("-") && input.length() > 1;
    }

    static List<String> matching(String prefix, Stream<String> options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static @Nullable Player viewer(CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }
}
