package dev.voicewarden.engine;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public record ModelArtifact(
        String id,
        String archiveFileName,
        URI downloadUrl,
        String expectedSha256,
        ModelKind kind,
        String modelType,
        Packaging packaging) {

    public static final String RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/";

    public enum Packaging {
        TAR_BZ2,
        SINGLE_FILE
    }

    public ModelArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(archiveFileName, "archiveFileName");
        Objects.requireNonNull(downloadUrl, "downloadUrl");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(modelType, "modelType");
        Objects.requireNonNull(packaging, "packaging");
        if (id.isBlank() || id.contains("/") || id.contains("\\") || id.startsWith(".")) {
            throw new IllegalArgumentException("Model id must be a plain directory name, got '" + id + "'");
        }
        if (archiveFileName.isBlank() || archiveFileName.startsWith(".") || archiveFileName.contains("/")
                || archiveFileName.contains("\\")) {
            throw new IllegalArgumentException("Archive file name must be a plain file name, got '" + archiveFileName + "'");
        }
        if (expectedSha256 != null) {
            expectedSha256 = expectedSha256.trim().toLowerCase(Locale.ROOT);
            if (!Sha256.isValidHex(expectedSha256)) {
                throw new IllegalArgumentException("expectedSha256 must be 64 hex characters for " + id);
            }
        }
    }

    public static ModelArtifact sherpaRelease(String id, String sha256, ModelKind kind, String modelType) {
        String archive = id + ".tar.bz2";
        return new ModelArtifact(id, archive, URI.create(RELEASE_BASE + archive), sha256, kind, modelType,
                Packaging.TAR_BZ2);
    }

    public static ModelArtifact sherpaReleaseFile(String id, String fileName, String sha256, ModelKind kind) {
        return new ModelArtifact(id, fileName, URI.create(RELEASE_BASE + fileName), sha256, kind, "",
                Packaging.SINGLE_FILE);
    }

    public Optional<String> pinnedSha256() {
        return Optional.ofNullable(expectedSha256);
    }

    public Path directoryIn(Path modelsDir) {
        return modelsDir.resolve(id);
    }
}
