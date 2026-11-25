package dev.voicewarden.plugin.pipeline;

import dev.voicewarden.core.config.EngineConfig;
import dev.voicewarden.core.config.SileroVadConfig;
import dev.voicewarden.core.config.VoiceReportConfig;
import dev.voicewarden.core.config.VoiceWardenConfig;
import dev.voicewarden.core.discord.DiscordWebhookConfig;
import dev.voicewarden.core.discord.IncludeTranscriptMode;
import dev.voicewarden.core.discord.WebhookTarget;
import dev.voicewarden.core.ipc.EngineLaunchTarget;
import dev.voicewarden.core.ipc.EngineProcessConfig;
import dev.voicewarden.core.ipc.IpcEndpoint;
import dev.voicewarden.core.moderation.ModerationEngine;
import dev.voicewarden.core.moderation.StrikePersistence;
import dev.voicewarden.core.storage.StorageConfig;
import dev.voicewarden.core.storage.StorageType;
import dev.voicewarden.core.wordlist.WordlistConfig;
import dev.voicewarden.plugin.voice.VoicePacketPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class ConfigBridges {

    public static final String DATA_DIRECTORY = "data";
    public static final String ENGINE_DIRECTORY = "engine";
    public static final String ENGINE_JAR = "voicewarden-engine.jar";
    public static final String SOCKET_FILE = "engine.sock";
    public static final String ARG_NO_AUTO_DOWNLOAD = "--no-auto-download";
    public static final String ARG_NO_DOUBLE_CHECK = "--no-double-check";
    public static final String ARG_NO_SILERO_VAD = "--no-silero-vad";
    public static final String ARG_VAD_THRESHOLD = "--vad-threshold";
    public static final String ARG_VAD_MIN_SPEECH_MS = "--vad-min-speech-ms";
    public static final String ARG_VAD_MIN_SILENCE_MS = "--vad-min-silence-ms";
    public static final String REPORT_WEBHOOK_TITLE = "Voice report";
    public static final int SOCKET_HASH_CHARS = 12;

    private ConfigBridges() {
    }

    public static StorageConfig storage(VoiceWardenConfig config, Path dataFolder) {
        dev.voicewarden.core.config.StorageConfig source = config.storage();
        return StorageConfig.builder(StorageType.valueOf(source.type().name()), dataFolder.resolve(DATA_DIRECTORY))
                .host(source.host())
                .port(source.port())
                .database(source.database())
                .username(source.username())
                .password(source.password())
                .jdbcParams(source.jdbcParams())
                .maxPoolSize(source.maxPoolSize())
                .queryTimeoutMs(source.queryTimeoutMs())
                .serverId(config.serverId())
                .networkStrikes(config.moderation().networkStrikes())
                .build();
    }

    public static DiscordWebhookConfig discord(VoiceWardenConfig config, String serverName) {
        dev.voicewarden.core.config.DiscordWebhookConfig flag = config.discord();
        return new DiscordWebhookConfig(
                target(flag),
                target(config.discordMute()),
                reportTarget(config.voiceReport(), flag),
                flag.timeoutSeconds(),
                flag.maxRetries(),
                DiscordWebhookConfig.DEFAULT_RETRY_BASE_DELAY_MS,
                serverName,
                config.serverId());
    }

    public static WebhookTarget target(dev.voicewarden.core.config.DiscordWebhookConfig source) {
        return new WebhookTarget(
                source.enabled(),
                source.webhookUrl(),
                source.username(),
                source.avatarUrl(),
                source.color(),
                source.title(),
                source.footer(),
                source.rolePings(),
                IncludeTranscriptMode.valueOf(source.includeTranscript().name()),
                source.includeServerInfo(),
                source.includeAudio());
    }

    public static WebhookTarget reportTarget(VoiceReportConfig report, dev.voicewarden.core.config.DiscordWebhookConfig style) {
        String url = report.webhookUrl().trim();
        return new WebhookTarget(
                report.enabled() && !url.isEmpty(),
                url,
                style.username(),
                style.avatarUrl(),
                style.color(),
                REPORT_WEBHOOK_TITLE,
                style.footer(),
                style.rolePings(),
                IncludeTranscriptMode.BOTH,
                style.includeServerInfo(),
                report.includeAudio());
    }

    public static WordlistConfig wordlist(VoiceWardenConfig config) {
        dev.voicewarden.core.config.WordlistConfig source = config.wordlist();
        return WordlistConfig.builder()
                .enabledLanguages(source.languages())
                .partialMatch(source.partialMatch())
                .caseSensitive(source.caseSensitive())
                .extraProfanityWords(source.profanityWords())
                .extraMuteWords(source.muteList())
                .extraAllowWords(source.allowWords())
                .build();
    }

    public static VoicePacketPolicy.Settings packetSettings(VoiceWardenConfig config) {
        return new VoicePacketPolicy.Settings(config.mutes().block(), config.monitorGroups());
    }

    public static ModerationEngine moderation(VoiceWardenConfig config, StrikePersistence persistence) {
        return ModerationEngine.create(config.moderationSettings(), config.muteLadder(), config.commands(), persistence);
    }

    public static boolean supportsDoubleCheck(EngineConfig.Profile profile) {
        return profile == EngineConfig.Profile.ENGLISH || profile == EngineConfig.Profile.ENGLISH_ACCURATE;
    }

    public static boolean engineRestartNeeded(EngineConfig previous, EngineConfig next) {
        return previous.profile() != next.profile()
                || previous.threads() != next.threads()
                || previous.maxMemoryMb() != next.maxMemoryMb()
                || !previous.modelsDir().equals(next.modelsDir())
                || previous.autoDownload() != next.autoDownload()
                || previous.nicePriority() != next.nicePriority()
                || previous.doubleCheckEnabled() != next.doubleCheckEnabled()
                || !previous.sileroVad().equals(next.sileroVad());
    }

    public static List<String> engineArgs(EngineConfig engine) {
        List<String> args = new ArrayList<>();
        if (!engine.autoDownload()) {
            args.add(ARG_NO_AUTO_DOWNLOAD);
        }
        if (!engine.doubleCheckEnabled()) {
            args.add(ARG_NO_DOUBLE_CHECK);
        }
        SileroVadConfig vad = engine.sileroVad();
        if (!vad.enabled()) {
            args.add(ARG_NO_SILERO_VAD);
            return List.copyOf(args);
        }
        args.add(ARG_VAD_THRESHOLD);
        args.add(BigDecimal.valueOf(vad.threshold()).stripTrailingZeros().toPlainString());
        args.add(ARG_VAD_MIN_SPEECH_MS);
        args.add(Integer.toString(vad.minSpeechDurationMs()));
        args.add(ARG_VAD_MIN_SILENCE_MS);
        args.add(Integer.toString(vad.minSilenceDurationMs()));
        return List.copyOf(args);
    }

    public static Path modelsDirectory(EngineConfig engine, Path dataFolder) {
        Path configured = Path.of(engine.modelsDir());
        return configured.isAbsolute() ? configured : dataFolder.resolve(configured);
    }

    public static EngineProcessConfig engineProcess(VoiceWardenConfig config, Path dataFolder, Path engineJar, IpcEndpoint endpoint) {
        EngineConfig engine = config.engine();
        EngineProcessConfig.Builder builder = EngineProcessConfig.builder(new EngineLaunchTarget.Jar(engineJar), endpoint)
                .profile(engine.profile().name())
                .modelsDir(modelsDirectory(engine, dataFolder))
                .threads(Math.max(1, engine.threads()))
                .extraEngineArgs(engineArgs(engine))
                .workingDirectory(dataFolder.resolve(ENGINE_DIRECTORY))
                .lowerPriority(engine.nicePriority() > 0)
                .niceLevel(Math.max(0, Math.min(19, engine.nicePriority())));
        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("--enable-native-access=ALL-UNNAMED");
        if (engine.maxMemoryMb() > 0) {
            jvmArgs.add("-Xmx" + engine.maxMemoryMb() + "m");
        }
        builder.jvmArgs(List.copyOf(jvmArgs));
        return builder.build();
    }

    public static IpcEndpoint engineEndpoint(Path dataFolder) {
        return engineEndpoint(dataFolder, IpcEndpoint.isWindows(), Path.of(System.getProperty("java.io.tmpdir")));
    }

    public static IpcEndpoint engineEndpoint(Path dataFolder, boolean windows, Path tempDirectory) {
        if (windows) {
            return IpcEndpoint.Tcp.ephemeral();
        }
        Path preferred = dataFolder.resolve(ENGINE_DIRECTORY).resolve(SOCKET_FILE).toAbsolutePath().normalize();
        if (utf8Length(preferred) <= IpcEndpoint.Unix.MAX_PATH_BYTES) {
            return new IpcEndpoint.Unix(preferred);
        }
        return new IpcEndpoint.Unix(tempDirectory.resolve("vw-" + shortStableHash(dataFolder) + ".sock"));
    }

    public static String shortStableHash(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, SOCKET_HASH_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    private static int utf8Length(Path path) {
        return path.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
