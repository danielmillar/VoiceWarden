package dev.voicewarden.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

final class DirectoryLock {

    private static final ConcurrentMap<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private DirectoryLock() {
    }

    interface IoAction<T> {
        T run() throws IOException;
    }

    static <T> T withLock(Path lockFile, IoAction<T> action) throws IOException {
        Path key = lockFile.toAbsolutePath().normalize();
        Files.createDirectories(key.getParent());
        ReentrantLock local = IN_PROCESS.computeIfAbsent(key, k -> new ReentrantLock());
        local.lock();
        try (FileChannel channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return action.run();
        } finally {
            local.unlock();
        }
    }
}
