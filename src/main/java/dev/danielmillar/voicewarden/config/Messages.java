package dev.danielmillar.voicewarden.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * messages.yml, immutable once loaded and safe to use from any thread. Player-derived values must be passed as
 * {@link Placeholder#unparsed} (see {@link #text}) so they can never inject MiniMessage tags.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Values allowed for literal substitution inside tag arguments (click commands): ids and player names. */
    private static final Pattern SAFE_LITERAL = Pattern.compile("[A-Za-z0-9_.\\-]{0,64}");

    private final Map<String, String> strings;
    private final Map<String, List<String>> lists;
    /** Theme colour tags and prefix snippets from messages.yml, available in every message. */
    private final TagResolver shared;

    private Messages(Map<String, String> strings, Map<String, List<String>> lists) {
        this.strings = Map.copyOf(strings);
        this.lists = Map.copyOf(lists);
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> e : this.strings.entrySet()) {
            if (e.getKey().startsWith("theme.")) {
                TextColor color = e.getValue().startsWith("#") ? TextColor.fromHexString(e.getValue()) : NamedTextColor.NAMES.value(e.getValue());
                if (color != null) {
                    builder.tag(e.getKey().substring("theme.".length()), Tag.styling(color));
                }
            }
        }
        for (Map.Entry<String, String> e : this.strings.entrySet()) {
            if (e.getKey().startsWith("prefixes.")) {
                builder.tag(e.getKey().substring("prefixes.".length()), Tag.preProcessParsed(e.getValue()));
            }
        }
        this.shared = builder.build();
    }

    public static Messages load(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                yaml.load(reader);
            } catch (org.bukkit.configuration.InvalidConfigurationException e) {
                throw new IOException("messages.yml is not valid YAML: " + e.getMessage(), e);
            }
        }
        yaml.setDefaults(ConfigLoader.bundledDefaults("messages.yml"));
        Map<String, String> strings = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        for (String key : yaml.getDefaults().getKeys(true)) {
            if (yaml.isList(key)) {
                lists.put(key, List.copyOf(yaml.getStringList(key)));
            } else if (yaml.isString(key)) {
                strings.put(key, yaml.getString(key));
            }
        }
        return new Messages(strings, lists);
    }

    public static TagResolver text(String key, String value) {
        return Placeholder.unparsed(key, value);
    }

    public static TagResolver component(String key, Component value) {
        return Placeholder.component(key, value);
    }

    public String raw(String key) {
        return strings.getOrDefault(key, "");
    }

    public Component render(String key, TagResolver... resolvers) {
        return renderTemplate(strings.getOrDefault(key, key), Map.of(), resolvers);
    }

    /**
     * Like {@link #render} but also substitutes {@code <name>} literally (including inside tag arguments such as
     * click commands). Only values matching a strict id/name pattern are substituted.
     */
    public Component renderWithLiterals(String key, Map<String, String> literals, TagResolver... resolvers) {
        return renderTemplate(strings.getOrDefault(key, key), literals, resolvers);
    }

    public List<Component> renderList(String key, TagResolver... resolvers) {
        return lists.getOrDefault(key, List.of()).stream()
                .map(line -> renderTemplate(line, Map.of(), resolvers))
                .toList();
    }

    public boolean isEmpty(String key) {
        return strings.getOrDefault(key, "").isEmpty();
    }

    private Component renderTemplate(String template, Map<String, String> literals, TagResolver... resolvers) {
        if (template.isEmpty()) {
            return Component.empty();
        }
        String t = template;
        for (Map.Entry<String, String> e : literals.entrySet()) {
            if (SAFE_LITERAL.matcher(e.getValue()).matches()) {
                t = t.replace("<" + e.getKey() + ">", e.getValue());
            }
        }
        TagResolver all = TagResolver.builder().resolver(shared).resolvers(resolvers).build();
        return MINI.deserialize(t, all);
    }
}
