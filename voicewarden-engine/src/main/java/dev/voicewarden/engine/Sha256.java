package dev.voicewarden.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

final class Sha256 {

    static final String SIDECAR_SUFFIX = ".sha256";

    private static final int HEX_LENGTH = 64;
    private static final int BUFFER = 1 << 16;

    private Sha256() {
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    static boolean isValidHex(String value) {
        if (value == null || value.length() != HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!HexFormat.isHexDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    static String of(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest);
    }

    static Path sidecarFor(Path archive) {
        return archive.resolveSibling(archive.getFileName() + SIDECAR_SUFFIX);
    }

    static Optional<String> readSidecar(Path sidecar) throws IOException {
        if (!Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        String content = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
        String first = content.isEmpty() ? "" : content.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!isValidHex(first)) {
            throw new IOException("Checksum file " + sidecar + " must start with a 64 character SHA-256 hex digest");
        }
        return Optional.of(first);
    }

    static Optional<String> expected(String pinned, Path archive) throws IOException {
        if (pinned != null) {
            return Optional.of(pinned);
        }
        return readSidecar(sidecarFor(archive));
    }

    static void verify(String expected, String actual, Path file) throws IOException {
        if (!expected.equalsIgnoreCase(actual)) {
            throw new ChecksumMismatchException("SHA-256 mismatch for " + file + ": expected " + expected + " but got "
                    + actual + ". The file is corrupt or was tampered with; delete it and download it again.");
        }
    }

    static final class ChecksumMismatchException extends IOException {
        private static final long serialVersionUID = 1L;

        ChecksumMismatchException(String message) {
            super(message);
        }
    }
}
