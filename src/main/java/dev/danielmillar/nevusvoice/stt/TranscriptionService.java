package dev.danielmillar.nevusvoice.stt;

import dev.danielmillar.nevusvoice.audio.AudioSegment;
import dev.danielmillar.nevusvoice.concurrent.PluginExecutors;
import dev.danielmillar.nevusvoice.config.PluginConfig;
import dev.danielmillar.nevusvoice.health.Metrics;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the speech engine lifecycle (download → load → run → swap on reload → release) and the worker threads.
 *
 * <p>Concurrency rules:
 * <ul>
 *   <li>Loads are serialised by {@link #loadLock} and run on an IO thread (downloads can take minutes).</li>
 *   <li>The active {@link Runtime} is published and started only under {@link #swapLock}, which is not held across
 *       IO or native calls. Each runtime is retired exactly once.</li>
 *   <li>Retiring never blocks the caller: workers are signalled and a reaper thread waits for them to leave native
 *       code before releasing the model. A model is never freed while a worker might still be using it, and is always
 *       freed eventually.</li>
 * </ul>
 * On reload the new engine is fully loaded before the old one is retired, so transcription never pauses; queued
 * utterances survive the swap.
 */
public final class TranscriptionService implements AutoCloseable {

    public enum State { STOPPED, PREPARING, LOADING, READY, FAILED }

    private final Path dataFolder;
    private final Downloader downloader;
    private final Executor io;
    private final Supplier<PluginConfig> config;
    private final Metrics metrics;
    private final Logger logger;
    private final Consumer<Transcript> sink;
    private final Consumer<Boolean> readyListener;
    private final SegmentQueue queue = new SegmentQueue();
    private final Object loadLock = new Object();
    private final Object swapLock = new Object();
    private final AtomicLong lastErrorLog = new AtomicLong();
    private final AtomicLong lastDropLog = new AtomicLong();
    private final Object admissionLock = new Object();

    private volatile State state = State.STOPPED;
    private volatile String stateDetail = "";
    private final List<Runtime> liveRuntimes = new ArrayList<>();
    private @Nullable Runtime runtime; // guarded by swapLock
    private volatile int workerCount;
    private volatile boolean closed;

    public TranscriptionService(Path dataFolder, Downloader downloader, Executor io, Supplier<PluginConfig> config,
                                Metrics metrics, Logger logger, Consumer<Transcript> sink, Consumer<Boolean> readyListener) {
        this.dataFolder = dataFolder;
        this.downloader = downloader;
        this.io = io;
        this.config = config;
        this.metrics = metrics;
        this.logger = logger;
        this.sink = sink;
        this.readyListener = readyListener;
    }

    /** Loads (or reloads) the engine in the background with the given settings. */
    public CompletableFuture<Void> start(PluginConfig.SpeechToText settings) {
        return CompletableFuture.runAsync(() -> {
            synchronized (loadLock) {
                if (!closed) {
                    load(settings);
                }
            }
        }, io);
    }

    private void load(PluginConfig.SpeechToText settings) {
        boolean reloading = hasRuntime();
        SherpaOnnxEngine engine = null;
        try {
            if (!reloading) {
                setState(State.PREPARING, "checking model files");
            }
            Path natives = NativeLibraries.ensure(dataFolder, settings.nativeLibraryPath(), settings.autoDownload(), downloader, logger);
            NativeLibraries.use(natives);
            ModelManager.ResolvedModel model = ModelManager.resolve(settings, dataFolder, downloader);
            if (!reloading) {
                setState(State.LOADING, "loading " + model.description());
            }
            long t0 = System.nanoTime();
            engine = SherpaOnnxEngine.load(model, settings);
            long loadMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            Runtime next = new Runtime(engine, settings);
            Runtime previous;
            boolean publish;
            synchronized (swapLock) {
                publish = !closed;
                previous = runtime;
                if (publish) {
                    liveRuntimes.add(next);
                    next.start();
                    runtime = next;
                    workerCount = next.threads.size();
                }
            }
            if (!publish) {
                engine.close();
                return;
            }
            if (previous != null) {
                previous.retire();
            }
            setState(State.READY, model.description());
            readyListener.accept(true);
            logger.info("Speech engine ready: " + model.description() + " (" + settings.workers() + " worker(s), "
                    + settings.cpuThreads() + " CPU thread(s), VAD " + (model.vad() != null ? "on" : "off")
                    + ", loaded in " + loadMillis + " ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted", e, engine);
        } catch (UnsatisfiedLinkError e) {
            fail("native libraries could not be loaded (" + e.getMessage() + "). If you reloaded the plugin with a "
                    + "plugin manager, restart the server instead", e, engine);
        } catch (Throwable e) {
            fail(e.getMessage() == null ? e.toString() : e.getMessage(), e, engine);
        }
    }

    /** {@code engine} is only non-null here if it was never published to a runtime. */
    private void fail(String reason, Throwable error, @Nullable SherpaOnnxEngine engine) {
        boolean published;
        synchronized (swapLock) {
            published = runtime != null && runtime.engine == engine;
        }
        if (engine != null && !published) {
            engine.close();
        }
        if (closed) {
            return;
        }
        if (hasRuntime()) {
            logger.log(Level.SEVERE, "Could not load the new speech engine; keeping the previous one: " + reason, error);
            return;
        }
        setState(State.FAILED, reason);
        readyListener.accept(false);
        logger.log(Level.SEVERE, "Speech engine failed to start: " + reason, error);
    }

    private boolean hasRuntime() {
        synchronized (swapLock) {
            return runtime != null;
        }
    }

    /** Called from decode threads. Never blocks on anything but a short queue lock. */
    public void submit(AudioSegment segment) {
        int dropped;
        synchronized (admissionLock) {
            if (closed || !config.get().enabled()) {
                return;
            }
            PluginConfig.SpeechToText s = config.get().speechToText();
            dropped = queue.offer(segment, s.queueMaxSize(), s.queueMaxPerPlayer());
        }
        if (dropped > 0) {
            metrics.queueDropped.add(dropped);
            long now = System.currentTimeMillis();
            long last = lastDropLog.get();
            if (now - last > 60_000 && lastDropLog.compareAndSet(last, now)) {
                logger.warning("Speech-to-text is falling behind: dropped " + dropped + " queued utterance(s). "
                        + "Consider more speech-to-text.workers / cpu-threads, or check CPU load. (logged at most once a minute)");
            }
        }
    }

    public void discardQueued() {
        synchronized (admissionLock) {
            metrics.queueDropped.add(queue.clear());
        }
    }

    public State state() {
        return state;
    }

    public String stateDetail() {
        return stateDetail;
    }

    /** Lock-free; safe to call from the main thread. */
    public int queueSize() {
        return queue.size();
    }

    public int workers() {
        return workerCount;
    }

    private void setState(State state, String detail) {
        this.state = state;
        this.stateDetail = detail;
    }

    /** Same as {@link #closeAsync()}, ignoring the result. */
    @Override
    public void close() {
        closeAsync();
    }

    /**
     * Returns immediately after signalling the workers. The future completes once every runtime, including ones
     * retired earlier by a reload, has stopped its workers and released its model.
     */
    public CompletableFuture<Void> closeAsync() {
        Runtime r;
        CompletableFuture<Void> stopped;
        synchronized (swapLock) {
            closed = true;
            r = runtime;
            runtime = null;
            workerCount = 0;
            stopped = CompletableFuture.allOf(liveRuntimes.stream().map(live -> live.stopped)
                    .toArray(CompletableFuture[]::new));
        }
        readyListener.accept(false);
        if (r != null) {
            r.retire();
        }
        setState(State.STOPPED, "");
        return stopped;
    }

    /** One loaded engine and its worker threads. */
    private final class Runtime {
        final SherpaOnnxEngine engine;
        final PluginConfig.SpeechToText settings;
        final List<Thread> threads = new ArrayList<>();
        final CompletableFuture<Void> stopped = new CompletableFuture<>();
        final AtomicBoolean retired = new AtomicBoolean();
        volatile boolean running = true;

        Runtime(SherpaOnnxEngine engine, PluginConfig.SpeechToText settings) {
            this.engine = engine;
            this.settings = settings;
        }

        /** Called once, under swapLock, before publication. */
        void start() {
            ThreadFactory factory = PluginExecutors.platform("NevusVoice-STT", Thread.NORM_PRIORITY - 1);
            for (int i = 0; i < settings.workers(); i++) {
                Thread t = factory.newThread(this::workerLoop);
                threads.add(t);
            }
            threads.forEach(Thread::start);
        }

        private void workerLoop() {
            try (SherpaOnnxEngine.Worker worker = engine.newWorker()) {
                long maxAge = settings.queueMaxAge().toNanos();
                while (running) {
                    SegmentQueue.Batch batch = queue.takeBatchSnapshot(settings.maxBatchSize(), 250, maxAge, metrics);
                    if (!batch.segments().isEmpty()) {
                        process(worker, batch.segments(), batch.generation());
                    }
                }
            } catch (InterruptedException e) {
                // retired
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Speech-to-text worker crashed", t);
            }
        }

        private void process(SherpaOnnxEngine.Worker worker, List<AudioSegment> batch, long generation) {
            if (!config.get().enabled() || generation != queue.generation()) {
                metrics.queueDropped.add(batch.size());
                return;
            }
            List<SherpaOnnxEngine.Result> results;
            long t0 = System.nanoTime();
            try {
                results = worker.transcribe(batch);
            } catch (Throwable t) {
                metrics.transcriptionErrors.add(batch.size());
                long now = System.currentTimeMillis();
                long last = lastErrorLog.get();
                if (now - last > 60_000 && lastErrorLog.compareAndSet(last, now)) {
                    logger.log(Level.WARNING, "Transcription failed (further errors suppressed for 60s)", t);
                }
                return;
            }
            long done = System.nanoTime();
            if (!config.get().enabled() || generation != queue.generation()) {
                metrics.queueDropped.add(batch.size());
                return;
            }
            metrics.decodeMillis.add(TimeUnit.NANOSECONDS.toMillis(done - t0));
            for (int i = 0; i < batch.size(); i++) {
                if (!config.get().enabled() || generation != queue.generation()) {
                    metrics.queueDropped.add(batch.size() - i);
                    return;
                }
                AudioSegment segment = batch.get(i);
                SherpaOnnxEngine.Result result = results.get(i);
                if (result.text() == null) {
                    metrics.noSpeech.increment();
                    continue;
                }
                metrics.audioMillis.add(result.speechSamples() * 1000L / SherpaOnnxEngine.SAMPLE_RATE);
                metrics.transcribed.increment();
                long latency = TimeUnit.NANOSECONDS.toMillis(done - segment.endedAtNanos());
                metrics.recordLatency(latency);
                if (result.text().isEmpty()) {
                    continue;
                }
                try {
                    sink.accept(new Transcript(segment, result.text(), latency));
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Moderation of a transcript failed", t);
                }
            }
        }

        /**
         * Idempotent and non-blocking. Workers finish the batch they are decoding (so it is still moderated), then
         * exit; a daemon reaper releases the model only after every worker has left native code.
         */
        void retire() {
            if (!retired.compareAndSet(false, true)) {
                return;
            }
            running = false;
            threads.forEach(Thread::interrupt);
            Thread.ofPlatform().daemon().name("NevusVoice-STT-Reaper").start(() -> {
                for (Thread t : threads) {
                    boolean interrupted = false;
                    while (t.isAlive()) {
                        try {
                            t.join();
                        } catch (InterruptedException e) {
                            interrupted = true; // keep waiting: freeing the model under a live worker would crash
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                try {
                    engine.close();
                    stopped.complete(null);
                } catch (Throwable error) {
                    stopped.completeExceptionally(error);
                } finally {
                    synchronized (swapLock) {
                        liveRuntimes.remove(this);
                    }
                }
            });
        }
    }
}
