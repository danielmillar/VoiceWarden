package dev.danielmillar.voicesentinel.config;

import dev.danielmillar.voicesentinel.testutil.Fakes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @TempDir Path folder;

    @Test void everyBundledMessageKeyRendersWithoutExceptions() throws Exception {
        Fakes.resource(folder, "messages.yml");
        Path file = folder.resolve("messages.yml");
        Messages messages = Messages.load(file);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        String[] placeholders = {"player", "transcript", "categories", "action", "duration", "offense", "flags", "threshold",
                "id", "context", "location", "ago", "text", "actor", "reason", "remaining", "name", "input", "count", "time",
                "details", "warning", "error", "next", "line", "state", "model", "speakers", "queue", "workers", "transcribed",
                "no-speech", "dropped", "latency-avg", "latency-p95", "rtf", "incidents", "automutes", "mutes", "discord-sent",
                "discord-queue", "discord-failed", "reporter", "lines", "window", "minutes"};
        TagResolver[] resolvers = java.util.Arrays.stream(placeholders).map(k -> Messages.text(k, "test")).toArray(TagResolver[]::new);
        int rendered = 0;
        for (String key : yaml.getKeys(true)) {
            if (yaml.isList(key)) {
                assertEquals(yaml.getStringList(key).size(), assertDoesNotThrow(() -> messages.renderList(key, resolvers), key).size());
                rendered++;
            } else if (yaml.isString(key)) {
                assertNotNull(assertDoesNotThrow(() -> messages.renderWithLiterals(key, Map.of("id", "abc123"), resolvers), key));
                rendered++;
            }
        }
        assertTrue(rendered > 50);
    }

    @Test void playerTextCannotInjectColorsOrClickCommands() throws Exception {
        Messages messages = Messages.load(folder.resolve("messages.yml"));
        String hostile = "<red>evil<click:run_command:/op me>";
        Component rendered = messages.render("alert.flag", Messages.text("player", hostile),
                Messages.text("transcript", "hello"), Messages.text("categories", "test"));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(rendered).contains(hostile));
        assertNoClick(rendered);
        assertPlayerColor(rendered, hostile);
    }

    @Test void missingMessageFileUsesBundledDefaults() throws Exception {
        Messages messages = Messages.load(folder.resolve("messages.yml"));
        assertFalse(messages.raw("player.warned").isBlank());
        assertFalse(PlainTextComponentSerializer.plainText().serialize(messages.render("player.warned")).isBlank());
    }

    @Test void partialMessageFileKeepsOverrideAndInheritsMissingKeys() throws Exception {
        Path file = folder.resolve("messages.yml");
        java.nio.file.Files.writeString(file, "player:\n  warned: 'Custom warning'\n");
        Messages messages = Messages.load(file);
        assertEquals("Custom warning", messages.raw("player.warned"));
        assertFalse(messages.raw("player.unmuted").isBlank());
    }

    @Test void unsafeLiteralIsNotInsertedIntoClickArgument() throws Exception {
        Messages messages = Messages.load(folder.resolve("messages.yml"));
        Component result = messages.renderWithLiterals("report.inbox-entry", Map.of("id", "x'><click:run_command:/op me>"));
        assertNoOpCommand(result);
    }

    private static void assertNoClick(Component component) {
        assertNull(component.clickEvent());
        for (Component child : component.children()) assertNoClick(child);
    }
    private static void assertPlayerColor(Component component, String value) {
        if (component instanceof TextComponent text && text.content().equals(value)) assertNotEquals(NamedTextColor.RED, component.color());
        for (Component child : component.children()) assertPlayerColor(child, value);
    }
    private static void assertNoOpCommand(Component component) {
        if (component.clickEvent() != null) assertFalse(component.clickEvent().value().contains("/op me"));
        for (Component child : component.children()) assertNoOpCommand(child);
    }
}
