package dev.voicewarden.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PluginYamlTest {

    private static final Map<String, List<String>> COMMANDS = Map.of(
            "voicewarden", List.of("vw"),
            "voiceconsent", List.of("vconsent"),
            "reportvoice", List.of("rvoice"),
            "viewreport", List.of("vr"),
            "reportinbox", List.of("rinbox", "vreports"));

    private static final Set<String> PERMISSIONS = Set.of(
            "voicewarden.admin", "voicewarden.bypass", "voicewarden.alerts", "voicewarden.reload", "voicewarden.stats",
            "voicewarden.vcmute", "voicewarden.pardon", "voicewarden.history", "voicewarden.mutes", "voicewarden.report",
            "voicewarden.report.review");

    private static YamlConfiguration yaml;
    private static PluginDescriptionFile description;

    @BeforeAll
    static void load() throws IOException, InvalidDescriptionException {
        try (InputStream in = PluginYamlTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in, "plugin.yml must be on the classpath");
            byte[] bytes = in.readAllBytes();
            yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            description = new PluginDescriptionFile(new ByteArrayInputStream(bytes));
        }
    }

    @Test
    void identityAndPlatform() {
        assertEquals("VoiceWarden", description.getName());
        assertEquals(VoiceWardenPlugin.class.getName(), description.getMain());
        assertEquals("1.21", yaml.getString("api-version"));
        assertTrue(yaml.isBoolean("folia-supported"));
        assertTrue(yaml.getBoolean("folia-supported"));
        assertFalse(description.getVersion().contains("${"), "the build must expand the version, got " + description.getVersion());
        assertFalse(description.getVersion().isBlank());
        assertEquals(List.of("Daniel Millar"), description.getAuthors());
        assertEquals("https://danielmillar.dev", description.getWebsite());
    }

    @Test
    void softDependsOnEveryOptionalPluginAndHardDependsOnNothing() {
        assertEquals(List.of("voicechat", "PlasmoVoice", "floodgate", "LuckPerms", "PlaceholderAPI"), description.getSoftDepend());
        assertTrue(description.getDepend().isEmpty());
    }

    @Test
    void everyCommandAndAliasIsDeclared() {
        Map<String, Map<String, Object>> commands = description.getCommands();
        assertEquals(COMMANDS.keySet(), commands.keySet());
        COMMANDS.forEach((name, aliases) -> assertEquals(aliases, commands.get(name).get("aliases"), name));
        Set<String> every = new TreeSet<>(commands.keySet());
        COMMANDS.values().forEach(every::addAll);
        assertFalse(every.contains("vs"), "the /vs alias belongs to another plugin and must not be declared");
    }

    @Test
    void everyPermissionIsDeclared() {
        Set<String> declared = new TreeSet<>();
        for (Permission permission : description.getPermissions()) {
            declared.add(permission.getName());
        }
        assertEquals(new TreeSet<>(PERMISSIONS), declared);
    }

    @Test
    void bypassIsOptInAndNotGrantedByAdmin() {
        Permission bypass = permission("voicewarden.bypass");
        assertEquals(PermissionDefault.FALSE, bypass.getDefault());
        Permission admin = permission("voicewarden.admin");
        assertFalse(admin.getChildren().containsKey("voicewarden.bypass"));
        Set<String> expected = new TreeSet<>(PERMISSIONS);
        expected.remove("voicewarden.admin");
        expected.remove("voicewarden.bypass");
        assertEquals(expected, new TreeSet<>(admin.getChildren().keySet()));
    }

    @Test
    void reportingIsOpenToPlayersAndReviewIsStaffOnly() {
        assertEquals(PermissionDefault.TRUE, permission("voicewarden.report").getDefault());
        assertEquals(PermissionDefault.OP, permission("voicewarden.report.review").getDefault());
        assertEquals(PermissionDefault.OP, permission("voicewarden.admin").getDefault());
    }

    private static Permission permission(String name) {
        return description.getPermissions().stream().filter(permission -> permission.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not declared"));
    }
}
