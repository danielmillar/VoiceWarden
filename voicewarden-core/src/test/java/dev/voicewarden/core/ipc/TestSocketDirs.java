package dev.voicewarden.core.ipc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class TestSocketDirs {

    private TestSocketDirs() {
    }

    static Path create() throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        Path shortBase = Path.of("/tmp");
        if (base.toString().length() > 40 && Files.isDirectory(shortBase)) {
            base = shortBase;
        }
        return Files.createTempDirectory(base, "vwipc");
    }

    static void delete(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
