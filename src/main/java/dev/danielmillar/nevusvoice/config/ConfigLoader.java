package dev.danielmillar.nevusvoice.config;

import dev.danielmillar.nevusvoice.evidence.EvidenceSettings;
import dev.danielmillar.nevusvoice.notify.discord.DiscordSettings;
import dev.danielmillar.nevusvoice.notify.discord.WebhookSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a {@link PluginConfig} from config.yml. Missing keys fall back to the bundled defaults; invalid values fall
 * back too and are reported as warnings instead of failing the whole load. Safe to call from any thread.
 */
public final class ConfigLoader {

    private final List<String> warnings = new ArrayList<>();
    private final Path dataFolder;

    private ConfigLoader(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    public record Result(PluginConfig config, List<String> warnings) {
    }

    public static Result load(Path dataFolder, Path configFile) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IOException("config.yml is not valid YAML: " + e.getMessage(), e);
        }
        yaml.setDefaults(bundledDefaults("config.yml"));
        ConfigLoader loader = new ConfigLoader(dataFolder);
        PluginConfig config = loader.parse(yaml);
        return new Result(config, List.copyOf(loader.warnings));
    }

    static YamlConfiguration bundledDefaults(String resource) throws IOException {
        try (InputStream in = Objects.requireNonNull(ConfigLoader.class.getClassLoader().getResourceAsStream(resource),
                resource + " missing from jar");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private PluginConfig parse(YamlConfiguration c) {
        return new PluginConfig(
                c.getBoolean("enabled"),
                c.getString("server-name", ""),
                speechToText(c),
                audio(c),
                moderation(c),
                mute(c),
                new PluginConfig.Alerts(c.getBoolean("alerts.staff"), c.getBoolean("alerts.console")),
                new PluginConfig.CustomCommands(
                        c.getStringList("custom-commands.mute"),
                        c.getStringList("custom-commands.profanity"),
                        c.getStringList("custom-commands.warn")),
                transcriptBuffer(c),
                voiceReport(c),
                recordings(c),
                discord(c),
                c.getBoolean("privacy.join-message"),
                new PluginConfig.Logging(c.getBoolean("logging.transcripts"), c.getBoolean("logging.debug")),
                duration(c, "health-check-interval", Duration.ofSeconds(5), Duration.ofHours(1))
        );
    }

    private PluginConfig.SpeechToText speechToText(YamlConfiguration c) {
        String p = "speech-to-text.";
        int cores = Runtime.getRuntime().availableProcessors();
        return new PluginConfig.SpeechToText(
                c.getString(p + "model", "parakeet-tdt-v2").trim().toLowerCase(Locale.ROOT),
                c.getBoolean(p + "auto-download"),
                c.getString(p + "native-library-path", "").trim(),
                intIn(c, p + "workers", 1, Math.max(1, cores)),
                intIn(c, p + "cpu-threads", 1, Math.max(1, cores)),
                intIn(c, p + "max-batch-size", 1, 32),
                intIn(c, p + "queue.max-size", 1, 10_000),
                intIn(c, p + "queue.max-per-player", 1, 1_000),
                duration(c, p + "queue.max-age", Duration.ofSeconds(1), Duration.ofHours(1)),
                new PluginConfig.InputGain(
                        c.getBoolean(p + "input-gain.enabled"),
                        doubleIn(c, p + "input-gain.target-dbfs", -50, -3),
                        doubleIn(c, p + "input-gain.max-gain-db", 0, 60)),
                new PluginConfig.Vad(
                        c.getBoolean(p + "vad.enabled"),
                        (float) doubleIn(c, p + "vad.threshold", 0.05, 0.95),
                        duration(c, p + "vad.min-speech", Duration.ZERO, Duration.ofSeconds(5)),
                        duration(c, p + "vad.min-silence", Duration.ZERO, Duration.ofSeconds(5)),
                        duration(c, p + "vad.padding", Duration.ZERO, Duration.ofSeconds(2)),
                        c.getBoolean(p + "vad.trim-silence")),
                new PluginConfig.CustomModel(
                        c.getString(p + "custom.model-type", "nemo_transducer"),
                        c.getString(p + "custom.encoder", ""),
                        c.getString(p + "custom.decoder", ""),
                        c.getString(p + "custom.joiner", ""),
                        c.getString(p + "custom.tokens", ""))
        );
    }

    private PluginConfig.Audio audio(YamlConfiguration c) {
        String p = "audio.";
        Duration min = duration(c, p + "min-utterance", Duration.ZERO, Duration.ofSeconds(10));
        Duration max = duration(c, p + "max-utterance", Duration.ofSeconds(2), Duration.ofSeconds(30));
        return new PluginConfig.Audio(
                duration(c, p + "silence-timeout", Duration.ofMillis(100), Duration.ofSeconds(10)),
                min,
                max,
                doubleIn(c, p + "min-level-dbfs", -96, 0),
                c.getBoolean(p + "ignore-whispers"),
                intIn(c, p + "decode-threads", 0, 64),
                duration(c, p + "idle-session-timeout", Duration.ofSeconds(10), Duration.ofDays(1)),
                intIn(c, p + "max-buffered-frames", 25, 50_000));
    }

    private PluginConfig.Moderation moderation(YamlConfiguration c) {
        String p = "moderation.";
        TreeMap<Integer, Duration> tiers = new TreeMap<>();
        ConfigurationSection tierSection = c.getConfigurationSection(p + "mute-ladder.tiers");
        if (tierSection != null) {
            for (String key : tierSection.getKeys(false)) {
                try {
                    int offense = Integer.parseInt(key.trim());
                    Duration d = Durations.parse(String.valueOf(tierSection.get(key)));
                    if (offense < 1 || d == null) {
                        throw new IllegalArgumentException();
                    }
                    tiers.put(offense, d);
                } catch (RuntimeException e) {
                    warnings.add("moderation.mute-ladder.tiers." + key + " is invalid (expected e.g. '3: 30m'); ignored");
                }
            }
        }
        if (tiers.isEmpty()) {
            tiers.put(1, Duration.ofMinutes(5));
        }
        Set<String> languages = new LinkedHashSet<>();
        for (String lang : c.getStringList(p + "languages")) {
            if (!lang.isBlank()) {
                languages.add(lang.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new PluginConfig.Moderation(
                c.getBoolean(p + "dry-run"),
                intIn(c, p + "flag-threshold", 1, 1_000),
                duration(c, p + "flag-window", Duration.ofSeconds(10), Duration.ofDays(30)),
                c.getBoolean(p + "warn-below-threshold"),
                duration(c, p + "mute-duration", Duration.ofSeconds(1), Duration.ofDays(3650)),
                new PluginConfig.MuteLadder(
                        c.getBoolean(p + "mute-ladder.enabled"),
                        Collections.unmodifiableNavigableMap(tiers),
                        duration(c, p + "mute-ladder.max-tier-duration", Duration.ofSeconds(1), Duration.ofDays(3650)),
                        intIn(c, p + "mute-ladder.reset-after-days", 0, 3650)),
                intIn(c, p + "context-lines", 0, 50),
                Set.copyOf(languages),
                c.getStringList(p + "profanity-words"),
                c.getStringList(p + "mute-words"));
    }

    private PluginConfig.Mute mute(YamlConfiguration c) {
        return new PluginConfig.Mute(
                c.getBoolean("mute.disable-listening"),
                c.getBoolean("mute.luckperms.enabled"),
                c.getString("mute.luckperms.speak-permission", "voicechat.speak").trim(),
                c.getString("mute.luckperms.listen-permission", "voicechat.listen").trim(),
                c.getString("mute.luckperms.server-context", "").trim(),
                c.getBoolean("mute.notify-player"));
    }

    private PluginConfig.TranscriptBuffer transcriptBuffer(YamlConfiguration c) {
        return new PluginConfig.TranscriptBuffer(
                c.getBoolean("transcript-buffer.enabled"),
                duration(c, "transcript-buffer.retention", Duration.ofMinutes(1), Duration.ofDays(7)),
                intIn(c, "transcript-buffer.max-lines-per-player", 1, 100_000),
                (long) intIn(c, "transcript-buffer.audio-memory-mb", 0, 16_384) * 1024 * 1024);
    }

    private PluginConfig.VoiceReport voiceReport(YamlConfiguration c) {
        String p = "voice-report.";
        Duration min = duration(c, p + "min-window", Duration.ofSeconds(5), Duration.ofDays(1));
        Duration max = duration(c, p + "max-window", min, Duration.ofDays(1));
        Duration def = duration(c, p + "default-window", min, max);
        return new PluginConfig.VoiceReport(
                c.getBoolean(p + "enabled"), def, min, max,
                duration(c, p + "cooldown", Duration.ZERO, Duration.ofDays(1)),
                c.getBoolean(p + "staff-chat-full-transcript"));
    }

    private EvidenceSettings recordings(YamlConfiguration c) {
        EvidenceSettings.Mode mode;
        String raw = c.getString("recordings.mode", "flagged").trim().toUpperCase(Locale.ROOT);
        try {
            mode = EvidenceSettings.Mode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            warnings.add("recordings.mode '" + raw + "' is invalid (none, flagged, all); using flagged");
            mode = EvidenceSettings.Mode.FLAGGED;
        }
        return new EvidenceSettings(
                mode,
                resolve(c.getString("recordings.directory", "recordings")),
                c.getBoolean("recordings.save-audio"),
                intIn(c, "recordings.retention-days", 0, 36_500),
                (long) intIn(c, "recordings.max-total-size-mb", 0, 10_000_000) * 1024 * 1024);
    }

    private DiscordSettings discord(YamlConfiguration c) {
        return new DiscordSettings(
                c.getString("server-name", ""),
                intIn(c, "discord.timeout-seconds", 1, 120),
                intIn(c, "discord.retry-attempts", 0, 10),
                intIn(c, "discord.max-queue-size", 1, 10_000),
                webhook(c, "discord.flags"),
                webhook(c, "discord.mutes"),
                webhook(c, "discord.reports"));
    }

    private WebhookSettings webhook(YamlConfiguration c, String p) {
        String colorRaw = c.getString(p + ".color", "").trim();
        int color = -1;
        if (!colorRaw.isEmpty()) {
            try {
                color = Integer.parseInt(colorRaw.startsWith("#") ? colorRaw.substring(1) : colorRaw, 16) & 0xFFFFFF;
            } catch (NumberFormatException e) {
                warnings.add(p + ".color '" + colorRaw + "' is not a hex colour; using the default");
            }
        }
        String role = c.getString(p + ".ping-role-id", "").trim();
        if (!role.isEmpty() && !role.chars().allMatch(Character::isDigit)) {
            warnings.add(p + ".ping-role-id must be a numeric role id; ignoring it");
            role = "";
        }
        return new WebhookSettings(
                c.getBoolean(p + ".enabled"),
                c.getString(p + ".webhook-url", "").trim(),
                c.getString(p + ".username", "NevusVoice"),
                c.getString(p + ".avatar-url", "").trim(),
                color,
                role,
                c.getBoolean(p + ".include-audio"),
                c.getBoolean(p + ".include-transcript"),
                c.getBoolean(p + ".include-context"),
                c.getBoolean(p + ".include-server-info"),
                c.getBoolean(p + ".spoiler-flagged-words"),
                c.getString(p + ".title", ""),
                c.getString(p + ".footer", ""));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path resolve(String path) {
        Path p = Path.of(path);
        return (p.isAbsolute() ? p : dataFolder.resolve(p)).normalize();
    }

    private Duration duration(YamlConfiguration c, String key, Duration min, Duration max) {
        Object raw = c.get(key);
        Object def = Objects.requireNonNull(c.getDefaults()).get(key);
        Duration fallback = Durations.parseOr(String.valueOf(def), min);
        if (raw == null) {
            return fallback;
        }
        try {
            Duration d = Durations.parse(String.valueOf(raw));
            if (d == null || d.compareTo(min) < 0 || d.compareTo(max) > 0) {
                warnings.add(key + " must be between " + Durations.format(min) + " and " + Durations.format(max)
                        + "; using " + Durations.format(fallback));
                return fallback;
            }
            return d;
        } catch (IllegalArgumentException e) {
            warnings.add(key + " '" + raw + "' is not a valid duration; using " + Durations.format(fallback));
            return fallback;
        }
    }

    private int intIn(YamlConfiguration c, String key, int min, int max) {
        int fallback = Objects.requireNonNull(c.getDefaults()).getInt(key, min);
        if (!c.isSet(key)) {
            return fallback;
        }
        if (!(c.get(key) instanceof Number)) {
            warnings.add(key + " must be a whole number; using " + fallback);
            return fallback;
        }
        double v = ((Number) c.get(key)).doubleValue();
        if (!Double.isFinite(v) || v != Math.rint(v)) {
            warnings.add(key + " must be a whole number; using " + fallback);
            return fallback;
        }
        if (v < min || v > max) {
            warnings.add(key + " must be between " + min + " and " + max + "; using " + fallback);
            return fallback;
        }
        return (int) v;
    }

    private double doubleIn(YamlConfiguration c, String key, double min, double max) {
        double fallback = Objects.requireNonNull(c.getDefaults()).getDouble(key, min);
        if (!c.isSet(key)) {
            return fallback;
        }
        if (!(c.get(key) instanceof Number)) {
            warnings.add(key + " must be a number; using " + fallback);
            return fallback;
        }
        double v = c.getDouble(key);
        if (!Double.isFinite(v) || v < min || v > max) {
            warnings.add(key + " must be between " + min + " and " + max + "; using " + fallback);
            return fallback;
        }
        return v;
    }

    /** Keys present in the user's file that the plugin does not know (typos), for a helpful warning. */
    public static List<String> unknownKeys(Path configFile) throws IOException {
        YamlConfiguration user = YamlConfiguration.loadConfiguration(configFile.toFile());
        YamlConfiguration defaults = bundledDefaults("config.yml");
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, Object> e : user.getValues(true).entrySet()) {
            String key = e.getKey();
            if (e.getValue() instanceof ConfigurationSection || key.startsWith("moderation.mute-ladder.tiers.")) {
                continue;
            }
            if (!defaults.contains(key, true) && !key.startsWith("moderation.mute-ladder.tiers")) {
                unknown.add(key);
            }
        }
        return unknown;
    }
}
