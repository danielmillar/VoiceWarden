package dev.danielmillar.nevusvoice.config;

import dev.danielmillar.nevusvoice.evidence.EvidenceSettings;
import dev.danielmillar.nevusvoice.notify.discord.DiscordSettings;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;

/** Immutable snapshot of config.yml. A new instance is built on every reload and swapped atomically. */
public record PluginConfig(
        boolean enabled,
        String serverName,
        SpeechToText speechToText,
        Audio audio,
        Moderation moderation,
        Mute mute,
        Alerts alerts,
        CustomCommands customCommands,
        TranscriptBuffer transcriptBuffer,
        VoiceReport voiceReport,
        EvidenceSettings recordings,
        DiscordSettings discord,
        boolean privacyJoinMessage,
        Logging logging,
        Duration healthCheckInterval
) {

    /**
     * Speech-to-text engine settings. Any change requires the engine to be rebuilt (done automatically on reload).
     *
     * @param model             preset id ({@code parakeet-tdt-v2}, {@code parakeet-tdt-v3}, {@code whisper-small-en}, {@code whisper-turbo}) or {@code custom}
     * @param nativeLibraryPath directory containing sherpa-onnx native libraries; empty = download automatically
     */
    public record SpeechToText(
            String model,
            boolean autoDownload,
            String nativeLibraryPath,
            int workers,
            int cpuThreads,
            int maxBatchSize,
            int queueMaxSize,
            int queueMaxPerPlayer,
            Duration queueMaxAge,
            InputGain inputGain,
            Vad vad,
            CustomModel custom
    ) {
    }

    public record InputGain(boolean enabled, double targetDbfs, double maxGainDb) {
    }

    public record Vad(boolean enabled, float threshold, Duration minSpeech, Duration minSilence, Duration padding,
                      boolean trimSilence) {
    }

    public record CustomModel(String modelType, String encoder, String decoder, String joiner, String tokens) {
    }

    /**
     * Audio capture and utterance segmentation.
     *
     * @param minLevelDbfs      utterances whose loudest 20 ms frame is below this RMS level are discarded
     * @param decodeThreads     Opus decode threads; 0 = automatic
     * @param maxBufferedFrames per-player backlog of undecoded 20 ms frames before packets are dropped
     */
    public record Audio(
            Duration silenceTimeout,
            Duration minUtterance,
            Duration maxUtterance,
            double minLevelDbfs,
            boolean ignoreWhispers,
            int decodeThreads,
            Duration idleSessionTimeout,
            int maxBufferedFrames
    ) {
    }

    public record Moderation(
            boolean dryRun,
            int flagThreshold,
            Duration flagWindow,
            boolean warnBelowThreshold,
            Duration muteDuration,
            MuteLadder ladder,
            int contextLines,
            Set<String> languages,
            List<String> profanityWords,
            List<String> muteWords
    ) {
        /** Mute length for the given offense number (1-based), honouring the ladder when enabled. */
        public Duration muteDurationFor(int offense) {
            return ladder.enabled() ? ladder.durationFor(offense) : muteDuration;
        }
    }

    /**
     * Escalating auto-mute durations for repeat offenders.
     *
     * @param tiers           offense number → mute duration
     * @param maxTierDuration used for offenses beyond the highest configured tier
     * @param resetAfterDays  offense count resets when the last offense is older than this (0 = never)
     */
    public record MuteLadder(boolean enabled, NavigableMap<Integer, Duration> tiers, Duration maxTierDuration,
                             int resetAfterDays) {
        public Duration durationFor(int offense) {
            Duration exact = tiers.get(offense);
            if (exact != null) {
                return exact;
            }
            var lastTier = tiers.lastEntry();
            if (lastTier == null || offense > lastTier.getKey()) {
                return maxTierDuration;
            }
            var floor = tiers.floorEntry(offense);
            return floor != null ? floor.getValue() : tiers.firstEntry().getValue();
        }
    }

    /**
     * @param luckPerms        also apply mutes as temporary LuckPerms permission nodes (persistent + network-wide)
     * @param serverContext    LuckPerms {@code server} context for mute nodes; empty = global
     */
    public record Mute(
            boolean disableListening,
            boolean luckPerms,
            String speakPermission,
            String listenPermission,
            String serverContext,
            boolean notifyPlayer
    ) {
    }

    public record Alerts(boolean staff, boolean console) {
    }

    public record CustomCommands(List<String> mute, List<String> profanity, List<String> warn) {
    }

    /**
     * @param audioMemoryBytes shared budget for buffered speech audio used by voice reports (0 = keep no audio)
     */
    public record TranscriptBuffer(boolean enabled, Duration retention, int maxLinesPerPlayer, long audioMemoryBytes) {
    }

    public record VoiceReport(
            boolean enabled,
            Duration defaultWindow,
            Duration minWindow,
            Duration maxWindow,
            Duration cooldown,
            boolean staffChatFullTranscript
    ) {
        public Duration clamp(@Nullable Duration requested) {
            Duration d = requested == null ? defaultWindow : requested;
            if (d.compareTo(minWindow) < 0) {
                return minWindow;
            }
            return d.compareTo(maxWindow) > 0 ? maxWindow : d;
        }
    }

    public record Logging(boolean transcripts, boolean debug) {
    }
}
