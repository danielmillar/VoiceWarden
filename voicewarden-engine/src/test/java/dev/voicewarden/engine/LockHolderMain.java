package dev.voicewarden.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class LockHolderMain {

    private static final long GIVE_UP_MS = 30_000;

    private LockHolderMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Path lockFile = Path.of(args[0]);
        Path ready = Path.of(args[1]);
        Path release = Path.of(args[2]);
        Files.createDirectories(lockFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            Files.writeString(ready, "locked");
            long deadline = System.currentTimeMillis() + GIVE_UP_MS;
            while (!Files.exists(release) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }
    }
}
