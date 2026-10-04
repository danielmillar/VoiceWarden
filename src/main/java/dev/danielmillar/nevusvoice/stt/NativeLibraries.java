package dev.danielmillar.nevusvoice.stt;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Provides the sherpa-onnx JNI library and ONNX Runtime for this OS/CPU. Only the ~190 KB of Java bindings ship in the
 * plugin jar; the matching ~8-13 MB native bundle is downloaded once from the official sherpa-onnx GitHub release,
 * verified against a pinned SHA-256, and extracted to {@code plugins/NevusVoice/natives/}.
 */
public final class NativeLibraries {

    /** Must match the sherpa-onnx-jvm version in build.gradle.kts; injected at build time. */
    public static final String VERSION = buildProperty("sherpaOnnxVersion", "1.13.8");

    private static final Map<String, Map<String, String>> SHA256 = Map.of(
            "1.13.8", Map.of(
                    "linux-x64", "30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba",
                    "linux-aarch64", "5123d2e48ae1a7ce82ba89ce153906c651bf418a63db2f7dff82265e6d49c104",
                    "osx-x64", "9190c28951d85efdbd376bae6b6dff12993311ad605945289e8459bf9c886a96",
                    "osx-aarch64", "42e272180c8836127f024f3335d7afcdfb30fe0164b78330d5d449034e34ce34",
                    "win-x64", "33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039",
                    "win-arm64", "986660bef51f0ca4f5635b763c172c64bb056eabebcd319f42185d7b23337fb8"));

    private static final String PATH_PROPERTY = "sherpa_onnx.native.path";

    private NativeLibraries() {
    }

    /**
     * @param overrideDir user-provided directory, or blank to download automatically
     * @return the directory containing the native libraries
     */
    public static Path ensure(Path dataFolder, String overrideDir, Downloader downloader, Logger logger)
            throws IOException, InterruptedException {
        String jni = System.mapLibraryName("sherpa-onnx-jni");
        String ort = System.mapLibraryName("onnxruntime");
        if (!overrideDir.isBlank()) {
            Path dir = Path.of(overrideDir).toAbsolutePath();
            if (!Files.isRegularFile(dir.resolve(jni))) {
                throw new IOException("speech-to-text.native-library-path " + dir + " does not contain " + jni);
            }
            return dir;
        }
        String platform = platform();
        Path dir = dataFolder.resolve("natives").resolve("sherpa-onnx-" + VERSION).resolve(platform).toAbsolutePath();
        if (Files.isRegularFile(dir.resolve(jni)) && Files.isRegularFile(dir.resolve(ort))) {
            return dir;
        }
        String sha = SHA256.getOrDefault(VERSION, Map.of()).get(platform);
        if (sha == null) {
            logger.warning("No pinned checksum for sherpa-onnx " + VERSION + " " + platform + "; downloading unverified");
        }
        URI uri = URI.create("https://github.com/k2-fsa/sherpa-onnx/releases/download/v" + VERSION
                + "/sherpa-onnx-native-lib-" + platform + "-" + VERSION + ".jar");
        Path jar = dir.resolveSibling(platform + ".jar");
        downloader.download(uri, jar, -1, sha, "speech engine native libraries (" + platform + ")");
        Files.createDirectories(dir);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String name : new String[]{ort, jni}) {
                ZipEntry entry = zip.getEntry("sherpa-onnx/native/" + platform + "/" + name);
                if (entry == null) {
                    throw new IOException(name + " missing from " + uri);
                }
                Path tmp = dir.resolve(name + ".tmp");
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(jar);
        }
        return dir;
    }

    /** Points sherpa-onnx's own loader at {@code dir}; libraries load on first engine construction. */
    public static void use(Path dir) {
        System.setProperty(PATH_PROPERTY, dir.toAbsolutePath().toString());
    }

    /** Platform id as used by sherpa-onnx release assets, e.g. {@code linux-x64}, {@code osx-aarch64}. */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o;
        if (os.contains("mac") || os.contains("darwin")) {
            o = "osx";
        } else if (os.contains("win")) {
            o = "win";
        } else if (os.contains("nux")) {
            o = "linux";
        } else {
            throw new IllegalStateException("Unsupported operating system: " + os);
        }
        String a;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            a = "x64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            a = o.equals("win") ? "arm64" : "aarch64";
        } else {
            throw new IllegalStateException("Unsupported CPU architecture: " + arch + " (need x64 or arm64)");
        }
        return o + "-" + a;
    }

    private static String buildProperty(String key, String fallback) {
        try (InputStream in = NativeLibraries.class.getClassLoader().getResourceAsStream("nevusvoice-build.properties")) {
            if (in == null) {
                return fallback;
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty(key, fallback);
            return v.contains("${") ? fallback : v;
        } catch (IOException e) {
            return fallback;
        }
    }
}
