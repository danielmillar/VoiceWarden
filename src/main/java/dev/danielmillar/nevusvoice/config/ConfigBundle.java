package dev.danielmillar.nevusvoice.config;

import dev.danielmillar.nevusvoice.moderation.rules.RuleAction;
import dev.danielmillar.nevusvoice.moderation.rules.RuleConfigLoader;
import dev.danielmillar.nevusvoice.moderation.rules.RuleDefinition;
import dev.danielmillar.nevusvoice.moderation.rules.RuleEngine;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything loaded from disk in one consistent snapshot: config.yml, messages.yml, rules.yml and wordlist.txt.
 * Built off the main thread and swapped in atomically on reload.
 */
public record ConfigBundle(PluginConfig config, Messages messages, RuleEngine rules, List<String> warnings) {

    public static ConfigBundle load(Path dataFolder) throws IOException {
        List<String> warnings = new ArrayList<>();
        Path configFile = dataFolder.resolve("config.yml");
        ConfigLoader.Result result = ConfigLoader.load(dataFolder, configFile);
        warnings.addAll(result.warnings());
        for (String unknown : ConfigLoader.unknownKeys(configFile)) {
            warnings.add("config.yml: unknown setting '" + unknown + "' (typo?)");
        }
        PluginConfig config = result.config();
        Messages messages = Messages.load(dataFolder.resolve("messages.yml"));
        RuleEngine rules = loadRules(dataFolder, config.moderation(), warnings);
        return new ConfigBundle(config, messages, rules, List.copyOf(warnings));
    }

    private static RuleEngine loadRules(Path dataFolder, PluginConfig.Moderation moderation, List<String> warnings)
            throws IOException {
        List<RuleDefinition> rules = new ArrayList<>();
        Set<String> allowlist = new LinkedHashSet<>();

        Path rulesFile = dataFolder.resolve("rules.yml");
        if (Files.exists(rulesFile)) {
            YamlConfiguration yaml = new YamlConfiguration();
            try (Reader reader = Files.newBufferedReader(rulesFile, StandardCharsets.UTF_8)) {
                yaml.load(reader);
            } catch (org.bukkit.configuration.InvalidConfigurationException e) {
                throw new IOException("rules.yml is not valid YAML: " + e.getMessage(), e);
            }
            RuleConfigLoader.LoadResult r = RuleConfigLoader.loadRulesYaml(yaml);
            rules.addAll(r.rules());
            allowlist.addAll(r.allowlist());
            r.warnings().forEach(w -> warnings.add("rules.yml: " + w));
        }

        Path wordList = dataFolder.resolve("wordlist.txt");
        if (Files.exists(wordList)) {
            try (Reader reader = Files.newBufferedReader(wordList, StandardCharsets.UTF_8)) {
                RuleConfigLoader.LoadResult r = RuleConfigLoader.loadWordList(reader, moderation.languages());
                boolean replaceProfanity = !moderation.profanityWords().isEmpty();
                boolean replaceMute = !moderation.muteWords().isEmpty();
                for (RuleDefinition rule : r.rules()) {
                    boolean replaced = rule.action() == RuleAction.FLAG ? replaceProfanity : replaceMute;
                    if (!replaced) {
                        rules.add(rule);
                    }
                }
                allowlist.addAll(r.allowlist());
                r.warnings().forEach(w -> warnings.add("wordlist.txt: " + w));
            }
        }
        RuleConfigLoader.LoadResult overrides = RuleConfigLoader.fromOverrides(moderation.profanityWords(), moderation.muteWords());
        rules.addAll(overrides.rules());
        overrides.warnings().forEach(w -> warnings.add("config.yml moderation: " + w));

        RuleEngine engine = RuleEngine.compile(rules, allowlist);
        engine.warnings().forEach(w -> warnings.add("rules: " + w));
        if (engine.ruleCount() == 0) {
            warnings.add("No moderation rules are loaded: voice will be transcribed but nothing will be flagged");
        }
        return engine;
    }

    public String summary() {
        return rules.ruleCount() + " rules / " + rules.termCount() + " terms, model " + config.speechToText().model();
    }
}
