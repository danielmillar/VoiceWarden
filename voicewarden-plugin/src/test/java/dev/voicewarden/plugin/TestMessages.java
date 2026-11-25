package dev.voicewarden.plugin;

import dev.voicewarden.core.config.ConfigFiles;
import dev.voicewarden.core.config.ConfigLoader;
import dev.voicewarden.core.config.MessagesConfig;
import dev.voicewarden.plugin.text.MessageRenderer;
import dev.voicewarden.plugin.text.PlaceholderHook;

public final class TestMessages {

    private TestMessages() {
    }

    public static MessagesConfig defaults() {
        return ConfigLoader.loadMessages(ConfigFiles.defaultMessagesTemplate()).value();
    }

    public static MessageRenderer renderer() {
        return new MessageRenderer(defaults(), PlaceholderHook.NONE);
    }
}
