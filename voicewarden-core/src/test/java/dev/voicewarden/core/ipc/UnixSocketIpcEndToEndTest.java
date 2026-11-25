package dev.voicewarden.core.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnixSocketIpcEndToEndTest extends AbstractIpcEndToEndTest {

    private Path dir;

    @BeforeEach
    void createDir() throws IOException {
        assumeFalse(IpcEndpoint.isWindows());
        dir = TestSocketDirs.create();
    }

    @AfterEach
    void deleteDir() throws IOException {
        TestSocketDirs.delete(dir);
    }

    @Override
    IpcEndpoint newEndpoint() {
        return new IpcEndpoint.Unix(dir.resolve("engine.sock"));
    }

    @Test
    void socketFileIsOwnerOnlyAndRemovedOnClose() throws Exception {
        startServer(HANDSHAKE);
        Path socket = ((IpcEndpoint.Unix) server.endpoint()).socketPath();
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)));
        server.close();
        assertFalse(Files.exists(socket));
    }

    @Test
    void createsMissingRuntimeDirectoryOwnerOnly() throws Exception {
        Path nested = dir.resolve("run");
        IpcEndpoint endpoint = new IpcEndpoint.Unix(nested.resolve("e.sock"));
        try (ServerSocketChannel ignored = IpcTransport.bind(endpoint)) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(nested)));
        }
    }

    @Test
    void replacesStaleSocketFile() throws Exception {
        IpcEndpoint endpoint = newEndpoint();
        ServerSocketChannel crashed = IpcTransport.bind(endpoint);
        crashed.close();
        assertTrue(Files.exists(((IpcEndpoint.Unix) endpoint).socketPath()));
        server = new EchoEngineServer(endpoint, token, HANDSHAKE, "ENGLISH");
        try (EngineClient client = EngineClient.connect(endpoint, token, HANDSHAKE, EngineResultListener.NONE)) {
            assertTrue(client.isConnected());
        }
    }

    @Test
    void refusesToStealLiveSocket() throws Exception {
        startServer(HANDSHAKE);
        assertThrows(IOException.class, () -> IpcTransport.bind(server.endpoint()));
    }

    @Test
    void refusesToReplaceRegularFile() throws Exception {
        Path file = Files.writeString(dir.resolve("engine.sock"), "not a socket");
        assertThrows(IOException.class, () -> IpcTransport.bind(new IpcEndpoint.Unix(file)));
        assertTrue(Files.exists(file));
    }

    @Test
    void rejectsPathOverOsLimit() {
        Path tooLong = dir.resolve("x".repeat(IpcEndpoint.Unix.MAX_PATH_BYTES));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new IpcEndpoint.Unix(tooLong));
        assertTrue(e.getMessage().contains("shorter runtime directory"));
    }

    @Test
    void roundTripsEngineArgs() {
        IpcEndpoint endpoint = newEndpoint();
        assertEquals(endpoint, IpcEndpoint.fromEngineArgs(endpoint.toEngineArgs().toArray(String[]::new)));
    }
}
