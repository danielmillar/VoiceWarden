package dev.voicewarden.engine;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

final class TestArchives {

    private TestArchives() {
    }

    static Map<String, String> transducerLayout(String root) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(root + "/encoder.onnx", "encoder");
        files.put(root + "/decoder.onnx", "decoder");
        files.put(root + "/joiner.onnx", "joiner");
        files.put(root + "/tokens.txt", "a 0\nb 1\n");
        files.put(root + "/README.md", "test model");
        return files;
    }

    static void writeTarBz2(Path archive, Map<String, String> files) throws IOException {
        try (OutputStream out = Files.newOutputStream(archive);
             BZip2CompressorOutputStream bzip = new BZip2CompressorOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(bzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] data = file.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey(), true);
                entry.setSize(data.length);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        }
    }

    static void writeTarBz2WithSymlink(Path archive, String name, String target) throws IOException {
        try (OutputStream out = Files.newOutputStream(archive);
             BZip2CompressorOutputStream bzip = new BZip2CompressorOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(bzip)) {
            TarArchiveEntry link = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK, true);
            link.setLinkName(target);
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }
    }

    static void touch(Path dir, String... names) throws IOException {
        Files.createDirectories(dir);
        for (String name : names) {
            Files.writeString(dir.resolve(name), name);
        }
    }

    static final class FileServer implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();

        FileServer(String path, byte[] body) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/redirect" + path, exchange -> {
                exchange.getResponseHeaders().add("Location", path);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext(path, exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.createContext("/missing", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
