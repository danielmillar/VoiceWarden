package dev.voicewarden.plugin.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.core.config.MessagesConfig;
import dev.voicewarden.plugin.TestMessages;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MessageRendererTest {

    private static final List<String> ALL_PLACEHOLDERS = List.of(
            "player", "duration", "reason", "staff", "remaining", "words", "severity", "action", "strikes",
            "transcript", "server", "reporter", "minutes", "seconds", "id", "time", "text", "count", "age",
            "status", "appeal_url", "input", "usage", "kind", "peak", "clipping");

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<Component> flatten(Component component) {
        List<Component> out = new ArrayList<>();
        out.add(component);
        for (Component child : component.children()) {
            out.addAll(flatten(child));
        }
        return out;
    }

    private static ClickEvent firstClick(Component component) {
        return flatten(component).stream().map(Component::clickEvent).filter(e -> e != null).findFirst().orElse(null);
    }

    private static HoverEvent<?> firstHover(Component component) {
        return flatten(component).stream().map(Component::hoverEvent).filter(e -> e != null).findFirst().orElse(null);
    }

    private static MessageRenderer rendererWith(MessageKey key, String template) {
        Map<MessageKey, String> messages = new EnumMap<>(TestMessages.defaults().messages());
        messages.put(key, template);
        return new MessageRenderer(new MessagesConfig(messages), PlaceholderHook.NONE);
    }

    @Test
    void everyDefaultMessageRendersWithoutLeftoverTokens() {
        MessageRenderer renderer = TestMessages.renderer();
        Map<String, String> values = new HashMap<>();
        for (String name : ALL_PLACEHOLDERS) {
            values.put(name, "v" + name.length());
        }
        for (MessageKey key : MessageKey.values()) {
            Map<String, String> input;
            if (key == MessageKey.PREFIX || key == MessageKey.HOLD_UP || key == MessageKey.OOPS) {
                input = Map.of();
            } else if (key == MessageKey.COMMANDS_RELOAD_FAILED) {
                Map<String, String> withError = new HashMap<>(values);
                withError.put("error", "v5");
                input = withError;
            } else {
                input = values;
            }
            String text = plain(renderer.render(key, input));
            assertFalse(text.contains("<"), key + " left a tag: " + text);
            assertFalse(text.contains("{"), key + " left a brace token: " + text);
        }
    }

    @Test
    void appealUrlIsSubstitutedInsideQuotedClickArgument() {
        Component rendered = TestMessages.renderer().render(MessageKey.APPEAL_MESSAGE, Map.of("appeal_url", "https://appeal.example/form?id=1&x=2"));
        ClickEvent click = firstClick(rendered);
        assertNotNull(click);
        assertEquals(ClickEvent.Action.OPEN_URL, click.action());
        assertEquals("https://appeal.example/form?id=1&x=2", click.value());
        assertTrue(plain(rendered).startsWith("APPEAL "));
    }

    @Test
    void braceTokensWorkInsideUnquotedClickArguments() {
        MessageRenderer renderer = rendererWith(MessageKey.APPEAL_MESSAGE,
                "{prefix}<click:open_url:{appeal_url}><primary>▸ ᴀᴘᴘᴇᴀʟ {player}</primary></click>");
        Component rendered = renderer.render(MessageKey.APPEAL_MESSAGE, Map.of("appeal_url", "https://a.example:8443/x", "player", "Steve"));
        ClickEvent click = firstClick(rendered);
        assertNotNull(click);
        assertEquals("https://a.example:8443/x", click.value());
        assertEquals("VOICE ▸ ᴀᴘᴘᴇᴀʟ Steve", plain(rendered));
    }

    @Test
    void runCommandArgumentKeepsBedrockNamesAndStripsQuoteInjection() {
        MessageRenderer renderer = rendererWith(MessageKey.REPORT_STAFF_ALERT,
                "<click:run_command:'/viewreport <player> <minutes>'><primary>▸ ᴠɪᴇᴡ</primary></click>");
        Component rendered = renderer.render(MessageKey.REPORT_STAFF_ALERT,
                Map.of("reporter", "Alex", "player", ".Bedrock'Name", "minutes", "5", "id", "7"));
        ClickEvent click = firstClick(rendered);
        assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/viewreport .Bedrock'Name 5", click.value());
    }

    @Test
    void controlCharactersCannotSmuggleASecondCommandLine() {
        MessageRenderer renderer = rendererWith(MessageKey.REPORT_STAFF_ALERT,
                "<click:run_command:'/viewreport <player> <minutes>'><primary>▸ ᴠɪᴇᴡ</primary></click>");
        Component rendered = renderer.render(MessageKey.REPORT_STAFF_ALERT,
                Map.of("reporter", "Alex", "player", "Bob\n/op Bob", "minutes", "5", "id", "7"));
        assertEquals("/viewreport Bob/op Bob 5", firstClick(rendered).value());
    }

    @Test
    void transcriptInHoverIsTreatedAsTextNotMarkup() {
        MessageRenderer renderer = rendererWith(MessageKey.ALERTS_FLAGGED,
                "<hover:show_text:'<body><transcript></body>'><primary><b>FLAGGED!</b></primary> <warning><words></warning></hover>");
        Component rendered = renderer.render(MessageKey.ALERTS_FLAGGED, Map.of(
                "player", "Steve", "words", "<red>bad", "severity", "MILD", "strikes", "2", "action", "warn",
                "transcript", "don't <red>say</red> \\ <click:run_command:/op me>x", "server", "s1"));
        HoverEvent<?> hover = firstHover(rendered);
        assertNotNull(hover);
        Component hoverText = (Component) hover.value();
        assertEquals("don't <red>say</red> \\ <click:run_command:/op me>x", plain(hoverText));
        assertTrue(flatten(hoverText).stream().allMatch(c -> c.clickEvent() == null));
        assertTrue(plain(rendered).contains("<red>bad"));
    }

    @Test
    void micSpamAlertIsOneClickableLineWithADetailHover() {
        Component rendered = TestMessages.renderer().render(MessageKey.ALERTS_MIC_SPAM, Map.of(
                "player", "Steve", "kind", "clipping", "peak", "95%", "clipping", "42%", "duration", "0.8s",
                "action", "warn", "server", "s1"));

        assertEquals("MIC SPAM! Steve is clipping or too loud. · clipping · warn", plain(rendered));
        ClickEvent click = firstClick(rendered);
        assertNotNull(click);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action());
        assertEquals("/vw vcmute Steve ", click.value());
        HoverEvent<?> hover = firstHover(rendered);
        assertNotNull(hover);
        assertEquals("Mic spam\nKind: clipping\nPeak loudness: 95%\nClipped samples: 42%\nLasted: 0.8s\nAction: warn\n\n"
                + "Click to prepare a voice mute.", plain((Component) hover.value()));
    }

    @Test
    void micSpamWarningUsesABoldLeadInAndAMutedSecondLine() {
        String text = plain(TestMessages.renderer().render(MessageKey.MIC_SPAM_WARNING, Map.of("player", "Steve")));

        assertEquals("\nTOO LOUD! Your microphone is clipping or far too loud.\n   Turn it down or you will be muted in voice chat.\n", text);
    }

    @Test
    void valuesOutsideTagsAreNeverParsedAsMiniMessage() {
        Component rendered = TestMessages.renderer().render(MessageKey.MUTE_MUTED,
                Map.of("duration", "5m", "reason", "<click:run_command:/op me>pwn"));
        assertTrue(plain(rendered).contains("<click:run_command:/op me>pwn"));
        assertTrue(flatten(rendered).stream().allMatch(c -> c.clickEvent() == null));
    }

    @Test
    void prefixResolvesInBothSyntaxes() {
        MessageRenderer renderer = rendererWith(MessageKey.MUTE_UNMUTED, "{prefix}a <prefix>b");
        assertEquals("VOICE a VOICE b", plain(renderer.render(MessageKey.MUTE_UNMUTED)));
    }

    @Test
    void placeholderApiRunsOnTemplateBeforeValuesAreInserted() {
        MessageRenderer renderer = new MessageRenderer(TestMessages.defaults(),
                (viewer, text) -> text.replace("%server_name%", "Lobby"));
        Map<MessageKey, String> messages = new EnumMap<>(TestMessages.defaults().messages());
        messages.put(MessageKey.MUTE_UNMUTED, "%server_name% <player>");
        renderer.update(new MessagesConfig(messages), (viewer, text) -> text.replace("%server_name%", "Lobby"));
        Player viewer = Mockito.mock(Player.class);
        assertEquals("Lobby %server_name%", plain(renderer.render(MessageKey.MUTE_UNMUTED, viewer, Map.of("player", "%server_name%"))));
        assertEquals("%server_name% x", plain(renderer.render(MessageKey.MUTE_UNMUTED, Map.of("player", "x"))));
    }

    @Test
    void plainRendersForBedrockForms() {
        assertEquals("Voice chat moderation", TestMessages.renderer().plain(MessageKey.CONSENT_BEDROCK_TITLE, Map.of()));
    }
}
