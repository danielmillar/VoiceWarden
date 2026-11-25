package dev.voicewarden.core.moderation;

import dev.voicewarden.api.VoiceAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class CustomCommandPlanner {

    public static final int MAX_PLACEHOLDER_LENGTH = 256;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(player|words|transcript)}");
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\p{Zl}\\p{Zp}]+");
    private static final Pattern SPACES = Pattern.compile(" {2,}");

    private final CustomCommandSettings settings;
    private final Map<String, Pattern> keywordPatterns;
    private final Map<UUID, Set<String>> firedThisSession = new ConcurrentHashMap<>();

    public CustomCommandPlanner(CustomCommandSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (String keyword : settings.keywordCommands().keySet()) {
            patterns.put(keyword, Pattern.compile(
                    "(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword) + "(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        this.keywordPatterns = patterns;
    }

    public CustomCommandSettings settings() {
        return settings;
    }

    public CustomCommandPlan plan(
            UUID playerId,
            String playerName,
            List<String> matchedWords,
            String transcript,
            Optional<VoiceAction> action,
            boolean allowProfanityCommands) {
        String words = String.join(", ", matchedWords);
        Set<String> lowerWords = matchedWords.stream().map(word -> word.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> fired = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        for (Map.Entry<String, Pattern> keyword : keywordPatterns.entrySet()) {
            boolean matched = lowerWords.contains(keyword.getKey()) || keyword.getValue().matcher(transcript).find();
            if (matched && firedThisSession.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet()).add(keyword.getKey())) {
                fired.add(keyword.getKey());
                for (String command : settings.keywordCommands().get(keyword.getKey())) {
                    commands.add(interpolate(command, playerName, words, transcript));
                }
            }
        }
        if (!fired.isEmpty()) {
            return new CustomCommandPlan(commands, fired, false, false);
        }
        if (action.isEmpty()) {
            return CustomCommandPlan.empty();
        }
        if (action.get() == VoiceAction.MUTE) {
            return fromList(settings.muteCommands(), playerName, words, transcript, true, false);
        }
        if (allowProfanityCommands) {
            return fromList(settings.profanityCommands(), playerName, words, transcript, false, true);
        }
        return CustomCommandPlan.empty();
    }

    public void clearSession(UUID playerId) {
        firedThisSession.remove(playerId);
    }

    private static CustomCommandPlan fromList(
            List<String> templates, String player, String words, String transcript, boolean mute, boolean profanity) {
        if (templates.isEmpty()) {
            return CustomCommandPlan.empty();
        }
        List<String> commands = templates.stream().map(command -> interpolate(command, player, words, transcript)).toList();
        return new CustomCommandPlan(commands, List.of(), mute, profanity);
    }

    public static String interpolate(String template, String player, String words, String transcript) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "player" -> player;
                case "words" -> words;
                default -> transcript;
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(sanitize(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String sanitize(String value) {
        String flattened = SPACES.matcher(UNSAFE.matcher(value).replaceAll(" ")).replaceAll(" ").strip();
        return flattened.length() > MAX_PLACEHOLDER_LENGTH ? flattened.substring(0, MAX_PLACEHOLDER_LENGTH) : flattened;
    }
}
