package dev.danielmillar.nevusvoice.health;

import dev.danielmillar.nevusvoice.config.PluginConfig;
import dev.danielmillar.nevusvoice.stt.TranscriptionService;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Periodic health check: watches for audio being dropped, rising latency or a failed engine and logs actionable
 * warnings (rate-limited). Runs on the timer thread and only reads counters.
 */
public final class HealthMonitor implements AutoCloseable {

    private static final long WARN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long SLOW_P95_MILLIS = 10_000;

    private final Supplier<PluginConfig> config;
    private final Metrics metrics;
    private final TranscriptionService transcription;
    private final Logger logger;
    private ScheduledFuture<?> task;
    private Duration scheduledInterval;

    private long lastQueueDropped;
    private long lastStale;
    private long lastPacketsDropped;
    private long lastErrors;
    private long lastWarnBacklog;
    private long lastWarnLatency;
    private long lastWarnFailed;

    public HealthMonitor(Supplier<PluginConfig> config, Metrics metrics, TranscriptionService transcription, Logger logger) {
        this.config = config;
        this.metrics = metrics;
        this.transcription = transcription;
        this.logger = logger;
    }

    public synchronized void start(ScheduledExecutorService timer) {
        Duration interval = config.get().healthCheckInterval();
        if (task != null) {
            if (interval.equals(scheduledInterval)) {
                return;
            }
            task.cancel(false);
        }
        scheduledInterval = interval;
        task = timer.scheduleWithFixedDelay(this::check, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void check() {
        long now = System.currentTimeMillis();
        long queueDropped = metrics.queueDropped.sum();
        long stale = metrics.staleDropped.sum();
        long packetsDropped = metrics.packetsDropped.sum();
        long errors = metrics.transcriptionErrors.sum();
        long lost = (queueDropped - lastQueueDropped) + (stale - lastStale) + (packetsDropped - lastPacketsDropped);
        if (lost > 0 && now - lastWarnBacklog > WARN_INTERVAL_MILLIS) {
            lastWarnBacklog = now;
            logger.warning("Health: voice audio is being skipped because transcription can't keep up ("
                    + (queueDropped - lastQueueDropped) + " queue overflow, " + (stale - lastStale) + " too old, "
                    + (packetsDropped - lastPacketsDropped) + " packets). Speed is "
                    + String.format(java.util.Locale.ROOT, "%.1f", metrics.speedFactor())
                    + "× real time. Raise speech-to-text.workers or cpu-threads if you have spare cores.");
        }
        long[] latency = metrics.latencySummary();
        if (latency[1] > SLOW_P95_MILLIS && now - lastWarnLatency > WARN_INTERVAL_MILLIS) {
            lastWarnLatency = now;
            logger.warning("Health: transcription latency is high (p95 " + latency[1] + " ms). Moderation is lagging behind speech.");
        }
        if (errors > lastErrors && now - lastWarnLatency > WARN_INTERVAL_MILLIS) {
            logger.warning("Health: " + (errors - lastErrors) + " transcription error(s) since the last check.");
        }
        if (transcription.state() == TranscriptionService.State.FAILED && now - lastWarnFailed > TimeUnit.HOURS.toMillis(1)) {
            lastWarnFailed = now;
            logger.severe("Health: the speech engine is not running (" + transcription.stateDetail()
                    + "). Voice is NOT being moderated. Fix the issue and run /nevusvoice reload.");
        }
        lastQueueDropped = queueDropped;
        lastStale = stale;
        lastPacketsDropped = packetsDropped;
        lastErrors = errors;
    }

    @Override
    public synchronized void close() {
        if (task != null) {
            task.cancel(false);
        }
    }
}
