package dev.voicewarden.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class CommentPreservingYamlUpdaterTest {

    private static final String TEMPLATE = """
            # Header line one.
            # Header line two.

            # Schema version.
            config-version: 3

            # Server id doc.
            server_id: "server-1"

            # Engine doc.
            engine:
              # Profile doc.
              profile: ENGLISH
              # Threads doc.
              threads: 2
              # Double check doc.
              double_check:
                enabled: true
                # Require doc.
                require_confirmation: true

            # Ladder doc.
            mute_ladder:
              enabled: true
              tiers:
                1: "5m"
                2: "15m"
                3: "30m"

            # Storage doc.
            storage:
              type: SQLITE
              # Timeout doc.
              query_timeout_ms: 2000

            # Update check doc.
            update_check:
              enabled: true
            """;

    private static final String USER = """
            # Header line one.
            # Header line two.

            # Schema version.
            config-version: 1 # managed

            # My own note about the server.
            server_id: "lobby-7"   # renamed by ops

            # Engine doc.
            engine:
              # I prefer accuracy.
              profile: ENGLISH_ACCURATE
              # A key only this server has.
              custom_flag: yes

            # Ladder doc.
            mute_ladder:
              enabled: false
              tiers:
                1: "10m"
                2: "1h"

            # Storage doc.
            storage:
              type: MYSQL
            # Trailing note about the file.
            """;

    private final CommentPreservingYamlUpdater updater = CommentPreservingYamlUpdater.forConfig();

    @Test
    void keepsUserCommentsValuesAndOrderWhileAddingMissingKeys() {
        YamlUpdateResult result = updater.merge(TEMPLATE, USER);

        assertTrue(result.changed());
        assertTrue(result.versionUpdated());
        assertEquals(
                List.of("engine.threads", "engine.double_check", "storage.query_timeout_ms", "update_check"),
                result.addedKeys());
        String expected = """
                # Header line one.
                # Header line two.

                # Schema version.
                config-version: 3 # managed

                # My own note about the server.
                server_id: "lobby-7"   # renamed by ops

                # Engine doc.
                engine:
                  # I prefer accuracy.
                  profile: ENGLISH_ACCURATE
                  # Threads doc.
                  threads: 2
                  # Double check doc.
                  double_check:
                    enabled: true
                    # Require doc.
                    require_confirmation: true
                  # A key only this server has.
                  custom_flag: yes

                # Ladder doc.
                mute_ladder:
                  enabled: false
                  tiers:
                    1: "10m"
                    2: "1h"

                # Storage doc.
                storage:
                  type: MYSQL
                  # Timeout doc.
                  query_timeout_ms: 2000

                # Update check doc.
                update_check:
                  enabled: true
                # Trailing note about the file.
                """;
        assertEquals(expected, result.content());
        assertUserValuesKept(USER, result.content(), Set.of("config-version"));
    }

    @Test
    void secondRunIsByteIdenticalAndUnchanged() {
        String once = updater.merge(TEMPLATE, USER).content();
        YamlUpdateResult twice = updater.merge(TEMPLATE, once);
        assertFalse(twice.changed());
        assertEquals(once, twice.content());
        assertEquals(List.of(), twice.addedKeys());
    }

    @Test
    void userOwnedMapsAreNotRefilled() {
        String result = updater.merge(TEMPLATE, USER).content();
        Map<String, Object> ladder = section(load(result), "mute_ladder");
        assertEquals(Map.of(1, "10m", 2, "1h"), ladder.get("tiers"));
    }

    @Test
    void missingUserOwnedMapIsAddedWhole() {
        String user = """
                config-version: 3
                mute_ladder:
                  enabled: true
                """;
        String result = updater.merge(TEMPLATE, user).content();
        assertEquals(Map.of(1, "5m", 2, "15m", 3, "30m"), section(load(result), "mute_ladder").get("tiers"));
    }

    @Test
    void parsingAndEmittingIsLossless() {
        for (String fixture : List.of(TEMPLATE, USER, fourSpaceUser(), trickyUser(), ConfigFiles.defaultConfigTemplate(),
                ConfigFiles.defaultMessagesTemplate())) {
            String normalized = fixture.endsWith("\n") ? fixture.substring(0, fixture.length() - 1) : fixture;
            List<String> lines = Arrays.asList(normalized.split("\n", -1));
            assertEquals(lines, YamlOutline.parse(lines).emit());
        }
    }

    @Test
    void keepsCrlfAndMissingFinalNewline() {
        String user = "config-version: 3\r\n# Keep me.\r\nengine:\r\n  profile: MULTILANG";
        YamlUpdateResult result = updater.merge(TEMPLATE, user);
        assertTrue(result.changed());
        assertFalse(result.content().endsWith("\n"));
        assertFalse(result.content().replace("\r\n", "").contains("\n"));
        assertTrue(result.content().contains("\r\n# Keep me.\r\nengine:\r\n  profile: MULTILANG\r\n"), result.content());
        assertEquals("MULTILANG", section(load(result.content()), "engine").get("profile"));
        assertFalse(updater.merge(TEMPLATE, result.content()).changed());
    }

    @Test
    void insertsWithTheUsersIndentation() {
        String user = fourSpaceUser();
        YamlUpdateResult result = updater.merge(TEMPLATE, user);
        assertTrue(result.content().contains("\n    # Threads doc.\n    threads: 2\n"), result.content());
        assertTrue(result.content().contains("\n    double_check:\n        enabled: true\n        # Require doc.\n        require_confirmation: true\n"), result.content());
        assertTrue(result.content().contains("\nmute_ladder:\n    enabled: true\n    tiers:\n        1: \"5m\"\n"), result.content());
        assertUserValuesKept(user, result.content(), Set.of("config-version"));
        assertFalse(updater.merge(TEMPLATE, result.content()).changed());
    }

    @Test
    void handlesListsBlockScalarsQuotedValuesAndQuotedKeys() {
        String user = trickyUser();
        YamlUpdateResult result = updater.merge(TEMPLATE, user);
        assertTrue(result.changed());
        assertUserValuesKept(user, result.content(), Set.of("config-version"));
        Map<String, Object> merged = load(result.content());
        assertEquals("line one\nkey: value\n# not a comment\n", merged.get("motd"));
        assertEquals("a # b: c", merged.get("quoted"));
        assertEquals(List.of("x", "y"), merged.get("same_indent_list"));
        assertEquals(2, section(merged, "engine").get("threads"));
        assertFalse(updater.merge(TEMPLATE, result.content()).changed());
    }

    @Test
    void leavesFlowStyleAndMismatchedTypesAlone() {
        String user = """
                config-version: 3
                engine: {profile: MULTILANG}
                storage: sqlite
                """;
        YamlUpdateResult result = updater.merge(TEMPLATE, user);
        Map<String, Object> merged = load(result.content());
        assertEquals(Map.of("profile", "MULTILANG"), merged.get("engine"));
        assertEquals("sqlite", merged.get("storage"));
        assertTrue(result.addedKeys().containsAll(List.of("server_id", "mute_ladder", "update_check")));
    }

    @Test
    void neverLowersTheVersion() {
        String user = "config-version: 9\n";
        YamlUpdateResult result = updater.merge(TEMPLATE, user);
        assertFalse(result.versionUpdated());
        assertEquals(9, load(result.content()).get("config-version"));
    }

    @Test
    void missingVersionIsInsertedBelowTheUsersHeader() {
        String user = """
                # My header.

                # Server doc.
                server_id: "x"
                """;
        String result = updater.merge(TEMPLATE, user).content();
        assertTrue(result.startsWith("# My header.\n\n# Schema version.\nconfig-version: 3\n\n# Server doc.\nserver_id: \"x\"\n"));
    }

    @Test
    void multiLineQuotedValuesAreNeverSplitByAnInsert() {
        String user = """
                config-version: 3
                engine:
                  profile: "ENGLISH
                  ACCURATE"
                  custom: 1
                """;
        YamlUpdateResult result = updater.merge(TEMPLATE, user);
        assertEquals("ENGLISH ACCURATE", section(load(result.content()), "engine").get("profile"));
        assertUserValuesKept(user, result.content(), Set.of("config-version"));
        YamlUpdateResult again = updater.merge(TEMPLATE, result.content());
        assertEquals(result.content(), again.content());
    }

    @Test
    void invalidUserYamlIsLeftUntouched() {
        String user = "engine:\n  profile: [unclosed\n";
        YamlUpdateResult result = updater.merge(TEMPLATE, user);
        assertFalse(result.changed());
        assertTrue(result.skippedReason().isPresent());
        assertEquals(user, result.content());
    }

    @Test
    void updateWritesOnlyWhenNeeded(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config/messages.yml");
        YamlUpdateResult created = updater.update(file, TEMPLATE);
        assertTrue(created.created());
        assertEquals(TEMPLATE, Files.readString(file));

        Files.writeString(file, USER, StandardCharsets.UTF_8);
        YamlUpdateResult merged = updater.update(file, TEMPLATE);
        assertTrue(merged.changed());
        assertEquals(merged.content(), Files.readString(file));

        long modified = Files.getLastModifiedTime(file).toMillis();
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified - 10_000));
        YamlUpdateResult again = updater.update(file, TEMPLATE);
        assertFalse(again.changed());
        assertEquals(modified - 10_000, Files.getLastModifiedTime(file).toMillis());
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void bundledTemplatesMergeIntoOldFilesCleanly(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("config.yml"), """
                config-version: 0
                server_id: "survival"
                engine:
                  profile: MULTILANG
                """);
        YamlUpdateResult result = ConfigFiles.updateConfig(dir);
        assertTrue(result.changed());
        VoiceWardenConfig config = ConfigLoader.loadConfig(result.content()).value();
        assertEquals("survival", config.serverId());
        assertEquals(EngineConfig.Profile.MULTILANG, config.engine().profile());
        VoiceWardenConfig defaults = ConfigLoader.loadConfig(ConfigFiles.defaultConfigTemplate()).value();
        assertEquals(defaults.moderation(), config.moderation());
        assertEquals(defaults.storage(), config.storage());
        assertEquals(defaults.muteLadder(), config.muteLadder());
        assertFalse(ConfigFiles.updateConfig(dir).changed());

        YamlUpdateResult messages = ConfigFiles.updateMessages(dir);
        assertTrue(messages.created());
        assertTrue(Files.exists(dir.resolve("config/messages.yml")));
    }

    private static String fourSpaceUser() {
        return """
                config-version: 3
                engine:
                    profile: ENGLISH
                    custom: 1
                storage:
                    type: SQLITE
                """;
    }

    private static String trickyUser() {
        return """
                config-version: 3
                motd: |
                  line one
                  key: value
                  # not a comment
                quoted: "a # b: c"  # real comment
                same_indent_list:
                - x
                - y
                engine:
                  profile: ENGLISH
                mute_ladder:
                  tiers:
                    "1": "1m"
                    '2': "2m"
                """;
    }

    private static void assertUserValuesKept(String before, String after, Set<String> ignored) {
        Map<String, Object> flatBefore = flatten("", load(before));
        Map<String, Object> flatAfter = flatten("", load(after));
        flatBefore.forEach((path, value) -> {
            if (!ignored.contains(path)) {
                assertEquals(value, flatAfter.get(path), path);
            }
        });
    }

    private static Map<String, Object> flatten(String prefix, Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            String path = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                out.putAll(flatten(path, nested));
            } else {
                out.put(path, value);
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        return (Map<String, Object>) root.get(key);
    }

    private static Map<String, Object> load(String text) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }
}
