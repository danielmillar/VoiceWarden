package dev.voicewarden.core.audio;

public record AudioPipelineStats(
        long framesSubmitted,
        long framesProcessed,
        long framesDroppedQueueFull,
        long framesDroppedPoolExhausted,
        long liveChunksEmitted,
        long finalUtterancesEmitted,
        int activeSessions,
        long framesRejectedInvalid,
        long utterancesDiscardedTooShort,
        long utterancesDropped,
        long decodeErrors,
        long processingErrors,
        int queuedFrames,
        long acousticAbuseEvents) {
}
