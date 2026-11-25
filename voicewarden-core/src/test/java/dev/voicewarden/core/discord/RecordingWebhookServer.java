package dev.voicewarden.core.discord;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

final class RecordingWebhookServer implements AutoCloseable {

    record Received(String method, String path, String contentType, byte[] body, long receivedNanos) {

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    record Reply(int status, Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();

    RecordingWebhookServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body, System.nanoTime()));
            Reply reply = replies.poll();
            if (reply == null) {
                reply = new Reply(204, Map.of(), "");
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/123/secret-token";
    }

    void reply(int status, Map<String, String> headers, String body) {
        replies.add(new Reply(status, headers, body));
    }

    void reply(int status) {
        reply(status, Map.of(), "");
    }

    List<Received> received() {
        return received;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
