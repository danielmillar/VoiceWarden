package dev.voicewarden.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.core.audio.AudioConfig;
import dev.voicewarden.core.audio.MicSpamConfig;
import dev.voicewarden.core.moderation.MuteLadderSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ConfigLoaderTest {

    @Test
    void bundledConfigLoadsWithoutWarnings() {
        ConfigLoadResult<VoiceWardenConfig> result = ConfigLoader.loadConfig(ConfigFiles.defaultConfigTemplate());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void defaultConfigMatchesThePlan() {
        VoiceWardenConfig config = ConfigLoader.loadConfig(ConfigFiles.defaultConfigTemplate()).value();

        assertEquals(2, config.configVersion());
        assertEquals("server-1", config.serverId());
        assertEquals(VoiceWardenConfig.VoiceBackend.AUTO, config.voiceBackend());
        assertTrue(config.monitorGroups());

        EngineConfig engine = config.engine();
        assertEquals(EngineConfig.Profile.ENGLISH_ACCURATE, engine.profile());
        assertEquals(2, engine.threads());
        assertEquals(2048, engine.maxMemoryMb());
        assertEquals("models", engine.modelsDir());
        assertTrue(engine.autoDownload());
        assertEquals(10, engine.nicePriority());
        assertTrue(engine.doubleCheckEnabled());
        assertTrue(engine.requireConfirmation());
        assertEquals(new SileroVadConfig(true, 0.5, 150, 300), engine.sileroVad());

        AudioConfig audio = config.audio();
        assertEquals(500, audio.silenceThresholdMs());
        assertEquals(200, audio.minAudioDurationMs());
        assertEquals(15, audio.maxUtteranceSeconds());
        assertTrue(audio.liveDetection());
        assertEquals(200, audio.liveChunkMs());
        assertEquals(200, audio.preRollMs());
        assertEquals(220.0, audio.vadRmsThreshold());
        assertEquals(2, audio.workerThreads());

        assertEquals(new WordlistConfig(List.of("en"), false, false, List.of(), List.of(), List.of()), config.wordlist());

        ModerationConfig moderation = config.moderation();
        assertEquals(2, moderation.flagThreshold());
        assertEquals(10, moderation.windowMinutes());
        assertTrue(moderation.networkStrikes());
        assertEquals(Map.of(
                FlagSeverity.MILD, List.of(VoiceAction.REPORT, VoiceAction.WARN, VoiceAction.MUTE),
                FlagSeverity.MODERATE, List.of(VoiceAction.WARN, VoiceAction.MUTE),
                FlagSeverity.SEVERE, List.of(VoiceAction.MUTE)), moderation.escalation());
        assertEquals("", moderation.appealUrl());
        assertTrue(moderation.autoWarnBuiltin());
        assertTrue(moderation.autoMuteBuiltin());

        MuteLadderSettings ladder = config.muteLadder();
        assertTrue(ladder.enabled());
        assertEquals(new TreeMap<>(Map.of(
                1, Duration.ofMinutes(5),
                2, Duration.ofMinutes(15),
                3, Duration.ofMinutes(30),
                4, Duration.ofHours(1),
                5, Duration.ofHours(6))), ladder.tiers());
        assertEquals(Duration.ofHours(24), ladder.maxTierDuration());
        assertEquals(Duration.ofDays(30), ladder.resetAfter());
        assertEquals(Duration.ofMinutes(60), ladder.fallbackDuration());

        MutesConfig mutes = config.mutes();
        assertEquals(MutesConfig.Enforcement.NATIVE, mutes.enforcement());
        assertFalse(mutes.mirrorLuckPerms());
        assertEquals(new MutesConfig.Block(true, true, MutesConfig.GroupBlock.ALL, List.of(), false), mutes.block());
        assertEquals(MutesConfig.Indicator.ACTION_BAR, mutes.indicator());
        assertTrue(mutes.notifyOnAttempt());
        assertEquals(30, mutes.attemptCooldownSeconds());

        assertEquals(new ConsentConfig(true, ConsentConfig.Mode.NOTICE, ConsentConfig.OnDeny.IGNORE, 1, 40), config.consent());
        assertEquals(new TranscriptConfig(43_200, 500, 4, 60, 48), config.transcripts());
        assertEquals(new VoiceReportConfig(true, VoiceReportConfig.Mode.COMMAND, 5, 1, 30, List.of(1, 5, 10, 15), 60, 3, false, "",
                true, 45), config.voiceReport());

        DiscordWebhookConfig discord = config.discord();
        assertFalse(discord.enabled());
        assertEquals("", discord.webhookUrl());
        assertEquals("VoiceWarden", discord.username());
        assertEquals(0xE74C3C, discord.color());
        assertEquals(DiscordWebhookConfig.TranscriptInclusion.BOTH, discord.includeTranscript());
        assertTrue(discord.includeServerInfo());
        assertFalse(discord.includeAudio());
        assertEquals(10, discord.timeoutSeconds());
        assertEquals(3, discord.maxRetries());
        assertEquals(List.of(), discord.rolePings());

        DiscordWebhookConfig discordMute = config.discordMute();
        assertFalse(discordMute.enabled());
        assertEquals(DiscordWebhookConfig.TranscriptInclusion.MUTED, discordMute.includeTranscript());
        assertTrue(discordMute.includeServerInfo());
        assertFalse(discordMute.includeAudio());

        assertEquals(Map.of(), config.commands().keywordCommands());
        assertEquals(List.of(), config.commands().muteCommands());
        assertEquals(List.of(), config.commands().profanityCommands());
        assertTrue(config.commands().builtinOnKeywordCommand());
        assertTrue(config.commands().builtinOnMuteCommand());
        assertTrue(config.commands().builtinOnProfanityCommand());

        assertEquals(new StorageConfig(StorageConfig.Type.SQLITE, "localhost", 3306, "voicewarden", "voicewarden", "", "", 10, 2000),
                config.storage());
        assertTrue(config.placeholderApiEnabled());
        assertTrue(config.updateCheckEnabled());

        assertEquals(Duration.ofMinutes(10), config.moderationSettings().window());
        assertTrue(config.moderationSettings().requireConfirmation());
    }

    @Test
    void micSpamDefaultsMatchTheBundledConfigAndTheRecordDefaults() {
        MicSpamConfig micSpam = ConfigLoader.loadConfig(ConfigFiles.defaultConfigTemplate()).value().audio().micSpam();

        assertEquals(MicSpamConfig.defaults(), micSpam);
        assertTrue(micSpam.enabled());
        assertEquals(28_000, micSpam.clippingSampleThreshold());
        assertEquals(0.12, micSpam.clippingRatio());
        assertEquals(15_000.0, micSpam.rmsThreshold());
        assertEquals(800, micSpam.minDurationMs());
        assertEquals(15, micSpam.cooldownSeconds());
        assertEquals(List.of(VoiceAction.WARN, VoiceAction.MUTE), micSpam.escalation());
        assertEquals(VoiceAction.WARN, micSpam.actionForHit(1));
        assertEquals(VoiceAction.MUTE, micSpam.actionForHit(2));
        assertEquals(VoiceAction.MUTE, micSpam.actionForHit(5));
        assertEquals(5, micSpam.windowMinutes());
        assertEquals(Duration.ofMinutes(5), micSpam.muteDuration());
        assertTrue(micSpam.notifyDiscord());
    }

    @Test
    void micSpamOverridesLoadAndOutOfRangeValuesAreClampedWithWarnings() {
        String yaml = """
                audio:
                  mic_spam:
                    enabled: off
                    clipping_sample_threshold: 40000
                    clipping_ratio: 0.3
                    rms_threshold: 20000
                    min_duration_ms: 50
                    cooldown_seconds: 30
                    escalation: [report, warn, mute]
                    window_minutes: 10
                    mute_duration: "10m"
                    notify_discord: false
                """;
        ConfigLoadResult<VoiceWardenConfig> result = ConfigLoader.loadConfig(yaml);
        MicSpamConfig micSpam = result.value().audio().micSpam();

        assertFalse(micSpam.enabled());
        assertEquals(MicSpamConfig.MAX_CLIPPING_SAMPLE_THRESHOLD, micSpam.clippingSampleThreshold());
        assertEquals(0.3, micSpam.clippingRatio());
        assertEquals(20_000.0, micSpam.rmsThreshold());
        assertEquals(MicSpamConfig.MIN_DURATION_MS, micSpam.minDurationMs());
        assertEquals(30, micSpam.cooldownSeconds());
        assertEquals(List.of(VoiceAction.REPORT, VoiceAction.WARN, VoiceAction.MUTE), micSpam.escalation());
        assertEquals(10, micSpam.windowMinutes());
        assertEquals(Duration.ofMinutes(10), micSpam.muteDuration());
        assertFalse(micSpam.notifyDiscord());
        assertEquals(2, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("audio.mic_spam.clipping_sample_threshold")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("audio.mic_spam.min_duration_ms")));
    }

    @Test
    void existingConfigWithoutMicSpamGainsTheBlockOnUpdate() {
        String template = ConfigFiles.defaultConfigTemplate();
        int start = template.indexOf("  # Catches mic spam");
        int end = template.indexOf("\n\n", start) + 1;
        String beforeMicSpam = template.substring(0, start) + template.substring(end);
        assertFalse(beforeMicSpam.contains("mic_spam"));

        YamlUpdateResult result = CommentPreservingYamlUpdater.forConfig().merge(template, beforeMicSpam);

        assertTrue(result.changed());
        assertTrue(result.addedKeys().stream().anyMatch(key -> key.startsWith("audio.mic_spam")), result.addedKeys().toString());
        ConfigLoadResult<VoiceWardenConfig> loaded = ConfigLoader.loadConfig(result.content());
        assertEquals(List.of(), loaded.warnings());
        assertEquals(MicSpamConfig.defaults(), loaded.value().audio().micSpam());
    }

    @Test
    void invalidMicSpamActionAndDurationFallBackToDefaults() {
        String yaml = """
                audio:
                  mic_spam:
                    action: ban
                    mute_duration: "soon"
                """;
        ConfigLoadResult<VoiceWardenConfig> result = ConfigLoader.loadConfig(yaml);
        MicSpamConfig micSpam = result.value().audio().micSpam();

        assertEquals(MicSpamConfig.DEFAULT_ESCALATION, micSpam.escalation());
        assertEquals(Duration.ofMinutes(5), micSpam.muteDuration());
        assertEquals(2, result.warnings().size(), result.warnings().toString());

        ConfigLoadResult<VoiceWardenConfig> legacy = ConfigLoader.loadConfig("""
                audio:
                  mic_spam:
                    action: mute
                """);
        assertEquals(List.of(VoiceAction.MUTE), legacy.value().audio().micSpam().escalation());
        assertEquals(List.of(), legacy.warnings());
    }

    @Test
    void emptyFileFallsBackToTheBundledDefaults() {
        assertEquals(ConfigLoader.loadConfig(ConfigFiles.defaultConfigTemplate()).value(), ConfigLoader.loadConfig("").value());
        assertEquals(ConfigLoader.defaultConfig(), VoiceWardenConfig.defaults());
        assertEquals(ConfigLoader.loadMessages(ConfigFiles.defaultMessagesTemplate()).value(), ConfigLoader.loadMessages("").value());
    }

    @Test
    void invalidValuesFallBackWithWarnings() {
        ConfigLoadResult<VoiceWardenConfig> result = ConfigLoader.loadConfig("""
                voice_backend: TEAMSPEAK
                engine:
                  threads: lots
                  nice_priority: 99
                moderation:
                  flag_threshold: 0
                  escalation:
                    mild: [report, shout]
                mute_ladder:
                  tiers:
                    1: "2m"
                    two: "5m"
                    3: "soon"
                  max_tier_duration: perm
                storage:
                  type: postgres
                """);
        VoiceWardenConfig config = result.value();
        assertEquals(VoiceWardenConfig.VoiceBackend.AUTO, config.voiceBackend());
        assertEquals(2, config.engine().threads());
        assertEquals(19, config.engine().nicePriority());
        assertEquals(1, config.moderation().flagThreshold());
        assertEquals(List.of(VoiceAction.REPORT, VoiceAction.WARN, VoiceAction.MUTE), config.moderation().escalation().get(FlagSeverity.MILD));
        assertEquals(new TreeMap<>(Map.of(1, Duration.ofMinutes(2))), config.muteLadder().tiers());
        assertEquals(Duration.ofHours(24), config.muteLadder().maxTierDuration());
        assertEquals(StorageConfig.Type.SQLITE, config.storage().type());
        assertEquals(9, result.warnings().size(), String.join("\n", result.warnings()));
        assertTrue(result.warnings().stream().allMatch(warning -> warning.startsWith("config.yml: ")));
    }

    @Test
    void conversationContextSettingsLoadAndClamp() {
        ConfigLoadResult<VoiceWardenConfig> custom = ConfigLoader.loadConfig("""
                transcripts:
                  context_lines: 0
                  context_window_seconds: 120
                  context_radius_blocks: 16
                """);
        assertEquals(new TranscriptConfig(43_200, 500, 0, 120, 16), custom.value().transcripts());
        assertFalse(custom.value().transcripts().contextEnabled());
        assertTrue(custom.warnings().isEmpty(), String.join("\n", custom.warnings()));

        ConfigLoadResult<VoiceWardenConfig> invalid = ConfigLoader.loadConfig("""
                transcripts:
                  context_lines: -3
                  context_window_seconds: 1
                  context_radius_blocks: far
                """);
        TranscriptConfig transcripts = invalid.value().transcripts();
        assertEquals(new TranscriptConfig(43_200, 500, 0, 10, 48), transcripts);
        assertEquals(3, invalid.warnings().size(), String.join("\n", invalid.warnings()));
        assertFalse(transcripts.contextEnabled());
        assertTrue(transcripts.sameRetention(new TranscriptConfig(43_200, 500, 4, 60, 1)));
        assertFalse(transcripts.sameRetention(new TranscriptConfig(60, 500, 4, 60, 48)));
    }

    @Test
    void sileroVadSettingsAreReadAndClamped() {
        VoiceWardenConfig tuned = ConfigLoader.loadConfig("""
                engine:
                  silero_vad:
                    enabled: off
                    threshold: 0.35
                    min_speech_duration_ms: 100
                    min_silence_duration_ms: 450
                """).value();
        assertEquals(new SileroVadConfig(false, 0.35, 100, 450), tuned.engine().sileroVad());

        ConfigLoadResult<VoiceWardenConfig> wild = ConfigLoader.loadConfig("""
                engine:
                  silero_vad:
                    threshold: 1.5
                    min_speech_duration_ms: 5
                    min_silence_duration_ms: 60000
                """);
        assertEquals(new SileroVadConfig(true, SileroVadConfig.MAX_THRESHOLD, SileroVadConfig.MIN_DURATION_MS,
                SileroVadConfig.MAX_SILENCE_DURATION_MS), wild.value().engine().sileroVad());
        assertEquals(3, wild.warnings().size(), String.join("\n", wild.warnings()));
        assertTrue(wild.warnings().stream().allMatch(w -> w.contains("engine.silero_vad.")), String.join("\n",
                wild.warnings()));

        ConfigLoadResult<VoiceWardenConfig> missing = ConfigLoader.loadConfig("engine:\n  threads: 3\n");
        assertEquals(ConfigLoader.defaultConfig().engine().sileroVad(), missing.value().engine().sileroVad());
        assertTrue(missing.warnings().isEmpty(), String.join("\n", missing.warnings()));
    }

    @Test
    void coercesCommonOperatorSpellings() {
        VoiceWardenConfig config = ConfigLoader.loadConfig("""
                server_id: 42
                voice_backend: plasmovoice
                wordlist:
                  languages: EN
                  mute-list: [badword]
                mutes:
                  block:
                    groups: open-only
                voice_report:
                  mode: gui
                  preset_minutes: ["2", 4]
                discord:
                  color: 255
                discord_mute:
                  color: "0x00FF00"
                commands:
                  keyword_commands:
                    griefing: "kick {player}"
                    raid: ["say {player} {words}", "ban {player}"]
                  mute_commands: "say muted {player}"
                storage:
                  password: 123456
                  port: "3307"
                """).value();
        assertEquals("42", config.serverId());
        assertEquals(VoiceWardenConfig.VoiceBackend.PLASMOVOICE, config.voiceBackend());
        assertEquals(List.of("en"), config.wordlist().languages());
        assertEquals(List.of("badword"), config.wordlist().muteList());
        assertEquals(MutesConfig.GroupBlock.OPEN_ONLY, config.mutes().block().groups());
        assertEquals(VoiceReportConfig.Mode.GUI, config.voiceReport().mode());
        assertEquals(List.of(2, 4), config.voiceReport().presetMinutes());
        assertEquals(255, config.discord().color());
        assertEquals(0x00FF00, config.discordMute().color());
        assertEquals(List.of("griefing", "raid"), new ArrayList<>(config.commands().keywordCommands().keySet()));
        assertEquals(List.of("kick {player}"), config.commands().keywordCommands().get("griefing"));
        assertEquals(List.of("say muted {player}"), config.commands().muteCommands());
        assertEquals("123456", config.storage().password());
        assertEquals(3307, config.storage().port());
    }

    @Test
    void voiceReportAudioSettingsLoadClampAndDisable() {
        ConfigLoadResult<VoiceWardenConfig> tooLong = ConfigLoader.loadConfig("""
                voice_report:
                  webhook_url: "https://discord.com/api/webhooks/2/token"
                  include_audio: true
                  audio_buffer_seconds: 500
                """);
        assertEquals(120, tooLong.value().voiceReport().audioBufferSeconds());
        assertTrue(tooLong.value().voiceReport().audioCaptureEnabled());
        assertTrue(tooLong.warnings().stream().anyMatch(w -> w.contains("voice_report.audio_buffer_seconds")), tooLong.warnings().toString());

        VoiceReportConfig off = ConfigLoader.loadConfig("""
                voice_report:
                  webhook_url: "https://discord.com/api/webhooks/2/token"
                  audio_buffer_seconds: 0
                """).value().voiceReport();
        assertEquals(0, off.audioBufferSeconds());
        assertFalse(off.audioCaptureEnabled());

        VoiceReportConfig noAudio = ConfigLoader.loadConfig("""
                voice_report:
                  webhook_url: "https://discord.com/api/webhooks/2/token"
                  include_audio: off
                """).value().voiceReport();
        assertFalse(noAudio.includeAudio());
        assertEquals(45, noAudio.audioBufferSeconds());
        assertFalse(noAudio.audioCaptureEnabled());

        assertFalse(ConfigLoader.defaultConfig().voiceReport().audioCaptureEnabled(), "no webhook means nothing to attach audio to");
    }

    @Test
    void brokenYamlLoadsDefaultsWithAWarning() {
        ConfigLoadResult<VoiceWardenConfig> broken = ConfigLoader.loadConfig("engine: [unclosed\n");
        assertEquals(ConfigLoader.defaultConfig(), broken.value());
        assertEquals(1, broken.warnings().size());
        ConfigLoadResult<VoiceWardenConfig> scalar = ConfigLoader.loadConfig("just text");
        assertEquals(ConfigLoader.defaultConfig(), scalar.value());
        assertEquals(1, scalar.warnings().size());
    }

    @Test
    void secretsStayOutOfToString() {
        VoiceWardenConfig config = ConfigLoader.loadConfig("""
                storage:
                  password: hunter2
                discord:
                  webhook_url: "https://discord.com/api/webhooks/1/secret-token"
                voice_report:
                  webhook_url: "https://discord.com/api/webhooks/2/other-token"
                """).value();
        String text = config.toString();
        assertFalse(text.contains("hunter2"));
        assertFalse(text.contains("secret-token"));
        assertFalse(text.contains("other-token"));
        assertTrue(text.contains("<redacted>"));
    }

    @Test
    void everyMessageKeyHasADefaultAndEveryDefaultHasAKey() {
        Map<?, ?> tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(ConfigFiles.defaultMessagesTemplate());
        Set<String> leaves = new TreeSet<>();
        collectLeaves("", tree, leaves);
        leaves.remove(CommentPreservingYamlUpdater.DEFAULT_VERSION_KEY);
        leaves.removeIf(path -> path.startsWith("theme."));
        Set<String> keys = new TreeSet<>();
        for (MessageKey key : EnumSet.allOf(MessageKey.class)) {
            keys.add(key.path());
        }
        assertEquals(leaves, keys);
        ConfigLoadResult<MessagesConfig> loaded = ConfigLoader.loadMessages(ConfigFiles.defaultMessagesTemplate());
        assertEquals(List.of(), loaded.warnings());
        assertEquals(MessagesConfig.DEFAULT_THEME, loaded.value().theme());
    }

    @Test
    void messagesFallBackPerKeyAndJoinLists() {
        ConfigLoadResult<MessagesConfig> result = ConfigLoader.loadMessages("""
                prefix: "<primary>[VW]</primary> "
                mute:
                  muted: [not, "a string"]
                  unmuted: 12
                """);
        MessagesConfig messages = result.value();
        assertEquals("<primary>[VW]</primary> ", messages.prefix());
        assertEquals("not\na string", messages.get(MessageKey.MUTE_MUTED));
        assertEquals("12", messages.get(MessageKey.MUTE_UNMUTED));
        MessagesConfig defaults = ConfigLoader.loadMessages("").value();
        assertEquals(defaults.get(MessageKey.ALERTS_FLAGGED_UNCONFIRMED), messages.get(MessageKey.ALERTS_FLAGGED_UNCONFIRMED));
        assertTrue(defaults.get(MessageKey.ALERTS_FLAGGED_UNCONFIRMED).contains("unconfirmed"));
        assertTrue(defaults.get(MessageKey.COMMANDS_HELP).contains("\n"));
        assertEquals(List.of(), result.warnings());
    }

    private static void collectLeaves(String prefix, Map<?, ?> map, Set<String> out) {
        map.forEach((key, value) -> {
            String path = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                collectLeaves(path, nested, out);
            } else {
                out.add(path);
            }
        });
    }
}
