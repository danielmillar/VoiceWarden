package dev.danielmillar.nevusvoice.command;

import dev.danielmillar.nevusvoice.NevusVoicePlugin;
import io.papermc.paper.command.brigadier.Commands;

/** Registers all Brigadier commands (Paper lifecycle COMMANDS event). */
public final class CommandRegistrar {

    private CommandRegistrar() {
    }

    public static void register(Commands commands, NevusVoicePlugin plugin) {
        new NevusVoiceCommand(plugin).register(commands);
        new ReportCommands(plugin).register(commands);
    }
}
