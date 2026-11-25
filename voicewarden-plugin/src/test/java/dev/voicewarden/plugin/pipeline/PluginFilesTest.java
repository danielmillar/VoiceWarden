package dev.voicewarden.plugin.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.core.config.ConfigFiles;
import dev.voicewarden.core.wordlist.RulesParser;
import dev.voicewarden.core.wordlist.WordlistParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginFilesTest {

    @Test
    void firstLoadCreatesEveryFileFromTheBundledDefaults(@TempDir Path data) throws IOException {
        PluginFiles.Loaded loaded = PluginFiles.load(data);

        assertTrue(Files.isRegularFile(ConfigFiles.configPath(data)));
        assertTrue(Files.isRegularFile(ConfigFiles.messagesPath(data)));
        assertEquals(WordlistParser.defaultWordlistResource(), Files.readString(data.resolve(PluginFiles.WORDLIST_FILE)));
        assertEquals(RulesParser.defaultRulesResource(), Files.readString(data.resolve(PluginFiles.RULES_FILE)));
        assertEquals("server-1", loaded.config().serverId());
        assertTrue(loaded.wordlist().flaggedPatternCount() > 0);
        assertTrue(loaded.wordlist().ruleCount() > 0);
        assertTrue(loaded.wordlist().match("what the fuck").hasMatches());
        assertTrue(loaded.wordlist().match("join my server at play.rival.net").hasMatches());
        assertTrue(loaded.unreadableFile().isEmpty());
        assertTrue(loaded.notices().stream().anyMatch(notice -> notice.contains(PluginFiles.WORDLIST_FILE)));
        assertTrue(loaded.notices().stream().anyMatch(notice -> notice.contains(PluginFiles.RULES_FILE)));
    }

    @Test
    void editedWordlistAndConfigAreKeptAndUsed(@TempDir Path data) throws IOException {
        PluginFiles.load(data);
        Files.writeString(data.resolve(PluginFiles.WORDLIST_FILE), "[EN-MUTE]\nzorblax\n", StandardCharsets.UTF_8);
        Path config = ConfigFiles.configPath(data);
        Files.writeString(config, Files.readString(config).replace("server_id: \"server-1\"", "server_id: \"lobby\""));

        PluginFiles.Loaded loaded = PluginFiles.load(data);

        assertEquals("lobby", loaded.config().serverId());
        assertTrue(loaded.wordlist().match("zorblax").hasMatches());
        assertFalse(loaded.wordlist().match("what the fuck").hasMatches());
        assertTrue(loaded.notices().isEmpty());
    }

    @Test
    void brokenYamlIsReportedSoReloadCanKeepThePreviousSettings(@TempDir Path data) throws IOException {
        PluginFiles.load(data);
        Files.writeString(ConfigFiles.configPath(data), "server_id: [unclosed\n", StandardCharsets.UTF_8);

        PluginFiles.Loaded loaded = PluginFiles.load(data);

        assertTrue(loaded.unreadableFile().isPresent(), "warnings were " + loaded.warnings());
        assertTrue(loaded.unreadableFile().get().contains(ConfigFiles.CONFIG_FILE));
    }

    @Test
    void wordlistProblemsBecomeWarnings(@TempDir Path data) throws IOException {
        PluginFiles.load(data);
        Files.writeString(data.resolve(PluginFiles.WORDLIST_FILE), "orphan\n[EN-MUTE]\nzorblax\n", StandardCharsets.UTF_8);
        Files.writeString(data.resolve(PluginFiles.RULES_FILE), "rules:\n  bad:\n    severity: MILD\n    regex:\n      - '[unclosed'\n",
                StandardCharsets.UTF_8);

        PluginFiles.Loaded loaded = PluginFiles.load(data);

        assertTrue(loaded.warnings().stream().anyMatch(warning -> warning.startsWith(PluginFiles.WORDLIST_FILE + ": line 1")));
        assertTrue(loaded.warnings().stream().anyMatch(warning -> warning.startsWith(PluginFiles.RULES_FILE + ": rules.bad.regex")));
        assertTrue(loaded.unreadableFile().isEmpty());
    }
}
