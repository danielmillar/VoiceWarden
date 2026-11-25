package dev.voicewarden.engine;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

final class ArchiveExtractor {

    static final long DEFAULT_MAX_EXTRACTED_BYTES = 16L * 1024 * 1024 * 1024;

    private static final int BUFFER = 1 << 16;
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("[A-Za-z]:");

    private ArchiveExtractor() {
    }

    static void extractTarBz2(Path archive, Path destination) throws IOException {
        extractTarBz2(archive, destination, DEFAULT_MAX_EXTRACTED_BYTES);
    }

    static void extractTarBz2(Path archive, Path destination, long maxExtractedBytes) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long total = 0;
        try (InputStream file = Files.newInputStream(archive);
             InputStream buffered = new BufferedInputStream(file, BUFFER);
             BZip2CompressorInputStream bzip = new BZip2CompressorInputStream(buffered, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(bzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path target = resolveInside(root, entry.getName(), archive);
                if (target.equals(root)) {
                    if (!entry.isDirectory()) {
                        throw unsafe(entry.getName(), archive);
                    }
                } else if (entry.isDirectory()) {
                    requireRealParentInside(root, target, entry.getName(), archive);
                    Files.createDirectories(target);
                } else if (entry.isSymbolicLink()) {
                    createSymlink(root, target, entry, archive);
                } else if (entry.isLink()) {
                    Path source = resolveInside(root, entry.getLinkName(), archive);
                    if (!source.toRealPath().startsWith(root.toRealPath())) {
                        throw unsafe(entry.getName() + " => " + entry.getLinkName(), archive);
                    }
                    requireRealParentInside(root, target, entry.getName(), archive);
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                } else if (entry.isFile()) {
                    requireRealParentInside(root, target, entry.getName(), archive);
                    total = copyBounded(tar, target, total, maxExtractedBytes, archive);
                }
            }
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static Path resolveInside(Path root, String entryName, Path archive) throws IOException {
        String name = entryName.replace('\\', '/');
        if (name.isEmpty() || name.startsWith("/") || WINDOWS_DRIVE.matcher(name).lookingAt()) {
            throw unsafe(entryName, archive);
        }
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root)) {
            throw unsafe(entryName, archive);
        }
        return resolved;
    }

    private static void requireRealParentInside(Path root, Path target, String entryName, Path archive)
            throws IOException {
        Files.createDirectories(target.getParent());
        if (!target.getParent().toRealPath().startsWith(root.toRealPath())) {
            throw unsafe(entryName, archive);
        }
    }

    private static void createSymlink(Path root, Path target, TarArchiveEntry entry, Path archive) throws IOException {
        String linkName = entry.getLinkName().replace('\\', '/');
        boolean climbs = Arrays.asList(linkName.split("/")).contains("..");
        if (linkName.isEmpty() || linkName.startsWith("/") || WINDOWS_DRIVE.matcher(linkName).lookingAt() || climbs) {
            throw unsafe(entry.getName() + " -> " + entry.getLinkName(), archive);
        }
        requireRealParentInside(root, target, entry.getName(), archive);
        Files.deleteIfExists(target);
        Files.createSymbolicLink(target, Path.of(linkName));
    }

    private static long copyBounded(InputStream in, Path target, long total, long max, Path archive) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long written = total;
        Files.deleteIfExists(target);
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > max) {
                    throw new IOException("Archive " + archive + " expands past " + max
                            + " bytes; refusing to extract it");
                }
                out.write(buffer, 0, read);
            }
        }
        return written;
    }

    private static IOException unsafe(String entryName, Path archive) {
        return new UnsafeArchiveException("Archive " + archive + " contains an unsafe entry '" + entryName
                + "' that would write outside the model directory; refusing to extract it");
    }

    static final class UnsafeArchiveException extends IOException {
        private static final long serialVersionUID = 1L;

        UnsafeArchiveException(String message) {
            super(message);
        }
    }
}
