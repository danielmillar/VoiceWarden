package dev.voicewarden.engine;

public record SchedulerStats(
        long processedLive,
        long processedFinal,
        long processedVerify,
        long droppedOverload,
        long failed,
        int queuedJobs) {
}
