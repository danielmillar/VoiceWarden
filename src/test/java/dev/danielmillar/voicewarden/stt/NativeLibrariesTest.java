package dev.danielmillar.voicewarden.stt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class NativeLibrariesTest {
    @TempDir Path folder;

    @Test void offlineSetupRejectsMissingLibrariesWithoutInvokingDownloader() {
        var error = assertThrows(IOException.class, () -> NativeLibraries.ensure(folder, "", false, null, Logger.getAnonymousLogger()));
        assertTrue(error.getMessage().contains("auto-download is false"));
        assertTrue(error.getMessage().contains("https://github.com/k2-fsa/sherpa-onnx/releases/"));
        assertFalse(Files.exists(folder.resolve("natives")));
    }

    @Test void offlineSetupUsesProvisionedLibraries() throws Exception {
        Path directory = folder.resolve("natives/sherpa-onnx-" + NativeLibraries.VERSION).resolve(NativeLibraries.platform());
        Files.createDirectories(directory);
        Files.createFile(directory.resolve(System.mapLibraryName("sherpa-onnx-jni")));
        Files.createFile(directory.resolve(System.mapLibraryName("onnxruntime")));
        assertEquals(directory.toAbsolutePath(), NativeLibraries.ensure(folder, "", false, null, Logger.getAnonymousLogger()));
    }
}
