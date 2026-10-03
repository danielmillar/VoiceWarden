package dev.danielmillar.voicesentinel.concurrent;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every thread NevusVoice uses. Nothing here ever runs on, or waits for, the Minecraft server thread.
 *
 * <ul>
 *   <li>{@link #decode()}: small fixed pool of platform threads for Opus decoding, resampling and segmentation
 *       (CPU-bound, short tasks; per-player work is serialised by the speaker session). Tasks are submitted from
 *       Simple Voice Chat's packet thread, so the pool is pre-started and backed by a lock-free
 *       {@link LinkedTransferQueue}: submitting never takes a lock and never spawns a thread on the caller.</li>
 *   <li>{@link #timer()}: one thread for periodic housekeeping (silence detection, expiries, health checks).
 *       Tasks scheduled here must be tiny and hand real work to another executor.</li>
 *   <li>{@link #io()}: virtual threads for blocking IO (files, downloads, HTTP, LuckPerms callbacks).</li>
 * </ul>
 * Speech-to-text workers are dedicated platform threads owned by the transcription service.
 */
public final class PluginExecutors implements AutoCloseable {

    private final Logger logger;
    private final ThreadPoolExecutor decode;
    private final ScheduledThreadPoolExecutor timer;
    private final ExecutorService io;

    public PluginExecutors(int decodeThreads, Logger logger) {
        this.logger = logger;
        this.decode = new ThreadPoolExecutor(decodeThreads, decodeThreads, 0, TimeUnit.SECONDS,
                new LinkedTransferQueue<>(), platform("NevusVoice-Decode", Thread.NORM_PRIORITY - 1));
        this.decode.prestartAllCoreThreads();
        this.timer = new ScheduledThreadPoolExecutor(1, platform("NevusVoice-Timer", Thread.NORM_PRIORITY));
        this.timer.setRemoveOnCancelPolicy(true);
        this.timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.io = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("NevusVoice-IO-", 0).factory());
    }

    /** 1 decode thread per 4 cores, between 1 and 4. */
    public static int autoDecodeThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 4);
    }

    public ExecutorService decode() {
        return decode;
    }

    public ScheduledExecutorService timer() {
        return timer;
    }

    public ExecutorService io() {
        return io;
    }

    public static ThreadFactory platform(String name, int priority) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, name + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(priority);
            t.setUncaughtExceptionHandler((thread, error) ->
                    Logger.getLogger("NevusVoice").log(Level.SEVERE, "Uncaught error in " + thread.getName(), error));
            return t;
        };
    }

    /**
     * Executor for non-critical IO (evidence files) that refuses work beyond {@code maxPending} queued/running tasks
     * instead of accumulating unbounded tasks (each holding audio) when storage is slow. Refused tasks are dropped
     * and counted; callers must not wait on them.
     */
    public static Executor bounded(Executor delegate, int maxPending, Logger logger, String what) {
        Semaphore permits = new Semaphore(maxPending);
        AtomicLong lastWarn = new AtomicLong();
        return task -> {
            if (!permits.tryAcquire()) {
                long now = System.currentTimeMillis();
                long last = lastWarn.get();
                if (now - last > 60_000 && lastWarn.compareAndSet(last, now)) {
                    logger.warning(what + " is falling behind (" + maxPending + " pending); skipping new work. Check disk speed.");
                }
                return;
            }
            try {
                delegate.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        permits.release();
                    }
                });
            } catch (RuntimeException e) {
                permits.release();
            }
        };
    }

    /** Stops all executors, waiting at most {@code timeoutMillis} in total. */
    public void shutdown(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (ExecutorService executor : List.of(timer, decode, io)) {
            executor.shutdown();
        }
        for (ExecutorService executor : List.of(timer, decode, io)) {
            try {
                long left = deadline - System.nanoTime();
                if (left <= 0 || !executor.awaitTermination(left, TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (!decode.isTerminated() || !io.isTerminated()) {
            logger.fine("Some NevusVoice tasks were still running at shutdown and were interrupted");
        }
    }

    @Override
    public void close() {
        shutdown(250);
    }
}
