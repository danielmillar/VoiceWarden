package dev.voicewarden.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SoftDependIsolationTest {

    private static final List<String> OPTIONAL_JARS = List.of(
            "voicechat-api", "su.plo", "floodgate", "cumulus", "geysermc", "luckperms", "placeholderapi");

    private static final Set<String> BRIDGES = Set.of(
            "dev.voicewarden.plugin.voice.SimpleVoiceChatAdapter",
            "dev.voicewarden.plugin.voice.PlasmoVoiceAdapter",
            "dev.voicewarden.plugin.consent.FloodgateConsentBridge",
            "dev.voicewarden.plugin.mute.LuckPermsBridge",
            "dev.voicewarden.plugin.text.PlaceholderApiHook",
            "dev.voicewarden.plugin.placeholder.VoiceWardenExpansion",
            "dev.voicewarden.plugin.placeholder.PlaceholderExpansionRegistrar$ExpansionHandle");

    private static boolean optional(String entry) {
        String lower = entry.toLowerCase();
        return OPTIONAL_JARS.stream().anyMatch(lower::contains);
    }

    private static Path mainClasses(List<String> classpath) {
        return classpath.stream()
                .map(Path::of)
                .filter(path -> path.endsWith(Path.of("classes", "java", "main")) && path.toString().contains("voicewarden-plugin"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("voicewarden-plugin main classes are not on the test classpath"));
    }

    private static boolean isBridge(String className) {
        return BRIDGES.stream().anyMatch(bridge -> className.equals(bridge) || className.startsWith(bridge + "$"));
    }

    @Test
    void everyNonBridgeClassLinksWithoutOptionalPlugins() throws IOException, ClassNotFoundException {
        List<String> classpath = List.of(System.getProperty("java.class.path").split(File.pathSeparator));
        assertTrue(classpath.stream().anyMatch(SoftDependIsolationTest::optional), "optional plugin jars should be on the normal test classpath");
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath) {
            if (!optional(entry) && !entry.contains(Path.of("classes", "java", "test").toString())) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        Path root = mainClasses(classpath);
        List<String> classes;
        try (Stream<Path> files = Files.walk(root)) {
            classes = files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> root.relativize(path).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", ""))
                    .sorted()
                    .toList();
        }
        assertFalse(classes.isEmpty());

        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            int linked = 0;
            for (String className : classes) {
                if (isBridge(className)) {
                    continue;
                }
                try {
                    Class.forName(className, true, loader);
                    linked++;
                } catch (LinkageError e) {
                    throw new AssertionError(className + " needs an optional plugin to load. Move the plugin type behind a bridge class. " + e, e);
                }
            }
            assertTrue(linked > 20, "expected to link most plugin classes, linked " + linked);
            assertThrows(NoClassDefFoundError.class,
                    () -> Class.forName("dev.voicewarden.plugin.voice.SimpleVoiceChatAdapter", true, loader),
                    "the isolated loader should not see Simple Voice Chat");
            assertEquals(loader, Class.forName("dev.voicewarden.plugin.voice.VoiceBackendManager", true, loader).getClassLoader());
        }
    }
}
