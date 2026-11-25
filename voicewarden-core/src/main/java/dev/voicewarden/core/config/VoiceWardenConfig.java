package dev.voicewarden.core.config;

import dev.voicewarden.core.audio.AudioConfig;
import dev.voicewarden.core.moderation.CustomCommandSettings;
import dev.voicewarden.core.moderation.ModerationSettings;
import dev.voicewarden.core.moderation.MuteLadderSettings;
import java.time.Duration;
import java.util.Objects;

public record VoiceWardenConfig(
        int configVersion,
        String serverId,
        VoiceBackend voiceBackend,
        boolean monitorGroups,
        EngineConfig engine,
        AudioConfig audio,
        WordlistConfig wordlist,
        ModerationConfig moderation,
        MuteLadderSettings muteLadder,
        MutesConfig mutes,
        ConsentConfig consent,
        TranscriptConfig transcripts,
        VoiceReportConfig voiceReport,
        DiscordWebhookConfig discord,
        DiscordWebhookConfig discordMute,
        CustomCommandSettings commands,
        StorageConfig storage,
        boolean placeholderApiEnabled,
        boolean updateCheckEnabled) {

    public enum VoiceBackend {
        AUTO,
        SVC,
        PLASMOVOICE
    }

    public VoiceWardenConfig {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(voiceBackend, "voiceBackend");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(audio, "audio");
        Objects.requireNonNull(wordlist, "wordlist");
        Objects.requireNonNull(moderation, "moderation");
        Objects.requireNonNull(muteLadder, "muteLadder");
        Objects.requireNonNull(mutes, "mutes");
        Objects.requireNonNull(consent, "consent");
        Objects.requireNonNull(transcripts, "transcripts");
        Objects.requireNonNull(voiceReport, "voiceReport");
        Objects.requireNonNull(discord, "discord");
        Objects.requireNonNull(discordMute, "discordMute");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(storage, "storage");
    }

    public static VoiceWardenConfig defaults() {
        return ConfigLoader.defaultConfig();
    }

    public ModerationSettings moderationSettings() {
        return new ModerationSettings(
                moderation.flagThreshold(),
                Duration.ofMinutes(moderation.windowMinutes()),
                moderation.escalation(),
                engine.requireConfirmation(),
                moderation.autoWarnBuiltin(),
                moderation.autoMuteBuiltin());
    }
}
