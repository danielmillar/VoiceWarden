package dev.voicewarden.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModelDownloader {

    public static final String READY_MARKER = ".ready";
    public static final String LOCK_DIR = ".locks";

    private static final Logger LOG = LoggerFactory.getLogger(ModelDownloader.class);

    private final Path modelsDir;
    private final boolean autoDownload;
    private final VerifiedDownloader downloader;

    public ModelDownloader(Path modelsDir, boolean autoDownload, VerifiedDownloader downloader) {
        this.modelsDir = Objects.requireNonNull(modelsDir, "modelsDir").toAbsolutePath().normalize();
        this.autoDownload = autoDownload;
        this.downloader = Objects.requireNonNull(downloader, "downloader");
    }

    public Path ensure(ModelArtifact artifact) throws IOException {
        Path target = artifact.directoryIn(modelsDir);
        if (isReadyInstalled(artifact, target)) {
            return target;
        }
        Files.createDirectories(modelsDir);
        Path lockFile = modelsDir.resolve(LOCK_DIR).resolve(artifact.id() + ".lock");
        return DirectoryLock.withLock(lockFile, () -> {
            if (isInstalledUnderLock(artifact, target)) {
                return target;
            }
            install(artifact, target);
            return target;
        });
    }

    private boolean isReadyInstalled(ModelArtifact artifact, Path target) throws IOException {
        return Files.isRegularFile(target.resolve(READY_MARKER)) && ModelFiles.isComplete(artifact.kind(), target)
                && pinnedFileIntact(artifact, target);
    }

    private boolean isInstalledUnderLock(ModelArtifact artifact, Path target) throws IOException {
        if (!ModelFiles.isComplete(artifact.kind(), target)) {
            return false;
        }
        if (!pinnedFileIntact(artifact, target)) {
            LOG.warn("Model file {} does not match its pinned SHA-256; installing a verified copy",
                    target.resolve(artifact.archiveFileName()));
            return false;
        }
        Path marker = target.resolve(READY_MARKER);
        if (!Files.isRegularFile(marker)) {
            LOG.info("Using manually placed model directory {}", target);
            writeReadyMarker(target, "manual");
        }
        return true;
    }

    private static boolean pinnedFileIntact(ModelArtifact artifact, Path target) throws IOException {
        if (artifact.packaging() != ModelArtifact.Packaging.SINGLE_FILE || artifact.expectedSha256() == null) {
            return true;
        }
        Path file = target.resolve(artifact.archiveFileName());
        return Files.isRegularFile(file) && artifact.expectedSha256().equalsIgnoreCase(Sha256.of(file));
    }

    private void install(ModelArtifact artifact, Path target) throws IOException {
        Path archive = modelsDir.resolve(artifact.archiveFileName());
        if (Files.isRegularFile(archive)) {
            LOG.info("Installing model {} from local archive {}", artifact.id(), archive);
            Optional<String> expected = Sha256.expected(artifact.expectedSha256(), archive);
            if (expected.isPresent()) {
                Sha256.verify(expected.get(), Sha256.of(archive), archive);
            } else {
                LOG.warn("No SHA-256 is pinned for local archive {}; extracting it unverified", archive);
            }
            extractAndPublish(artifact, archive, target, "archive");
            return;
        }
        if (!autoDownload) {
            String alternative = artifact.packaging() == ModelArtifact.Packaging.TAR_BZ2
                    ? " (or extract it so " + target + " holds the model files)"
                    : " (or copy it into " + target + ")";
            throw new ModelUnavailableException("Model '" + artifact.id() + "' is not installed and automatic download "
                    + "is disabled. Download " + artifact.downloadUrl() + " and place '" + artifact.archiveFileName()
                    + "' in " + modelsDir + alternative + ", then restart the engine.");
        }
        Path part = modelsDir.resolve(artifact.archiveFileName() + ".download");
        try {
            downloader.download(artifact.downloadUrl(), part, Sha256.expected(artifact.expectedSha256(), archive));
            extractAndPublish(artifact, part, target, "download");
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private void extractAndPublish(ModelArtifact artifact, Path archive, Path target, String source)
            throws IOException {
        Path staging = modelsDir.resolve("." + artifact.id() + ".extract-" + UUID.randomUUID());
        try {
            Path root = unpack(artifact, archive, staging);
            ModelFiles.discover(artifact.kind(), root);
            writeReadyMarker(root, source);
            ArchiveExtractor.deleteRecursively(target);
            Files.move(root, target, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("Model {} is ready in {}", artifact.id(), target);
        } finally {
            ArchiveExtractor.deleteRecursively(staging);
        }
    }

    private static Path unpack(ModelArtifact artifact, Path archive, Path staging) throws IOException {
        return switch (artifact.packaging()) {
            case TAR_BZ2 -> {
                ArchiveExtractor.extractTarBz2(archive, staging);
                yield locateModelRoot(staging, artifact);
            }
            case SINGLE_FILE -> {
                Files.createDirectories(staging);
                Files.copy(archive, staging.resolve(artifact.archiveFileName()));
                yield staging;
            }
        };
    }

    private static Path locateModelRoot(Path staging, ModelArtifact artifact) throws IOException {
        Path named = staging.resolve(artifact.id());
        if (Files.isDirectory(named)) {
            return named;
        }
        List<Path> children;
        try (Stream<Path> list = Files.list(staging)) {
            children = list.toList();
        }
        if (children.size() == 1 && Files.isDirectory(children.get(0))) {
            return children.get(0);
        }
        return staging;
    }

    private static void writeReadyMarker(Path target, String source) throws IOException {
        Path marker = target.resolve(READY_MARKER);
        Path tmp = target.resolve(READY_MARKER + ".tmp-" + UUID.randomUUID());
        Files.writeString(tmp, "source=" + source + "\ninstalled=" + Instant.now() + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
