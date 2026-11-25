package dev.voicewarden.engine;

import dev.voicewarden.core.ipc.IpcAuthToken;
import dev.voicewarden.core.ipc.IpcServerChannel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VoiceWardenEngineMain {

    public static final String ENGINE_VERSION = "1.0.0";
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_IO = 74;
    public static final int EXIT_CONFIG = 78;

    private static final Logger LOG = LoggerFactory.getLogger(VoiceWardenEngineMain.class);

    private VoiceWardenEngineMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv()));
    }

    static int run(String[] args, Map<String, String> environment) {
        EngineOptions options;
        try {
            options = EngineOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("voicewarden-engine: " + e.getMessage());
            System.err.println(EngineOptions.USAGE);
            return EXIT_USAGE;
        }
        IpcAuthToken token;
        try {
            token = IpcAuthToken.fromEnvironment(environment);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("voicewarden-engine: " + e.getMessage());
            return EXIT_CONFIG;
        }
        IpcServerChannel channel;
        try {
            channel = IpcServerChannel.bind(options.endpoint(), token, ENGINE_VERSION, options.profile().name());
        } catch (IOException e) {
            System.err.println("voicewarden-engine: could not listen on " + options.endpoint().address() + ": "
                    + e.getMessage());
            return EXIT_IO;
        }
        channel.onRejection(reason -> LOG.warn("Rejected an IPC client: {}", reason));
        EngineIpcServer server = new EngineIpcServer(channel, options.profile().name(), options.workers(),
                options.maxQueuedPerLane(), EngineIpcServer.DEFAULT_IDLE_STREAM_TIMEOUT);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "voicewarden-engine-shutdown"));
        LOG.info("VoiceWarden engine {} listening on {} (profile {}, models {})", ENGINE_VERSION,
                channel.endpoint().address(), options.profile(), options.modelsDir());
        Thread.ofPlatform().name("voicewarden-engine-loader").daemon().start(() -> load(options, server));
        int code = server.run();
        server.close();
        LOG.info("VoiceWarden engine exiting with code {}", code);
        return code;
    }

    static void load(EngineOptions options, EngineIpcServer server) {
        SpeechRecognizerEngine engine = null;
        SpeechGate gate = SpeechGate.OPEN;
        try {
            VerifiedDownloader http = VerifiedDownloader.createDefault();
            new NativeLibraryLoader(options.modelsDir(), options.autoDownload(), http, NativePlatform.current())
                    .ensureLoaded();
            ModelDownloader models = new ModelDownloader(options.modelsDir(), options.autoDownload(), http);
            EngineProfile profile = options.profile();
            Path primaryDir = models.ensure(profile.primary());
            Path verifierDir = null;
            if (options.doubleCheck()) {
                verifierDir = models.ensure(profile.verifier().orElseThrow());
            }
            gate = speechGate(options.sileroVad(), models, dir -> SileroVadGate.open(dir, options.sileroVad()));
            engine = SherpaOnnxSpeechEngine.create(profile, primaryDir, verifierDir, options.threads(),
                    new DoubleCheckEvaluator(), gate);
            server.attachEngine(engine);
        } catch (ModelUnavailableException | ModelLayoutException e) {
            LOG.error("VoiceWarden engine could not start: {}", e.getMessage());
            gate.close();
            server.failStartup(e);
        } catch (Exception | LinkageError e) {
            LOG.error("VoiceWarden engine could not start: {}", e.getMessage(), e);
            if (engine != null) {
                engine.close();
            } else {
                gate.close();
            }
            server.failStartup(e);
        }
    }

    static SpeechGate speechGate(SileroVadSettings settings, ModelDownloader models, Function<Path, SpeechGate> opener) {
        return speechGate(settings, SileroVadGate.ARTIFACT, models, opener);
    }

    static SpeechGate speechGate(
            SileroVadSettings settings, ModelArtifact artifact, ModelDownloader models, Function<Path, SpeechGate> opener) {
        if (!settings.enabled()) {
            LOG.info("Silero VAD is turned off; every utterance goes to speech recognition");
            return SpeechGate.OPEN;
        }
        Path dir;
        try {
            dir = models.ensure(artifact);
        } catch (ModelUnavailableException e) {
            LOG.warn("Silero VAD is unavailable, so every utterance goes to speech recognition. {}", e.getMessage());
            return SpeechGate.OPEN;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Silero VAD could not be installed, so every utterance goes to speech recognition: {}",
                    e.getMessage());
            return SpeechGate.OPEN;
        }
        try {
            SpeechGate gate = opener.apply(dir);
            LOG.info("Speech gate ready: {}", gate.describe());
            return gate;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Silero VAD in {} could not be loaded, so every utterance goes to speech recognition: {}", dir,
                    e.toString());
            return SpeechGate.OPEN;
        }
    }
}
