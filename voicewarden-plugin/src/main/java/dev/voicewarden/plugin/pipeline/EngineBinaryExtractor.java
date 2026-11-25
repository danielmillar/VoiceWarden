package dev.voicewarden.plugin.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class EngineBinaryExtractor {

    public static final String RESOURCE = "/META-INF/voicewarden-engine.jar.bin";

    public enum Result {
        EXTRACTED,
        UNCHANGED,
        MISSING_RESOURCE
    }

    private EngineBinaryExtractor() {
    }

    public static Path target(Path dataFolder) {
        return dataFolder.resolve(ConfigBridges.ENGINE_DIRECTORY).resolve(ConfigBridges.ENGINE_JAR);
    }

    public static Result extract(Supplier<InputStream> resource, Path target) throws IOException {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(target, "target");
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "voicewarden-engine-", ".jar.part");
        try {
            Optional<String> bundledHash = copy(resource, temp);
            if (bundledHash.isEmpty()) {
                return Result.MISSING_RESOURCE;
            }
            if (Files.isRegularFile(target) && bundledHash.get().equals(sha256(target))) {
                return Result.UNCHANGED;
            }
            move(temp, target);
            return Result.EXTRACTED;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Optional<String> copy(Supplier<InputStream> resource, Path temp) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream raw = resource.get()) {
            if (raw == null) {
                return Optional.empty();
            }
            try (InputStream in = new DigestInputStream(raw, digest); OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
        }
        return Optional.of(HexFormat.of().formatHex(digest.digest()));
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
