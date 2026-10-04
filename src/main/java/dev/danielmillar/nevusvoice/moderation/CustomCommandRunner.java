package dev.danielmillar.nevusvoice.moderation;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs configured console commands. Bukkit requires command dispatch on the server thread, so the expanded commands
 * are handed to the global region scheduler (main thread on Paper, global region on Folia) as one tiny task.
 */
public final class CustomCommandRunner {

    private static final int MAX_VALUE_LENGTH = 256;

    private final Plugin plugin;
    private final Logger logger;

    public CustomCommandRunner(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void run(List<String> templates, Map<String, String> placeholders) {
        if (templates.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        List<String> commands = new ArrayList<>(templates.size());
        for (String template : templates) {
            String command = template.strip();
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                command = command.replace("{" + e.getKey() + "}", sanitize(e.getValue()));
            }
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (!command.isBlank()) {
                commands.add(command);
            }
        }
        if (commands.isEmpty()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            for (String command : commands) {
                try {
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                        logger.warning("Custom command was not handled (unknown command?): " + command);
                    }
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Custom command failed: " + command, e);
                }
            }
        });
    }

    /** Strips characters that could break out of a command line or inject legacy formatting. */
    static String sanitize(String value) {
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_VALUE_LENGTH));
        for (int i = 0; i < value.length() && out.length() < MAX_VALUE_LENGTH; i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                out.append(' ');
            } else if (c >= 0x20 && c != 0x7F && c != '§') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
