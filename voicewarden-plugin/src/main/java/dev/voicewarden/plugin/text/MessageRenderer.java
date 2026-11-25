package dev.voicewarden.plugin.text;

import dev.voicewarden.core.config.MessageKey;
import dev.voicewarden.core.config.MessagesConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public final class MessageRenderer {

    public static final String PREFIX = "prefix";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Set<String> MARKUP_ARGUMENT_TAGS = Set.of("hover");

    private record State(MessagesConfig messages, PlaceholderHook placeholderHook) {
    }

    private volatile State state;

    public MessageRenderer(MessagesConfig messages, PlaceholderHook placeholderHook) {
        this.state = new State(Objects.requireNonNull(messages, "messages"), Objects.requireNonNull(placeholderHook, "placeholderHook"));
    }

    public void update(MessagesConfig messages, PlaceholderHook placeholderHook) {
        this.state = new State(Objects.requireNonNull(messages, "messages"), Objects.requireNonNull(placeholderHook, "placeholderHook"));
    }

    public MessagesConfig messages() {
        return state.messages();
    }

    public Component render(MessageKey key) {
        return render(key, null, Map.of());
    }

    public Component render(MessageKey key, Map<String, String> values) {
        return render(key, null, values);
    }

    public Component render(MessageKey key, @Nullable Player viewer, Map<String, String> values) {
        return renderTemplate(state.messages().get(key), viewer, values);
    }

    public String plain(MessageKey key, Map<String, String> values) {
        return PlainTextComponentSerializer.plainText().serialize(render(key, values));
    }

    public Component renderTemplate(String template, @Nullable Player viewer, Map<String, String> values) {
        State current = state;
        String text = viewer == null ? template : current.placeholderHook().apply(viewer, template);
        String prepared = prepare(text, values);
        MessagesConfig config = current.messages();
        TagResolver.Builder resolver = TagResolver.builder();
        config.prefixes().forEach((name, snippet) -> resolver.resolver(
                Placeholder.parsed(name, inlineThemeColors(snippet, config.theme()))));
        values.forEach((name, value) -> {
            if (!config.prefixes().containsKey(name)) {
                resolver.resolver(Placeholder.unparsed(name, value));
            }
        });
        config.theme().forEach((name, rawColor) -> {
            if (!values.containsKey(name)) {
                TextColor color = parseColor(rawColor);
                if (color != null) {
                    resolver.resolver(TagResolver.resolver(name, Tag.styling(color)));
                }
            }
        });
        return MINI_MESSAGE.deserialize(prepared, resolver.build());
    }

    private static String inlineThemeColors(String snippet, Map<String, String> theme) {
        String out = snippet;
        for (Map.Entry<String, String> entry : theme.entrySet()) {
            TextColor color = parseColor(entry.getValue());
            if (color == null) {
                continue;
            }
            String hex = color.asHexString();
            out = out.replace("<" + entry.getKey() + ">", "<" + hex + ">")
                    .replace("</" + entry.getKey() + ">", "</" + hex + ">");
        }
        return out;
    }

    private static @Nullable TextColor parseColor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.startsWith("#")) {
            return TextColor.fromHexString(trimmed);
        }
        return NamedTextColor.NAMES.value(trimmed.toLowerCase(Locale.ROOT));
    }

    static String prepare(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        int length = template.length();
        int i = 0;
        while (i < length) {
            char c = template.charAt(i);
            if (c == '\\' && i + 1 < length) {
                out.append(c).append(template.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '<') {
                int end = findTagEnd(template, i);
                if (end < 0) {
                    out.append(braceTokensToTags(template.substring(i), values));
                    break;
                }
                out.append('<').append(rewriteTag(template.substring(i + 1, end), values)).append('>');
                i = end + 1;
                continue;
            }
            int next = nextSpecial(template, i);
            out.append(braceTokensToTags(template.substring(i, next), values));
            i = next;
        }
        return out.toString();
    }

    private static int nextSpecial(String text, int from) {
        int i = from;
        while (i < text.length() && text.charAt(i) != '<' && text.charAt(i) != '\\') {
            i++;
        }
        return i == from ? from + 1 : i;
    }

    private static String braceTokensToTags(String text, Map<String, String> values) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        String out = text.replace("{" + PREFIX + "}", "<" + PREFIX + ">");
        for (String name : values.keySet()) {
            out = out.replace("{" + name + "}", "<" + name + ">");
        }
        return out;
    }

    private static int findTagEnd(String text, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if ((c == '\'' || c == '"') && text.charAt(i - 1) == ':') {
                quote = c;
            } else if (c == '<') {
                depth++;
            } else if (c == '>') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    private static String rewriteTag(String inner, Map<String, String> values) {
        List<String> parts = splitArguments(inner);
        if (parts.size() < 2) {
            return inner;
        }
        String tagName = parts.get(0).replaceFirst("^[/!]", "").toLowerCase(Locale.ROOT);
        boolean markup = MARKUP_ARGUMENT_TAGS.contains(tagName);
        StringBuilder out = new StringBuilder(parts.get(0));
        for (int index = 1; index < parts.size(); index++) {
            out.append(':').append(rewriteArgument(parts.get(index), values, markup));
        }
        return out.toString();
    }

    private static List<String> splitArguments(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if ((c == '\'' || c == '"') && i == start) {
                quote = c;
            } else if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (c == ':' && depth == 0) {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inner.substring(start));
        return parts;
    }

    private static String rewriteArgument(String argument, Map<String, String> values, boolean markup) {
        boolean quoted = argument.length() >= 2
                && (argument.charAt(0) == '\'' || argument.charAt(0) == '"')
                && argument.charAt(argument.length() - 1) == argument.charAt(0);
        char quote = quoted ? argument.charAt(0) : '\'';
        String body = quoted ? argument.substring(1, argument.length() - 1) : argument;
        String substituted = substituteTokens(body, values, quote, markup);
        if (quoted) {
            return quote + substituted + quote;
        }
        if (substituted.equals(body)) {
            return argument;
        }
        return quote + substituted + quote;
    }

    private static String substituteTokens(String body, Map<String, String> values, char quote, boolean markup) {
        String out = body;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            String angle = "<" + name + ">";
            String brace = "{" + name + "}";
            if (!out.contains(angle) && !out.contains(brace)) {
                continue;
            }
            String safe = escapeArgumentValue(entry.getValue(), quote, markup);
            out = out.replace(angle, safe).replace(brace, safe);
        }
        return out;
    }

    static String escapeArgumentValue(String value, char quote, boolean markup) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                continue;
            }
            if (c == '\\' || c == quote) {
                out.append('\\');
            } else if (markup && c == '<') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
