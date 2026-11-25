package dev.voicewarden.core.moderation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record CustomCommandSettings(
        Map<String, List<String>> keywordCommands,
        List<String> muteCommands,
        List<String> profanityCommands,
        boolean builtinOnKeywordCommand,
        boolean builtinOnMuteCommand,
        boolean builtinOnProfanityCommand) {

    public CustomCommandSettings {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        keywordCommands.forEach((keyword, commands) -> {
            String normalized = keyword.strip().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                copy.put(normalized, nonBlank(commands));
            }
        });
        keywordCommands = Collections.unmodifiableMap(copy);
        muteCommands = nonBlank(muteCommands);
        profanityCommands = nonBlank(profanityCommands);
    }

    public static CustomCommandSettings none() {
        return new CustomCommandSettings(Map.of(), List.of(), List.of(), true, true, true);
    }

    private static List<String> nonBlank(List<String> commands) {
        return commands.stream().filter(command -> command != null && !command.isBlank()).map(String::strip).toList();
    }
}
