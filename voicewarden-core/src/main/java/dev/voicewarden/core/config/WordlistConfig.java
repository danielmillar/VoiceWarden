package dev.voicewarden.core.config;

import java.util.List;

public record WordlistConfig(
        List<String> languages,
        boolean partialMatch,
        boolean caseSensitive,
        List<String> profanityWords,
        List<String> muteList,
        List<String> allowWords) {

    public WordlistConfig {
        languages = List.copyOf(languages);
        profanityWords = List.copyOf(profanityWords);
        muteList = List.copyOf(muteList);
        allowWords = List.copyOf(allowWords);
    }
}
