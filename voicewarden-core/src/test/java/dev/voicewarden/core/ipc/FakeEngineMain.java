package dev.voicewarden.core.ipc;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class FakeEngineMain {

    static final UUID CRASH = new UUID(0xC0FFEEL, 1L);
    static final UUID HANG = new UUID(0xC0FFEEL, 2L);
    static final UUID FREEZE = new UUID(0xC0FFEEL, 3L);
    static final String EXIT_ON_START = "--exit-on-start";
    static final int CRASH_EXIT_CODE = 42;

    private static final long SELF_DESTRUCT_MS = 90_000;

    private static volatile boolean hung;

    private FakeEngineMain() {
    }

    public static void main(String[] args) throws IOException {
        armSafetyNets();
        List<String> argList = Arrays.asList(args);
        if (argList.contains(EXIT_ON_START)) {
            System.err.println("fake-engine refusing to start");
            System.exit(3);
        }
        IpcAuthToken token = IpcAuthToken.fromEnvironment();
        IpcEndpoint endpoint = IpcEndpoint.fromEngineArgs(args);
        String profile = argList.get(argList.indexOf(EngineProcessConfig.ARG_PROFILE) + 1);
        try (IpcServerChannel server = IpcServerChannel.bind(endpoint, token, "fake-1", profile)) {
            server.onRejection(reason -> System.err.println("fake-engine rejected client: " + reason));
            System.out.println("fake-engine listening pid=" + ProcessHandle.current().pid() + " args=" + String.join(" ", args));
            System.err.println("fake-engine stderr ready");
            while (true) {
                IpcConnection connection = server.accept();
                Thread serving = new Thread(() -> serve(connection), "fake-engine-conn");
                serving.setDaemon(true);
                serving.start();
            }
        }
    }

    private static void serve(IpcConnection connection) {
        try (connection) {
            while (true) {
                IpcMessage message = connection.receive();
                if (message instanceof IpcMessage.DropUtterance drop && drop.playerId().equals(CRASH)) {
                    System.out.println("fake-engine crashing");
                    System.out.flush();
                    Runtime.getRuntime().halt(CRASH_EXIT_CODE);
                }
                if (message instanceof IpcMessage.DropUtterance drop && drop.playerId().equals(HANG)) {
                    hung = true;
                }
                if (message instanceof IpcMessage.DropUtterance drop && drop.playerId().equals(FREEZE)) {
                    System.out.println("fake-engine frozen");
                    freezeForever();
                }
                if (message instanceof IpcMessage.Shutdown) {
                    System.out.println("fake-engine shutting down");
                    System.exit(0);
                }
                if (message instanceof IpcMessage.Ping && hung) {
                    continue;
                }
                IpcMessage reply = EchoEngineServer.reply(message);
                if (reply != null) {
                    connection.send(reply);
                }
            }
        } catch (IOException e) {
            return;
        }
    }

    private static void freezeForever() {
        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException ignored) {
                continue;
            }
        }
    }

    private static void armSafetyNets() {
        ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> Runtime.getRuntime().halt(8)));
        Thread selfDestruct = new Thread(() -> {
            try {
                Thread.sleep(SELF_DESTRUCT_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            Runtime.getRuntime().halt(9);
        }, "fake-engine-self-destruct");
        selfDestruct.setDaemon(true);
        selfDestruct.start();
    }
}
