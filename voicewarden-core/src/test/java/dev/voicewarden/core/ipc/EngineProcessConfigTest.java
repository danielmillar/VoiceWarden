package dev.voicewarden.core.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EngineProcessConfigTest {

    private static final IpcEndpoint ENDPOINT = new IpcEndpoint.Tcp(41_234);

    @Test
    void buildsNicedJarCommandWithEngineArgs() {
        EngineProcessConfig config = EngineProcessConfig.builder(new EngineLaunchTarget.Jar(Path.of("/opt/vw/engine.jar")), ENDPOINT)
                .javaExecutable(Path.of("/jdk/bin/java"))
                .jvmArgs(List.of("-Xmx1g"))
                .profile("MULTILANG")
                .modelsDir(Path.of("/srv/models"))
                .threads(3)
                .niceLevel(12)
                .build();

        assertEquals(List.of(
                        "/usr/bin/nice", "-n", "12",
                        "/jdk/bin/java", "-Xmx1g", "-jar", "/opt/vw/engine.jar",
                        "--ipc-port", "41234",
                        "--profile", "MULTILANG",
                        "--models-dir", "/srv/models",
                        "--threads", "3"),
                config.command(Optional.of(Path.of("/usr/bin/nice"))));
        assertEquals("/jdk/bin/java", config.command(Optional.empty()).get(0));
    }

    @Test
    void commandNeverCarriesTheToken() {
        IpcAuthToken token = IpcAuthToken.generate();
        EngineProcessConfig config = EngineProcessConfig.builder(new EngineLaunchTarget.MainClass("cp", "Main"), ENDPOINT).build();
        EngineProcessSupervisor supervisor = new EngineProcessSupervisor(
                config, token, EngineResultListener.NONE, EngineSupervisorListener.NONE, EngineLogSink.slf4j());
        for (String arg : supervisor.command()) {
            assertFalse(arg.contains(token.toHex()), arg);
        }
        assertEquals("VOICEWARDEN_IPC_TOKEN", IpcAuthToken.ENV_VAR);
    }

    @Test
    void skipsNiceWhenPriorityLoweringIsOff() {
        EngineProcessConfig config = EngineProcessConfig.builder(new EngineLaunchTarget.MainClass("cp", "Main"), ENDPOINT)
                .lowerPriority(false)
                .build();
        assertTrue(config.resolveNice().isEmpty());
    }

    @Test
    void ignoresMissingConfiguredNiceBinary() {
        EngineProcessConfig config = EngineProcessConfig.builder(new EngineLaunchTarget.MainClass("cp", "Main"), ENDPOINT)
                .niceExecutable(Path.of("/definitely/not/nice"))
                .build();
        assertTrue(config.resolveNice().isEmpty());
    }

    @Test
    void validatesSettings() {
        EngineProcessConfig.Builder builder = EngineProcessConfig.builder(new EngineLaunchTarget.MainClass("cp", "Main"), ENDPOINT);
        assertThrows(IllegalArgumentException.class, () -> builder.threads(0).build());
        builder.threads(1);
        assertThrows(IllegalArgumentException.class, () -> builder.niceLevel(20).build());
        builder.niceLevel(10);
        assertThrows(IllegalArgumentException.class, () -> builder.heartbeatInterval(java.time.Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> EngineProcessConfig.builder(
                new EngineLaunchTarget.MainClass("cp", "Main"), new IpcEndpoint.Tcp(0)).build());
    }
}
