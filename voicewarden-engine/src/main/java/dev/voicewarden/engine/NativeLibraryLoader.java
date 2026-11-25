package dev.voicewarden.engine;

import com.k2fsa.sherpa.onnx.VersionInfo;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NativeLibraryLoader {

    public static final String SHERPA_VERSION = "2.0.3";
    public static final String NATIVE_PATH_PROPERTY = "sherpa_onnx.native.path";
    public static final String JNI_LIBRARY = "sherpa-onnx-jni";
    public static final String ONNXRUNTIME_LIBRARY = "onnxruntime";
    public static final String NATIVE_DIR = "native";

    private static final Logger LOG = LoggerFactory.getLogger(NativeLibraryLoader.class);
    private static final String RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/";

    private final Path modelsDir;
    private final boolean autoDownload;
    private final VerifiedDownloader downloader;
    private final NativePlatform platform;
    private final String releaseBase;

    public NativeLibraryLoader(Path modelsDir, boolean autoDownload, VerifiedDownloader downloader, NativePlatform platform) {
        this(modelsDir, autoDownload, downloader, platform, RELEASE_BASE);
    }

    NativeLibraryLoader(
            Path modelsDir,
            boolean autoDownload,
            VerifiedDownloader downloader,
            NativePlatform platform,
            String releaseBase) {
        this.releaseBase = Objects.requireNonNull(releaseBase, "releaseBase");
        this.modelsDir = Objects.requireNonNull(modelsDir, "modelsDir").toAbsolutePath().normalize();
        this.autoDownload = autoDownload;
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public static String jniFileName() {
        return System.mapLibraryName(JNI_LIBRARY);
    }

    public static String onnxRuntimeFileName() {
        return System.mapLibraryName(ONNXRUNTIME_LIBRARY);
    }

    public String jarFileName() {
        return platform.artifactName() + "-" + SHERPA_VERSION + ".jar";
    }

    public URI jarUrl() {
        return URI.create(releaseBase + "v" + SHERPA_VERSION + "/" + jarFileName());
    }

    public Path nativeRoot() {
        return modelsDir.resolve(NATIVE_DIR);
    }

    public Path extractedLibraryDir() {
        return nativeRoot().resolve(platform.id() + "-" + SHERPA_VERSION);
    }

    public static boolean bundledOnClasspath(NativePlatform platform) {
        ClassLoader loader = NativeLibraryLoader.class.getClassLoader();
        return loader.getResource(platform.resourceDirectory() + jniFileName()) != null;
    }

    public String ensureLoaded() throws IOException {
        String configured = System.getProperty(NATIVE_PATH_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            LOG.info("Loading sherpa-onnx native libraries from {}={}", NATIVE_PATH_PROPERTY, configured);
        } else if (bundledOnClasspath(platform)) {
            LOG.info("Loading sherpa-onnx native libraries for {} from the classpath", platform.id());
        } else {
            Path dir = installNativeLibraries();
            System.setProperty(NATIVE_PATH_PROPERTY, dir.toString());
            LOG.info("Loading sherpa-onnx native libraries for {} from {}", platform.id(), dir);
        }
        try {
            String version = VersionInfo.getVersion();
            LOG.info("sherpa-onnx {} (onnxruntime {}) loaded", version, VersionInfo.getOnnxruntimeVersion());
            return version;
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("Could not load the sherpa-onnx native library for " + platform.id()
                    + ": " + e.getMessage() + ". Delete " + extractedLibraryDir() + " to re-extract it, or place "
                    + jarFileName() + " from " + jarUrl() + " in " + nativeRoot() + ".", e);
        }
    }

    Path installNativeLibraries() throws IOException {
        Path dir = extractedLibraryDir();
        if (hasLibraries(dir)) {
            return dir;
        }
        Path lockFile = nativeRoot().resolve(ModelDownloader.LOCK_DIR).resolve(platform.id() + ".lock");
        return DirectoryLock.withLock(lockFile, () -> {
            if (hasLibraries(dir)) {
                return dir;
            }
            Path jar = nativeRoot().resolve(jarFileName());
            if (!Files.isRegularFile(jar)) {
                if (!autoDownload) {
                    throw new ModelUnavailableException("The sherpa-onnx native library for " + platform.id()
                            + " is not installed and automatic download is disabled. Download " + jarUrl()
                            + " and place it in " + nativeRoot() + ", then restart the engine.");
                }
                downloader.download(jarUrl(), jar, Sha256.readSidecar(Sha256.sidecarFor(jar)));
            } else {
                Optional<String> expected = Sha256.readSidecar(Sha256.sidecarFor(jar));
                if (expected.isPresent()) {
                    Sha256.verify(expected.get(), Sha256.of(jar), jar);
                }
            }
            extractFromJar(jar, platform, dir);
            return dir;
        });
    }

    static boolean hasLibraries(Path dir) {
        return Files.isRegularFile(dir.resolve(jniFileName())) && Files.isRegularFile(dir.resolve(onnxRuntimeFileName()));
    }

    static void extractFromJar(Path jar, NativePlatform platform, Path destination) throws IOException {
        String prefix = platform.resourceDirectory();
        Path staging = destination.resolveSibling(destination.getFileName() + ".extract-" + UUID.randomUUID());
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Files.createDirectories(staging);
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) {
                    continue;
                }
                String fileName = name.substring(prefix.length());
                if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\") || fileName.startsWith(".")) {
                    continue;
                }
                Path out = staging.resolve(fileName);
                try (InputStream in = jarFile.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!hasLibraries(staging)) {
                throw new IOException(jar + " does not contain " + prefix + jniFileName() + " and "
                        + prefix + onnxRuntimeFileName() + "; it is not the sherpa-onnx native jar for " + platform.id());
            }
            ArchiveExtractor.deleteRecursively(destination);
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            ArchiveExtractor.deleteRecursively(staging);
        }
    }
}
