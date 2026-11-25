package dev.voicewarden.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

public final class ConfigFiles {

    public static final String CONFIG_FILE = "config.yml";
    public static final String MESSAGES_FILE = "config/messages.yml";
    public static final String DEFAULT_CONFIG_RESOURCE = "/default-config.yml";
    public static final String DEFAULT_MESSAGES_RESOURCE = "/default-messages.yml";

    public static final Set<String> CONFIG_USER_OWNED_PATHS = Set.of(
            "mute_ladder.tiers",
            "commands.keyword_commands");

    private static final String DEFAULT_CONFIG_TEMPLATE = resource(DEFAULT_CONFIG_RESOURCE);
    private static final String DEFAULT_MESSAGES_TEMPLATE = resource(DEFAULT_MESSAGES_RESOURCE);

    private ConfigFiles() {
    }

    public static String defaultConfigTemplate() {
        return DEFAULT_CONFIG_TEMPLATE;
    }

    public static String defaultMessagesTemplate() {
        return DEFAULT_MESSAGES_TEMPLATE;
    }

    public static Path configPath(Path dataFolder) {
        return dataFolder.resolve(CONFIG_FILE);
    }

    public static Path messagesPath(Path dataFolder) {
        return dataFolder.resolve(MESSAGES_FILE);
    }

    public static YamlUpdateResult updateConfig(Path dataFolder) {
        return CommentPreservingYamlUpdater.forConfig().update(configPath(dataFolder), defaultConfigTemplate());
    }

    public static YamlUpdateResult updateMessages(Path dataFolder) {
        return CommentPreservingYamlUpdater.forMessages().update(messagesPath(dataFolder), defaultMessagesTemplate());
    }

    private static String resource(String name) {
        try (InputStream in = ConfigFiles.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read bundled resource " + name, e);
        }
    }
}
