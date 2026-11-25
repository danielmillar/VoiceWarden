package dev.voicewarden.plugin.command;

import dev.voicewarden.core.audio.AudioPipelineStats;
import dev.voicewarden.core.discord.DiscordWebhookClient;
import dev.voicewarden.core.moderation.ModerationEngine;
import dev.voicewarden.core.storage.StorageProvider;
import dev.voicewarden.core.wordlist.AhoCorasickMatcher;
import dev.voicewarden.core.wordlist.RuleMatcher;
import dev.voicewarden.plugin.consent.ConsentManager;
import dev.voicewarden.plugin.mute.NativeMuteService;
import dev.voicewarden.plugin.pipeline.VoiceModerationPipeline;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface AdminRuntime {

    record StorageStatus(String description, boolean available, int pendingWrites, String lastFailure) {

        public StorageStatus {
            Objects.requireNonNull(description, "description");
            lastFailure = lastFailure == null ? "" : lastFailure;
        }
    }

    record ReloadResult(boolean success, String error, List<String> notes) {

        public ReloadResult {
            error = error == null ? "" : error;
            notes = List.copyOf(notes);
        }

        public static ReloadResult ok(List<String> notes) {
            return new ReloadResult(true, "", notes);
        }

        public static ReloadResult failed(String error) {
            return new ReloadResult(false, error, List.of());
        }
    }

    NativeMuteService mutes();

    ModerationEngine moderation();

    DiscordWebhookClient discord();

    StorageProvider storage();

    StorageStatus storageStatus();

    VoiceModerationPipeline pipeline();

    Optional<AudioPipelineStats> audioStats();

    String engineState();

    List<String> voiceBackends();

    AhoCorasickMatcher wordlist();

    default RuleMatcher rules() {
        return RuleMatcher.fromWordlist(wordlist());
    }

    Duration defaultMuteDuration();

    UpdateChecker updates();

    ConsentManager consent();

    ReloadResult reload();
}
