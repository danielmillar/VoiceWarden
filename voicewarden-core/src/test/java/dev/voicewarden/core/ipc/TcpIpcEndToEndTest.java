package dev.voicewarden.core.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SocketChannel;
import org.junit.jupiter.api.Test;

class TcpIpcEndToEndTest extends AbstractIpcEndToEndTest {

    @Override
    IpcEndpoint newEndpoint() {
        return new IpcEndpoint.Tcp(0);
    }

    @Test
    void bindsLoopbackOnlyWithNoDelay() throws Exception {
        startServer(HANDSHAKE);
        IpcEndpoint.Tcp bound = (IpcEndpoint.Tcp) server.endpoint();
        assertNotEquals(0, bound.port());
        assertEquals("127.0.0.1", ((InetSocketAddress) bound.address()).getAddress().getHostAddress());
        try (SocketChannel channel = IpcTransport.connect(bound)) {
            assertTrue(channel.getOption(StandardSocketOptions.TCP_NODELAY));
        }
    }

    @Test
    void ephemeralEndpointHasFixedPortForTheChild() {
        IpcEndpoint.Tcp endpoint = IpcEndpoint.Tcp.ephemeral();
        assertNotEquals(0, endpoint.port());
        assertEquals(endpoint, IpcEndpoint.fromEngineArgs(endpoint.toEngineArgs().toArray(String[]::new)));
    }
}
