package dev.danielmillar.voicewarden.command;

import dev.danielmillar.voicewarden.VoiceWardenPlugin;
import io.papermc.paper.command.brigadier.Commands;

/** Registers all Brigadier commands (Paper lifecycle COMMANDS event). */
public final class CommandRegistrar {

    private CommandRegistrar() {
    }

    public static void register(Commands commands, VoiceWardenPlugin plugin) {
        new VoiceWardenCommand(plugin).register(commands);
        new ReportCommands(plugin).register(commands);
    }
}
