package dev.danielmillar.voicesentinel.command;

import dev.danielmillar.voicesentinel.VoiceSentinelPlugin;
import io.papermc.paper.command.brigadier.Commands;

/** Registers all Brigadier commands (Paper lifecycle COMMANDS event). */
public final class CommandRegistrar {

    private CommandRegistrar() {
    }

    public static void register(Commands commands, VoiceSentinelPlugin plugin) {
        new VoiceSentinelCommand(plugin).register(commands);
        new ReportCommands(plugin).register(commands);
    }
}
