package dev.voicewarden.engine;

import dev.voicewarden.core.ipc.IpcEndpoint;
import java.nio.file.Path;
import java.util.Objects;

public record EngineOptions(
        IpcEndpoint endpoint,
        EngineProfile profile,
        Path modelsDir,
        int threads,
        int workers,
        int maxQueuedPerLane,
        boolean autoDownload,
        boolean doubleCheck,
        SileroVadSettings sileroVad) {

    public static final String ARG_PROFILE = "--profile";
    public static final String ARG_MODELS_DIR = "--models-dir";
    public static final String ARG_THREADS = "--threads";
    public static final String ARG_WORKERS = "--workers";
    public static final String ARG_MAX_QUEUED = "--max-queued";
    public static final String ARG_NO_AUTO_DOWNLOAD = "--no-auto-download";
    public static final String ARG_NO_DOUBLE_CHECK = "--no-double-check";
    public static final String ARG_NO_SILERO_VAD = "--no-silero-vad";
    public static final String ARG_VAD_THRESHOLD = "--vad-threshold";
    public static final String ARG_VAD_MIN_SPEECH_MS = "--vad-min-speech-ms";
    public static final String ARG_VAD_MIN_SILENCE_MS = "--vad-min-silence-ms";

    public static final String USAGE = "Usage: voicewarden-engine (" + IpcEndpoint.ARG_SOCKET + " <path> | "
            + IpcEndpoint.ARG_PORT + " <port>) [" + ARG_PROFILE + " ENGLISH|ENGLISH_ACCURATE|MULTILANG|MULTILANG_GLOBAL] ["
            + ARG_MODELS_DIR + " <dir>] [" + ARG_THREADS + " <n>] [" + ARG_WORKERS + " <n>] [" + ARG_MAX_QUEUED
            + " <n>] [" + ARG_NO_AUTO_DOWNLOAD + "] [" + ARG_NO_DOUBLE_CHECK + "] [" + ARG_NO_SILERO_VAD + "] ["
            + ARG_VAD_THRESHOLD + " <0-1>] [" + ARG_VAD_MIN_SPEECH_MS + " <ms>] [" + ARG_VAD_MIN_SILENCE_MS + " <ms>]\n"
            + "The supervisor passes the IPC auth token in the VOICEWARDEN_IPC_TOKEN environment variable.";

    public EngineOptions {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(modelsDir, "modelsDir");
        Objects.requireNonNull(sileroVad, "sileroVad");
    }

    public static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    public static EngineOptions parse(String[] args) {
        Objects.requireNonNull(args, "args");
        String socket = null;
        String port = null;
        EngineProfile profile = EngineProfile.DEFAULT;
        Path modelsDir = Path.of("models");
        int threads = defaultThreads();
        int workers = 2;
        int maxQueued = FairTranscriptionScheduler.DEFAULT_MAX_QUEUED_PER_PLAYER_LANE;
        boolean autoDownload = true;
        boolean noDoubleCheck = false;
        boolean sileroVad = true;
        float vadThreshold = SileroVadSettings.DEFAULT_THRESHOLD;
        int vadMinSpeechMs = SileroVadSettings.DEFAULT_MIN_SPEECH_MS;
        int vadMinSilenceMs = SileroVadSettings.DEFAULT_MIN_SILENCE_MS;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case IpcEndpoint.ARG_SOCKET -> socket = value(args, ++i, arg);
                case IpcEndpoint.ARG_PORT -> port = value(args, ++i, arg);
                case ARG_PROFILE -> profile = EngineProfile.parse(value(args, ++i, arg));
                case ARG_MODELS_DIR -> modelsDir = Path.of(value(args, ++i, arg));
                case ARG_THREADS -> threads = positive(value(args, ++i, arg), arg);
                case ARG_WORKERS -> workers = positive(value(args, ++i, arg), arg);
                case ARG_MAX_QUEUED -> maxQueued = positive(value(args, ++i, arg), arg);
                case ARG_NO_AUTO_DOWNLOAD -> autoDownload = false;
                case ARG_NO_DOUBLE_CHECK -> noDoubleCheck = true;
                case ARG_NO_SILERO_VAD -> sileroVad = false;
                case ARG_VAD_THRESHOLD -> vadThreshold = fraction(value(args, ++i, arg), arg);
                case ARG_VAD_MIN_SPEECH_MS -> vadMinSpeechMs = positive(value(args, ++i, arg), arg);
                case ARG_VAD_MIN_SILENCE_MS -> vadMinSilenceMs = positive(value(args, ++i, arg), arg);
                default -> throw new IllegalArgumentException("Unknown engine argument '" + arg + "'");
            }
        }
        if (socket != null && port != null) {
            throw new IllegalArgumentException("Pass either " + IpcEndpoint.ARG_SOCKET + " or " + IpcEndpoint.ARG_PORT
                    + ", not both");
        }
        if (socket == null && port == null) {
            throw new IllegalArgumentException("Missing " + IpcEndpoint.ARG_SOCKET + " <path> or "
                    + IpcEndpoint.ARG_PORT + " <port>");
        }
        IpcEndpoint endpoint = socket != null
                ? IpcEndpoint.fromEngineArgs(new String[] {IpcEndpoint.ARG_SOCKET, socket})
                : IpcEndpoint.fromEngineArgs(new String[] {IpcEndpoint.ARG_PORT, port});
        boolean doubleCheck = !noDoubleCheck && profile.defaultDoubleCheck() && profile.verifier().isPresent();
        SileroVadSettings vad = new SileroVadSettings(sileroVad, vadThreshold, vadMinSpeechMs, vadMinSilenceMs);
        return new EngineOptions(endpoint, profile, modelsDir.toAbsolutePath().normalize(), threads, workers, maxQueued,
                autoDownload, doubleCheck, vad);
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[index];
    }

    private static float fraction(String value, String flag) {
        try {
            float parsed = Float.parseFloat(value.trim());
            if (!(parsed > 0f && parsed < 1f)) {
                throw new IllegalArgumentException(flag + " must be between 0 and 1, got " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " must be a number between 0 and 1, got '" + value + "'", e);
        }
    }

    private static int positive(String value, String flag) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) {
                throw new IllegalArgumentException(flag + " must be at least 1, got " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " must be a whole number, got '" + value + "'", e);
        }
    }
}
