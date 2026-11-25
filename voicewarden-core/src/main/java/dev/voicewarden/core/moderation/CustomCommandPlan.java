package dev.voicewarden.core.moderation;

import java.util.List;

public record CustomCommandPlan(
        List<String> commands,
        List<String> firedKeywords,
        boolean muteCommandsRan,
        boolean profanityCommandsRan) {

    private static final CustomCommandPlan EMPTY = new CustomCommandPlan(List.of(), List.of(), false, false);

    public CustomCommandPlan {
        commands = List.copyOf(commands);
        firedKeywords = List.copyOf(firedKeywords);
    }

    public static CustomCommandPlan empty() {
        return EMPTY;
    }

    public boolean keywordCommandsRan() {
        return !firedKeywords.isEmpty();
    }

    public boolean isEmpty() {
        return commands.isEmpty() && firedKeywords.isEmpty();
    }
}
