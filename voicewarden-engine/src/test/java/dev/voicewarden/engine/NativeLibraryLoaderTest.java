package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeLibraryLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void detectsEverySupportedPlatform() {
        assertEquals(NativePlatform.LINUX_X64, NativePlatform.detect("Linux", "amd64"));
        assertEquals(NativePlatform.LINUX_AARCH64, NativePlatform.detect("Linux", "aarch64"));
        assertEquals(NativePlatform.OSX_AARCH64, NativePlatform.detect("Mac OS X", "aarch64"));
        assertEquals(NativePlatform.OSX_X64, NativePlatform.detect("Mac OS X", "x86_64"));
        assertEquals(NativePlatform.WIN_X64, NativePlatform.detect("Windows 11", "amd64"));
        assertEquals(NativePlatform.WIN_ARM64, NativePlatform.detect("Windows 11", "arm64"));
        assertThrows(IllegalStateException.class, () -> NativePlatform.detect("Linux", "riscv64"));
        assertThrows(IllegalStateException.class, () -> NativePlatform.detect("FreeBSD", "amd64"));
    }

    @Test
    void jarNameAndUrlFollowTheSherpaRelease() {
        NativeLibraryLoader loader = loader(false, NativePlatform.LINUX_AARCH64);
        assertEquals("sherpa-onnx-native-lib-linux-aarch64-2.0.3.jar", loader.jarFileName());
        assertEquals("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/"
                + "sherpa-onnx-native-lib-linux-aarch64-2.0.3.jar", loader.jarUrl().toString());
    }

    @Test
    void extractsOnlyThisPlatformsLibrariesFromAManuallyPlacedJar() throws IOException {
        NativeLibraryLoader loader = loader(false, NativePlatform.LINUX_X64);
        Files.createDirectories(loader.nativeRoot());
        writeNativeJar(loader.nativeRoot().resolve(loader.jarFileName()), NativePlatform.LINUX_X64);
        Path dir = loader.installNativeLibraries();
        assertEquals(loader.extractedLibraryDir(), dir);
        assertTrue(NativeLibraryLoader.hasLibraries(dir));
        assertEquals("jni", Files.readString(dir.resolve(NativeLibraryLoader.jniFileName())));
        assertFalse(Files.exists(dir.resolve("other.txt")));
    }

    @Test
    void downloadsTheJarWhenAutoDownloadIsOn() throws IOException {
        Path fixture = tmp.resolve("fixture.jar");
        writeNativeJar(fixture, NativePlatform.LINUX_AARCH64);
        String path = "/v2.0.3/sherpa-onnx-native-lib-linux-aarch64-2.0.3.jar";
        try (TestArchives.FileServer server = new TestArchives.FileServer(path, Files.readAllBytes(fixture))) {
            NativeLibraryLoader loader = new NativeLibraryLoader(tmp.resolve("models"), true,
                    VerifiedDownloader.createDefault(), NativePlatform.LINUX_AARCH64, server.uri("/").toString());
            Path dir = loader.installNativeLibraries();
            assertTrue(NativeLibraryLoader.hasLibraries(dir));
            assertTrue(Files.isRegularFile(loader.nativeRoot().resolve(loader.jarFileName())));
            loader.installNativeLibraries();
            assertEquals(1, server.requests.get(), "installed libraries must not be downloaded again");
        }
    }

    @Test
    void wrongPlatformJarIsRejected() throws IOException {
        NativeLibraryLoader loader = loader(false, NativePlatform.OSX_AARCH64);
        Files.createDirectories(loader.nativeRoot());
        writeNativeJar(loader.nativeRoot().resolve(loader.jarFileName()), NativePlatform.LINUX_X64);
        IOException e = assertThrows(IOException.class, loader::installNativeLibraries);
        assertTrue(e.getMessage().contains("osx-aarch64"), e.getMessage());
    }

    @Test
    void missingJarWithoutAutoDownloadExplainsWhereToPutIt() {
        NativeLibraryLoader loader = loader(false, NativePlatform.WIN_X64);
        ModelUnavailableException e = assertThrows(ModelUnavailableException.class, loader::installNativeLibraries);
        assertTrue(e.getMessage().contains(loader.jarUrl().toString()), e.getMessage());
        assertTrue(e.getMessage().contains(loader.nativeRoot().toString()), e.getMessage());
    }

    private NativeLibraryLoader loader(boolean autoDownload, NativePlatform platform) {
        return new NativeLibraryLoader(tmp.resolve("models"), autoDownload, VerifiedDownloader.createDefault(), platform);
    }

    private static void writeNativeJar(Path jar, NativePlatform platform) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            put(zip, platform.resourceDirectory() + NativeLibraryLoader.jniFileName(), "jni");
            put(zip, platform.resourceDirectory() + NativeLibraryLoader.onnxRuntimeFileName(), "ort");
            put(zip, "sherpa-onnx/native/other-platform/" + NativeLibraryLoader.jniFileName(), "wrong");
            put(zip, "other.txt", "ignored");
        }
    }

    private static void put(JarOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new JarEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
