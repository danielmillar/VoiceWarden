package dev.voicewarden.plugin.pipeline;

import dev.voicewarden.core.config.ConfigFiles;
import dev.voicewarden.core.config.ConfigLoadResult;
import dev.voicewarden.core.config.ConfigLoader;
import dev.voicewarden.core.config.MessagesConfig;
import dev.voicewarden.core.config.VoiceWardenConfig;
import dev.voicewarden.core.config.YamlUpdateResult;
import dev.voicewarden.core.wordlist.ParsedRules;
import dev.voicewarden.core.wordlist.ParsedWordlist;
import dev.voicewarden.core.wordlist.RuleMatcher;
import dev.voicewarden.core.wordlist.RulesParser;
import dev.voicewarden.core.wordlist.WordlistConfig;
import dev.voicewarden.core.wordlist.WordlistParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PluginFiles {

    public static final String WORDLIST_FILE = "config/wordlist.txt";
    public static final String RULES_FILE = "config/rules.yml";
    public static final String CONSENT_FILE = "consent.properties";
    public static final String DEFAULTS_MARKER = "using all defaults";

    public record Loaded(
            VoiceWardenConfig config,
            MessagesConfig messages,
            RuleMatcher wordlist,
            List<String> warnings,
            List<String> notices) {

        public Loaded {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(messages, "messages");
            Objects.requireNonNull(wordlist, "wordlist");
            warnings = List.copyOf(warnings);
            notices = List.copyOf(notices);
        }

        public Optional<String> unreadableFile() {
            return warnings.stream().filter(warning -> warning.endsWith(DEFAULTS_MARKER)).findFirst();
        }
    }

    private PluginFiles() {
    }

    public static Loaded load(Path dataFolder) throws IOException {
        Files.createDirectories(dataFolder.resolve("config"));
        List<String> warnings = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        describe(ConfigFiles.updateConfig(dataFolder), ConfigFiles.CONFIG_FILE, warnings, notices);
        describe(ConfigFiles.updateMessages(dataFolder), ConfigFiles.MESSAGES_FILE, warnings, notices);
        ConfigLoadResult<VoiceWardenConfig> config = ConfigLoader.loadConfig(ConfigFiles.configPath(dataFolder));
        ConfigLoadResult<MessagesConfig> messages = ConfigLoader.loadMessages(ConfigFiles.messagesPath(dataFolder));
        warnings.addAll(config.warnings());
        warnings.addAll(messages.warnings());
        Path wordlistFile = dataFolder.resolve(WORDLIST_FILE);
        if (!Files.exists(wordlistFile)) {
            Files.writeString(wordlistFile, WordlistParser.defaultWordlistResource(), StandardCharsets.UTF_8);
            notices.add("Created " + WORDLIST_FILE + ".");
        }
        Path rulesFile = dataFolder.resolve(RULES_FILE);
        if (!Files.exists(rulesFile)) {
            Files.writeString(rulesFile, RulesParser.defaultRulesResource(), StandardCharsets.UTF_8);
            notices.add("Created " + RULES_FILE + ".");
        }
        WordlistConfig wordlistConfig = ConfigBridges.wordlist(config.value());
        ParsedWordlist parsed = WordlistParser.parse(Files.readString(wordlistFile, StandardCharsets.UTF_8), wordlistConfig);
        parsed.warnings().forEach(warning -> warnings.add(WORDLIST_FILE + ": " + warning));
        ParsedRules parsedRules = RulesParser.parse(Files.readString(rulesFile, StandardCharsets.UTF_8));
        parsedRules.warnings().forEach(warning -> warnings.add(RULES_FILE + ": " + warning));
        RuleMatcher matcher = RuleMatcher.compile(parsed, wordlistConfig, parsedRules);
        return new Loaded(config.value(), messages.value(), matcher, warnings, notices);
    }

    private static void describe(YamlUpdateResult result, String file, List<String> warnings, List<String> notices) {
        result.skippedReason().ifPresent(reason -> warnings.add(file + " was not updated: " + reason));
        if (result.created()) {
            notices.add("Created " + file + ".");
        } else if (!result.addedKeys().isEmpty()) {
            notices.add("Added new settings to " + file + ": " + String.join(", ", result.addedKeys()));
        }
    }
}
