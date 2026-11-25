package dev.voicewarden.core.config;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.core.audio.AudioConfig;
import dev.voicewarden.core.audio.MicSpamConfig;
import dev.voicewarden.core.moderation.CustomCommandSettings;
import dev.voicewarden.core.moderation.MuteLadderSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

public final class ConfigLoader {

    private static final Map<?, ?> DEFAULT_CONFIG_TREE = parseBundled(ConfigFiles.defaultConfigTemplate());
    private static final Map<?, ?> DEFAULT_MESSAGES_TREE = parseBundled(ConfigFiles.defaultMessagesTemplate());
    private static final VoiceWardenConfig DEFAULT_CONFIG = loadConfig(ConfigFiles.defaultConfigTemplate()).value();

    private ConfigLoader() {
    }

    public static VoiceWardenConfig defaultConfig() {
        return DEFAULT_CONFIG;
    }

    public static ConfigLoadResult<VoiceWardenConfig> loadConfig(Path file) {
        return loadConfig(readIfExists(file));
    }

    public static ConfigLoadResult<MessagesConfig> loadMessages(Path file) {
        return loadMessages(readIfExists(file));
    }

    public static ConfigLoadResult<VoiceWardenConfig> loadConfig(String yaml) {
        List<String> warnings = new ArrayList<>();
        Map<?, ?> tree = parse(yaml, ConfigFiles.CONFIG_FILE, warnings);
        ConfigReader reader = new ConfigReader(tree, DEFAULT_CONFIG_TREE, ConfigFiles.CONFIG_FILE, warnings);
        VoiceWardenConfig config = new VoiceWardenConfig(
                reader.integer("config-version", 0, Integer.MAX_VALUE),
                reader.string("server_id").strip(),
                reader.enumValue("voice_backend", VoiceWardenConfig.VoiceBackend.class),
                reader.bool("monitor_groups"),
                engine(reader),
                audio(reader),
                wordlist(reader),
                moderation(reader),
                muteLadder(reader),
                mutes(reader),
                consent(reader),
                new TranscriptConfig(
                        reader.integer("transcripts.retention_seconds", 60, 7 * 86_400),
                        reader.integer("transcripts.max_lines_per_player", 1, 100_000),
                        reader.integer("transcripts.context_lines", 0, 20),
                        reader.integer("transcripts.context_window_seconds", 10, 600),
                        reader.integer("transcripts.context_radius_blocks", 1, 1024)),
                voiceReport(reader),
                discord(reader, "discord"),
                discord(reader, "discord_mute"),
                commands(reader),
                storage(reader),
                reader.bool("placeholder_api.enabled"),
                reader.bool("update_check.enabled"));
        return new ConfigLoadResult<>(config, warnings);
    }

    public static ConfigLoadResult<MessagesConfig> loadMessages(String yaml) {
        List<String> warnings = new ArrayList<>();
        Map<?, ?> tree = parse(yaml, ConfigFiles.MESSAGES_FILE, warnings);
        ConfigReader reader = new ConfigReader(tree, DEFAULT_MESSAGES_TREE, ConfigFiles.MESSAGES_FILE, warnings);
        Map<MessageKey, String> messages = new EnumMap<>(MessageKey.class);
        for (MessageKey key : MessageKey.values()) {
            messages.put(key, reader.read(key.path(), "text or a list of lines",
                    raw -> ConfigReader.asStringList(raw).map(lines -> String.join("\n", lines))));
        }
        if (tree.containsKey("prefix") && !hasNestedKey(tree, "prefixes", "prefix")) {
            ConfigReader.asString(tree.get("prefix")).ifPresent(legacy -> messages.put(MessageKey.PREFIX, legacy));
        }
        Map<String, String> theme = new LinkedHashMap<>(MessagesConfig.DEFAULT_THEME);
        reader.map("theme").forEach((name, value) -> ConfigReader.asString(value)
                .map(String::strip)
                .filter(color -> !color.isEmpty())
                .ifPresent(color -> theme.put(name.strip().toLowerCase(Locale.ROOT), color)));
        Map<String, String> prefixes = new LinkedHashMap<>();
        reader.map("prefixes").forEach((name, value) -> ConfigReader.asString(value)
                .ifPresent(snippet -> prefixes.put(name.strip().toLowerCase(Locale.ROOT), snippet)));
        prefixes.put("prefix", messages.get(MessageKey.PREFIX));
        prefixes.put("hold-up", messages.get(MessageKey.HOLD_UP));
        prefixes.put("oops", messages.get(MessageKey.OOPS));
        return new ConfigLoadResult<>(new MessagesConfig(messages, theme, prefixes), warnings);
    }

    private static boolean hasNestedKey(Map<?, ?> root, String section, String key) {
        Object nested = root.get(section);
        return nested instanceof Map<?, ?> map && map.containsKey(key);
    }

    private static EngineConfig engine(ConfigReader reader) {
        return new EngineConfig(
                reader.enumValue("engine.profile", EngineConfig.Profile.class),
                reader.integer("engine.threads", 1, 64),
                reader.integer("engine.max_memory_mb", 128, 65_536),
                reader.string("engine.models_dir"),
                reader.bool("engine.auto_download"),
                reader.integer("engine.nice_priority", 0, 19),
                reader.bool("engine.double_check.enabled"),
                reader.bool("engine.double_check.require_confirmation"),
                new SileroVadConfig(
                        reader.bool("engine.silero_vad.enabled"),
                        reader.decimal("engine.silero_vad.threshold", SileroVadConfig.MIN_THRESHOLD,
                                SileroVadConfig.MAX_THRESHOLD),
                        reader.integer("engine.silero_vad.min_speech_duration_ms", SileroVadConfig.MIN_DURATION_MS,
                                SileroVadConfig.MAX_SPEECH_DURATION_MS),
                        reader.integer("engine.silero_vad.min_silence_duration_ms", SileroVadConfig.MIN_DURATION_MS,
                                SileroVadConfig.MAX_SILENCE_DURATION_MS)));
    }

    private static AudioConfig audio(ConfigReader reader) {
        return AudioConfig.builder()
                .silenceThresholdMs(reader.integer("audio.silence_threshold_ms",
                        AudioConfig.MIN_SILENCE_THRESHOLD_MS, AudioConfig.MAX_SILENCE_THRESHOLD_MS))
                .minAudioDurationMs(reader.integer("audio.min_audio_duration_ms", 0, AudioConfig.MAX_MIN_AUDIO_DURATION_MS))
                .maxUtteranceSeconds(reader.integer("audio.max_utterance_seconds",
                        AudioConfig.MIN_UTTERANCE_SECONDS, AudioConfig.MAX_UTTERANCE_SECONDS))
                .liveDetection(reader.bool("audio.live_detection"))
                .liveChunkMs(reader.integer("audio.live_chunk_ms", AudioConfig.MIN_LIVE_CHUNK_MS, AudioConfig.MAX_LIVE_CHUNK_MS))
                .preRollMs(reader.integer("audio.pre_roll_ms", 0, AudioConfig.MAX_PRE_ROLL_MS))
                .vadRmsThreshold(reader.decimal("audio.vad_rms_threshold",
                        AudioConfig.MIN_VAD_RMS_THRESHOLD, AudioConfig.MAX_VAD_RMS_THRESHOLD))
                .workerThreads(reader.integer("audio.worker_threads", AudioConfig.MIN_WORKER_THREADS, AudioConfig.MAX_WORKER_THREADS))
                .micSpam(micSpam(reader))
                .build();
    }

    private static MicSpamConfig micSpam(ConfigReader reader) {
        List<VoiceAction> escalation = micSpamEscalation(reader);
        return new MicSpamConfig(
                reader.bool("audio.mic_spam.enabled"),
                reader.integer("audio.mic_spam.clipping_sample_threshold",
                        MicSpamConfig.MIN_CLIPPING_SAMPLE_THRESHOLD, MicSpamConfig.MAX_CLIPPING_SAMPLE_THRESHOLD),
                reader.decimal("audio.mic_spam.clipping_ratio", MicSpamConfig.MIN_CLIPPING_RATIO, MicSpamConfig.MAX_CLIPPING_RATIO),
                reader.decimal("audio.mic_spam.rms_threshold", MicSpamConfig.MIN_RMS_THRESHOLD, MicSpamConfig.MAX_RMS_THRESHOLD),
                reader.integer("audio.mic_spam.min_duration_ms", MicSpamConfig.MIN_DURATION_MS, MicSpamConfig.MAX_DURATION_MS),
                reader.integer("audio.mic_spam.cooldown_seconds", 0, MicSpamConfig.MAX_COOLDOWN_SECONDS),
                escalation,
                reader.integer("audio.mic_spam.window_minutes", MicSpamConfig.MIN_WINDOW_MINUTES, MicSpamConfig.MAX_WINDOW_MINUTES),
                reader.duration("audio.mic_spam.mute_duration"),
                reader.bool("audio.mic_spam.notify_discord"));
    }

    private static List<VoiceAction> micSpamEscalation(ConfigReader reader) {
        if (!reader.hasUserValue("audio.mic_spam.escalation") && reader.hasUserValue("audio.mic_spam.action")) {
            return reader.readIfPresent("audio.mic_spam.action", "report, warn or mute", ConfigLoader::nonEmptyActions)
                    .orElse(MicSpamConfig.DEFAULT_ESCALATION);
        }
        return reader.read("audio.mic_spam.escalation", "a non-empty list of report, warn or mute", ConfigLoader::nonEmptyActions);
    }

    private static Optional<List<VoiceAction>> nonEmptyActions(Object raw) {
        return actions(raw).filter(list -> !list.isEmpty());
    }

    private static WordlistConfig wordlist(ConfigReader reader) {
        return new WordlistConfig(
                reader.stringList("wordlist.languages").stream().map(language -> language.strip().toLowerCase(Locale.ROOT)).toList(),
                reader.bool("wordlist.partial_match"),
                reader.bool("wordlist.case_sensitive"),
                reader.stringList("wordlist.profanity_words"),
                reader.stringList("wordlist.mute-list"),
                reader.stringList("wordlist.allow_words"));
    }

    private static ModerationConfig moderation(ConfigReader reader) {
        Map<FlagSeverity, List<VoiceAction>> escalation = new EnumMap<>(FlagSeverity.class);
        for (FlagSeverity severity : FlagSeverity.values()) {
            String path = "moderation.escalation." + severity.name().toLowerCase(Locale.ROOT);
            escalation.put(severity, reader.read(path, "a list of report, warn or mute", ConfigLoader::actions));
        }
        return new ModerationConfig(
                reader.integer("moderation.flag_threshold", 1, 1_000),
                reader.integer("moderation.window_minutes", 1, 10_080),
                reader.bool("moderation.network_strikes"),
                escalation,
                reader.string("moderation.appeal_url").strip(),
                reader.bool("moderation.auto_warn_builtin"),
                reader.bool("moderation.auto_mute_builtin"));
    }

    private static Optional<List<VoiceAction>> actions(Object raw) {
        Optional<List<String>> names = ConfigReader.asStringList(raw);
        if (names.isEmpty()) {
            return Optional.empty();
        }
        List<VoiceAction> actions = new ArrayList<>();
        for (String name : names.get()) {
            Optional<VoiceAction> action = ConfigReader.enumConstant(VoiceAction.class, name);
            if (action.isEmpty()) {
                return Optional.empty();
            }
            actions.add(action.get());
        }
        return Optional.of(List.copyOf(actions));
    }

    private static MuteLadderSettings muteLadder(ConfigReader reader) {
        TreeMap<Integer, Duration> tiers = new TreeMap<>();
        reader.map("mute_ladder.tiers").forEach((key, value) -> {
            Optional<Long> tier = ConfigReader.asLong(key);
            Optional<Duration> duration = ConfigReader.asString(value).flatMap(ConfigReader::parseDuration);
            if (tier.isPresent() && tier.get() >= 1 && tier.get() <= 1_000 && duration.isPresent()) {
                tiers.put(tier.get().intValue(), duration.get());
            } else {
                reader.warn("mute_ladder.tiers." + key, "expected a tier number from 1 and a duration such as 15m but found '"
                        + value + "'; skipping this tier");
            }
        });
        return new MuteLadderSettings(
                reader.bool("mute_ladder.enabled"),
                tiers,
                reader.duration("mute_ladder.max_tier_duration"),
                Duration.ofDays(reader.integer("mute_ladder.reset_after_days", 1, 3_650)),
                Duration.ofMinutes(reader.integer("mute_ladder.mute-duration-minutes", 1, 525_600)));
    }

    private static MutesConfig mutes(ConfigReader reader) {
        return new MutesConfig(
                reader.enumValue("mutes.enforcement", MutesConfig.Enforcement.class),
                reader.bool("mutes.mirror_luckperms"),
                new MutesConfig.Block(
                        reader.bool("mutes.block.proximity"),
                        reader.bool("mutes.block.whisper"),
                        reader.enumValue("mutes.block.groups", MutesConfig.GroupBlock.class),
                        reader.stringList("mutes.block.allowed_groups"),
                        reader.bool("mutes.block.listen")),
                reader.enumValue("mutes.indicator", MutesConfig.Indicator.class),
                reader.bool("mutes.notify_on_attempt"),
                reader.integer("mutes.attempt_cooldown_seconds", 0, 3_600));
    }

    private static ConsentConfig consent(ConfigReader reader) {
        return new ConsentConfig(
                reader.bool("consent.enabled"),
                reader.enumValue("consent.mode", ConsentConfig.Mode.class),
                reader.enumValue("consent.on_deny", ConsentConfig.OnDeny.class),
                reader.integer("consent.rules_version", 1, Integer.MAX_VALUE),
                reader.integer("consent.gui_delay_ticks", 0, 1_200));
    }

    private static VoiceReportConfig voiceReport(ConfigReader reader) {
        return new VoiceReportConfig(
                reader.bool("voice_report.enabled"),
                reader.enumValue("voice_report.mode", VoiceReportConfig.Mode.class),
                reader.integer("voice_report.default_minutes", 1, 720),
                reader.integer("voice_report.min_minutes", 1, 720),
                reader.integer("voice_report.max_minutes", 1, 720),
                reader.intList("voice_report.preset_minutes"),
                reader.integer("voice_report.cooldown_seconds", 0, 86_400),
                reader.integer("voice_report.max_concurrent_reports", 1, 100),
                reader.bool("voice_report.staff_chat_full_transcript"),
                reader.string("voice_report.webhook_url").strip(),
                reader.bool("voice_report.include_audio"),
                reader.integer("voice_report.audio_buffer_seconds", 0, VoiceReportConfig.MAX_AUDIO_BUFFER_SECONDS));
    }

    private static DiscordWebhookConfig discord(ConfigReader reader, String section) {
        return new DiscordWebhookConfig(
                reader.bool(section + ".enabled"),
                reader.string(section + ".webhook_url").strip(),
                reader.string(section + ".username"),
                reader.string(section + ".avatar_url").strip(),
                reader.color(section + ".color"),
                reader.string(section + ".title"),
                reader.string(section + ".footer"),
                reader.stringList(section + ".role_pings"),
                reader.enumValue(section + ".include_transcript", DiscordWebhookConfig.TranscriptInclusion.class),
                reader.bool(section + ".include_server_info"),
                reader.bool(section + ".include_audio"),
                reader.integer(section + ".timeout_seconds", 1, 120),
                reader.integer(section + ".max_retries", 0, 10));
    }

    private static CustomCommandSettings commands(ConfigReader reader) {
        Map<String, List<String>> keywordCommands = new LinkedHashMap<>();
        reader.map("commands.keyword_commands").forEach((keyword, value) -> {
            Optional<List<String>> commands = ConfigReader.asStringList(value);
            if (commands.isPresent()) {
                keywordCommands.put(keyword, commands.get());
            } else {
                reader.warn("commands.keyword_commands." + keyword, "expected a list of commands; skipping this keyword");
            }
        });
        return new CustomCommandSettings(
                keywordCommands,
                reader.stringList("commands.mute_commands"),
                reader.stringList("commands.profanity_commands"),
                reader.bool("commands.auto_mute_builtin.on_keyword_command"),
                reader.bool("commands.auto_mute_builtin.on_mute_command"),
                reader.bool("commands.auto_mute_builtin.on_profanity_command"));
    }

    private static StorageConfig storage(ConfigReader reader) {
        return new StorageConfig(
                reader.enumValue("storage.type", StorageConfig.Type.class),
                reader.string("storage.host").strip(),
                reader.integer("storage.port", 1, 65_535),
                reader.string("storage.database").strip(),
                reader.string("storage.username"),
                reader.string("storage.password"),
                reader.string("storage.jdbc_params").strip(),
                reader.integer("storage.max_pool_size", 1, 100),
                reader.integer("storage.query_timeout_ms", 100, 60_000));
    }

    private static String readIfExists(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    private static Map<?, ?> parse(String yaml, String fileName, List<String> warnings) {
        try {
            Object root = newYaml().load(yaml);
            if (root == null) {
                return Map.of();
            }
            if (root instanceof Map<?, ?> map) {
                return map;
            }
            warnings.add(fileName + ": the file is not a map of settings; using all defaults");
        } catch (YAMLException e) {
            warnings.add(fileName + ": could not be read as YAML (" + e.getMessage().lines().findFirst().orElse("parse error")
                    + "); using all defaults");
        }
        return Map.of();
    }

    private static Map<?, ?> parseBundled(String yaml) {
        Object root = newYaml().load(yaml);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Bundled defaults are not a YAML map");
        }
        return map;
    }

    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }
}
