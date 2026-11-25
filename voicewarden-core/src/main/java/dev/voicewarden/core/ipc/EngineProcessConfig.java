package dev.voicewarden.core.ipc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EngineProcessConfig(
        Path javaExecutable,
        EngineLaunchTarget launchTarget,
        List<String> jvmArgs,
        IpcEndpoint endpoint,
        String profile,
        Path modelsDir,
        int threads,
        List<String> extraEngineArgs,
        Path workingDirectory,
        boolean lowerPriority,
        int niceLevel,
        Path niceExecutable,
        Duration startupTimeout,
        Duration handshakeTimeout,
        Duration heartbeatInterval,
        int maxMissedHeartbeats,
        Duration initialBackoff,
        Duration maxBackoff,
        int maxRestartsInWindow,
        Duration restartWindow,
        Duration shutdownGrace) {

    public static final String ARG_PROFILE = "--profile";
    public static final String ARG_MODELS_DIR = "--models-dir";
    public static final String ARG_THREADS = "--threads";

    private static final List<Path> NICE_CANDIDATES = List.of(Path.of("/usr/bin/nice"), Path.of("/bin/nice"));

    public EngineProcessConfig {
        Objects.requireNonNull(javaExecutable, "javaExecutable");
        Objects.requireNonNull(launchTarget, "launchTarget");
        jvmArgs = List.copyOf(jvmArgs);
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(modelsDir, "modelsDir");
        extraEngineArgs = List.copyOf(extraEngineArgs);
        requirePositive(startupTimeout, "startupTimeout");
        requirePositive(handshakeTimeout, "handshakeTimeout");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(initialBackoff, "initialBackoff");
        requirePositive(maxBackoff, "maxBackoff");
        requirePositive(restartWindow, "restartWindow");
        requirePositive(shutdownGrace, "shutdownGrace");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        if (maxMissedHeartbeats < 1) {
            throw new IllegalArgumentException("maxMissedHeartbeats must be at least 1, got " + maxMissedHeartbeats);
        }
        if (maxRestartsInWindow < 1) {
            throw new IllegalArgumentException("maxRestartsInWindow must be at least 1, got " + maxRestartsInWindow);
        }
        if (niceLevel < 0 || niceLevel > 19) {
            throw new IllegalArgumentException("niceLevel must be 0..19, got " + niceLevel);
        }
        if (endpoint instanceof IpcEndpoint.Tcp tcp && tcp.port() == 0) {
            throw new IllegalArgumentException("Engine TCP endpoint needs a fixed port; use IpcEndpoint.Tcp.ephemeral()");
        }
    }

    public static Builder builder(EngineLaunchTarget launchTarget, IpcEndpoint endpoint) {
        return new Builder(launchTarget, endpoint);
    }

    public static Path currentJavaExecutable() {
        String binary = IpcEndpoint.isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", binary);
    }

    public Optional<Path> resolveNice() {
        if (!lowerPriority || IpcEndpoint.isWindows()) {
            return Optional.empty();
        }
        if (niceExecutable != null) {
            return Files.isExecutable(niceExecutable) ? Optional.of(niceExecutable) : Optional.empty();
        }
        return NICE_CANDIDATES.stream().filter(Files::isExecutable).findFirst();
    }

    public List<String> command(Optional<Path> nice) {
        List<String> command = new ArrayList<>();
        nice.ifPresent(path -> {
            command.add(path.toString());
            command.add("-n");
            command.add(Integer.toString(niceLevel));
        });
        command.add(javaExecutable.toString());
        command.addAll(jvmArgs);
        command.addAll(launchTarget.toJavaArgs());
        command.addAll(endpoint.toEngineArgs());
        command.add(ARG_PROFILE);
        command.add(profile);
        command.add(ARG_MODELS_DIR);
        command.add(modelsDir.toAbsolutePath().toString());
        command.add(ARG_THREADS);
        command.add(Integer.toString(threads));
        command.addAll(extraEngineArgs);
        return List.copyOf(command);
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, got " + duration);
        }
    }

    public static final class Builder {
        private final EngineLaunchTarget launchTarget;
        private final IpcEndpoint endpoint;
        private Path javaExecutable = currentJavaExecutable();
        private List<String> jvmArgs = List.of("-Xmx1536m");
        private String profile = "ENGLISH";
        private Path modelsDir = Path.of("models");
        private int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        private List<String> extraEngineArgs = List.of();
        private Path workingDirectory;
        private boolean lowerPriority = true;
        private int niceLevel = 10;
        private Path niceExecutable;
        private Duration startupTimeout = Duration.ofSeconds(60);
        private Duration handshakeTimeout = Duration.ofSeconds(5);
        private Duration heartbeatInterval = Duration.ofSeconds(2);
        private int maxMissedHeartbeats = 3;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofSeconds(30);
        private int maxRestartsInWindow = 5;
        private Duration restartWindow = Duration.ofMinutes(5);
        private Duration shutdownGrace = Duration.ofSeconds(5);

        private Builder(EngineLaunchTarget launchTarget, IpcEndpoint endpoint) {
            this.launchTarget = launchTarget;
            this.endpoint = endpoint;
        }

        public Builder javaExecutable(Path value) {
            this.javaExecutable = value;
            return this;
        }

        public Builder jvmArgs(List<String> value) {
            this.jvmArgs = value;
            return this;
        }

        public Builder profile(String value) {
            this.profile = value;
            return this;
        }

        public Builder modelsDir(Path value) {
            this.modelsDir = value;
            return this;
        }

        public Builder threads(int value) {
            this.threads = value;
            return this;
        }

        public Builder extraEngineArgs(List<String> value) {
            this.extraEngineArgs = value;
            return this;
        }

        public Builder workingDirectory(Path value) {
            this.workingDirectory = value;
            return this;
        }

        public Builder lowerPriority(boolean value) {
            this.lowerPriority = value;
            return this;
        }

        public Builder niceLevel(int value) {
            this.niceLevel = value;
            return this;
        }

        public Builder niceExecutable(Path value) {
            this.niceExecutable = value;
            return this;
        }

        public Builder startupTimeout(Duration value) {
            this.startupTimeout = value;
            return this;
        }

        public Builder handshakeTimeout(Duration value) {
            this.handshakeTimeout = value;
            return this;
        }

        public Builder heartbeatInterval(Duration value) {
            this.heartbeatInterval = value;
            return this;
        }

        public Builder maxMissedHeartbeats(int value) {
            this.maxMissedHeartbeats = value;
            return this;
        }

        public Builder initialBackoff(Duration value) {
            this.initialBackoff = value;
            return this;
        }

        public Builder maxBackoff(Duration value) {
            this.maxBackoff = value;
            return this;
        }

        public Builder maxRestartsInWindow(int value) {
            this.maxRestartsInWindow = value;
            return this;
        }

        public Builder restartWindow(Duration value) {
            this.restartWindow = value;
            return this;
        }

        public Builder shutdownGrace(Duration value) {
            this.shutdownGrace = value;
            return this;
        }

        public EngineProcessConfig build() {
            return new EngineProcessConfig(
                    javaExecutable,
                    launchTarget,
                    jvmArgs,
                    endpoint,
                    profile,
                    modelsDir,
                    threads,
                    extraEngineArgs,
                    workingDirectory,
                    lowerPriority,
                    niceLevel,
                    niceExecutable,
                    startupTimeout,
                    handshakeTimeout,
                    heartbeatInterval,
                    maxMissedHeartbeats,
                    initialBackoff,
                    maxBackoff,
                    maxRestartsInWindow,
                    restartWindow,
                    shutdownGrace);
        }
    }
}
