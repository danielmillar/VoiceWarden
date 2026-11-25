package dev.voicewarden.core.storage;

import dev.voicewarden.api.VoiceMuteRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResilientStorageManager implements StorageProvider {

    static final Duration FALLBACK_RETENTION = Duration.ofDays(1);
    static final int MAX_PENDING_WRITES = 4096;
    static final int MAX_REPLAYED_REPORT_IDS = 4096;

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientStorageManager.class);

    private final StorageProviderFactory factory;
    private final String description;
    private final Duration queryTimeout;
    private final Duration reconnectInterval;
    private final Clock clock;
    private final InMemoryFallbackStore fallback;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler;
    private final ConcurrentLinkedQueue<Consumer<StorageProvider>> pendingWrites = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingWriteCount = new AtomicInteger();
    private final Map<Long, Long> replayedReportIds = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> replayedReportOrder = new ConcurrentLinkedQueue<>();
    private final AtomicReference<CompletableFuture<Boolean>> connectAttempt = new AtomicReference<>();
    private final ReentrantLock replayLock = new ReentrantLock();
    private final ReentrantReadWriteLock routingLock = new ReentrantReadWriteLock();
    private final AtomicReference<StorageProvider> primary = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> retiringPrimary =
            new AtomicReference<>(CompletableFuture.completedFuture(null));
    private final AtomicReference<CompletableFuture<Boolean>> initialConnect = new AtomicReference<>();
    private volatile String lastFailure = "";
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ResilientStorageManager(
            StorageProviderFactory factory,
            String description,
            String serverId,
            Duration queryTimeout,
            Duration reconnectInterval,
            Clock clock) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.description = Objects.requireNonNull(description, "description");
        this.queryTimeout = requirePositive(queryTimeout, "queryTimeout");
        this.reconnectInterval = requirePositive(reconnectInterval, "reconnectInterval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fallback = new InMemoryFallbackStore(Objects.requireNonNull(serverId, "serverId"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("VoiceWarden-Storage-Reconnect")
                .daemon(true)
                .factory());
    }

    public static ResilientStorageManager create(StorageConfig config) {
        return new ResilientStorageManager(
                StorageProviderFactory.forConfig(config),
                config.type().name(),
                config.serverId(),
                Duration.ofMillis(config.queryTimeoutMs()),
                Duration.ofMillis(config.reconnectIntervalMs()),
                Clock.systemUTC());
    }

    public CompletableFuture<Boolean> start() {
        if (closed.get()) {
            return CompletableFuture.completedFuture(false);
        }
        if (!started.compareAndSet(false, true)) {
            CompletableFuture<Boolean> existing = initialConnect.get();
            return existing != null ? existing : CompletableFuture.completedFuture(isAvailable());
        }
        long intervalMs = reconnectInterval.toMillis();
        try {
            scheduler.scheduleWithFixedDelay(this::maintain, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> future = reconnectNow();
        initialConnect.compareAndSet(null, future);
        return future;
    }

    public CompletableFuture<Boolean> reconnectNow() {
        if (closed.get()) {
            return CompletableFuture.completedFuture(false);
        }
        if (primary.get() != null) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> attempt = new CompletableFuture<>();
        CompletableFuture<Boolean> running = connectAttempt.compareAndExchange(null, attempt);
        if (running != null) {
            return running;
        }
        try {
            virtualThreads.execute(() -> {
                boolean connected = false;
                try {
                    connected = connect();
                } catch (RuntimeException ignored) {
                } finally {
                    connectAttempt.compareAndSet(attempt, null);
                    attempt.complete(connected);
                }
            });
        } catch (RuntimeException e) {
            connectAttempt.compareAndSet(attempt, null);
            attempt.complete(false);
        }
        return attempt;
    }

    @Override
    public boolean isAvailable() {
        return primary.get() != null;
    }

    public boolean isUsingFallback() {
        return primary.get() == null;
    }

    public int pendingWriteCount() {
        return pendingWriteCount.get();
    }

    public String lastFailure() {
        return lastFailure;
    }

    public String description() {
        return description;
    }

    @Override
    public void saveMute(VoiceMuteRecord mute) {
        Objects.requireNonNull(mute, "mute");
        if (closed.get()) {
            return;
        }
        Consumer<StorageProvider> queued = p -> p.saveMute(mute);
        StorageProvider current = beginWriteOrQueue(queued);
        if (current == null) {
            if (!closed.get()) {
                fallback.saveMute(mute);
            }
            return;
        }
        Attempt<Boolean> attempt = onProvider(current, p -> {
            p.saveMute(mute);
            return Boolean.TRUE;
        });
        if (attempt.primaryDown()) {
            queueAfterFailure(queued);
        }
        if (!closed.get()) {
            fallback.saveMute(mute);
        }
    }

    @Override
    public int deactivateActiveMutes(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (closed.get()) {
            return 0;
        }
        Consumer<StorageProvider> queued = p -> p.deactivateActiveMutes(playerId);
        StorageProvider current = beginWriteOrQueue(queued);
        if (current == null) {
            return closed.get() ? 0 : fallback.deactivateActiveMutes(playerId);
        }
        Attempt<Integer> attempt = onProvider(current, p -> p.deactivateActiveMutes(playerId));
        int local = closed.get() ? 0 : fallback.deactivateActiveMutes(playerId);
        if (attempt.primaryDown()) {
            queueAfterFailure(queued);
        }
        return attempt.stored() ? attempt.value() : local;
    }

    @Override
    public Optional<VoiceMuteRecord> findActiveMute(UUID playerId, Instant now) {
        return read(p -> p.findActiveMute(playerId, now));
    }

    @Override
    public List<VoiceMuteRecord> findAllActiveMutes(Instant now) {
        return read(p -> p.findAllActiveMutes(now));
    }

    @Override
    public List<VoiceMuteRecord> findMuteHistory(UUID playerId, int limit) {
        return read(p -> p.findMuteHistory(playerId, limit));
    }

    @Override
    public int countMutesInWindow(UUID playerId, Instant since, boolean networkWide) {
        return read(p -> p.countMutesInWindow(playerId, since, networkWide));
    }

    @Override
    public StrikeRecord recordStrike(StrikeRecord strike) {
        Objects.requireNonNull(strike, "strike");
        if (closed.get()) {
            return strike;
        }
        Consumer<StorageProvider> queued = p -> p.recordStrike(strike);
        StorageProvider current = beginWriteOrQueue(queued);
        if (current == null) {
            return closed.get() ? strike : fallback.recordStrike(strike);
        }
        Attempt<StrikeRecord> attempt = onProvider(current, p -> p.recordStrike(strike));
        StrikeRecord local = closed.get() ? strike : fallback.recordStrike(strike);
        if (attempt.primaryDown()) {
            queueAfterFailure(queued);
        }
        return attempt.stored() ? attempt.value() : local;
    }

    @Override
    public int countActiveStrikes(UUID playerId, Instant since, boolean networkWide) {
        return read(p -> p.countActiveStrikes(playerId, since, networkWide));
    }

    @Override
    public int pardonStrikes(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (closed.get()) {
            return 0;
        }
        Consumer<StorageProvider> queued = p -> p.pardonStrikes(playerId);
        StorageProvider current = beginWriteOrQueue(queued);
        if (current == null) {
            return closed.get() ? 0 : fallback.pardonStrikes(playerId);
        }
        Attempt<Integer> attempt = onProvider(current, p -> p.pardonStrikes(playerId));
        int local = closed.get() ? 0 : fallback.pardonStrikes(playerId);
        if (attempt.primaryDown()) {
            queueAfterFailure(queued);
        }
        return attempt.stored() ? attempt.value() : local;
    }

    @Override
    public VoiceReportRecord saveReport(VoiceReportRecord report) {
        Objects.requireNonNull(report, "report");
        if (closed.get()) {
            return report;
        }
        StorageProvider current;
        routingLock.readLock().lock();
        try {
            if (closed.get()) {
                return report;
            }
            current = primary.get();
            if (current == null) {
                VoiceReportRecord local = fallback.saveReport(report);
                enqueuePending(p -> recordReplayedReportId(local.id(), p.saveReport(report).id()));
                return local;
            }
        } finally {
            routingLock.readLock().unlock();
        }
        Attempt<VoiceReportRecord> attempt = onProvider(current, p -> p.saveReport(report));
        if (attempt.stored()) {
            return attempt.value();
        }
        if (closed.get()) {
            return report;
        }
        VoiceReportRecord local = fallback.saveReport(report);
        if (attempt.primaryDown()) {
            queueAfterFailure(p -> recordReplayedReportId(local.id(), p.saveReport(report).id()));
        }
        return local;
    }

    @Override
    public List<VoiceReportRecord> findReports(boolean unreviewedOnly, int limit) {
        return read(p -> p.findReports(unreviewedOnly, limit));
    }

    @Override
    public Optional<VoiceReportRecord> findReportById(long id) {
        long target = replayedReportIds.getOrDefault(id, id);
        if (target <= 0) {
            return fallback.findReportById(id);
        }
        Attempt<Optional<VoiceReportRecord>> attempt = onPrimary(p -> p.findReportById(target));
        return attempt.stored() ? attempt.value() : fallback.findReportById(id);
    }

    @Override
    public boolean markReportReviewed(long id, String reviewer, Instant reviewedAt) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        if (closed.get()) {
            return false;
        }
        Consumer<StorageProvider> queued = p -> {
            long resolved = replayedReportIds.getOrDefault(id, id);
            if (resolved > 0) {
                p.markReportReviewed(resolved, reviewer, reviewedAt);
            }
        };
        StorageProvider current;
        long target;
        routingLock.readLock().lock();
        try {
            if (closed.get()) {
                return false;
            }
            target = replayedReportIds.getOrDefault(id, id);
            current = primary.get();
            if (current == null || target <= 0) {
                boolean local = fallback.markReportReviewed(id, reviewer, reviewedAt);
                enqueuePending(queued);
                return local || id > 0;
            }
        } finally {
            routingLock.readLock().unlock();
        }
        long resolvedTarget = target;
        Attempt<Boolean> attempt = onProvider(current, p -> p.markReportReviewed(resolvedTarget, reviewer, reviewedAt));
        if (attempt.stored()) {
            return attempt.value();
        }
        if (closed.get()) {
            return false;
        }
        boolean local = fallback.markReportReviewed(id, reviewer, reviewedAt);
        if (!attempt.primaryDown()) {
            return local;
        }
        queueAfterFailure(queued);
        return local || id > 0;
    }

    @Override
    public int countOpenReportsByReporter(UUID reporterId) {
        return read(p -> p.countOpenReportsByReporter(reporterId));
    }

    public <T> CompletableFuture<T> supplyAsync(Function<StorageProvider, T> operation) {
        Objects.requireNonNull(operation, "operation");
        return CompletableFuture.supplyAsync(() -> operation.apply(this), virtualThreads);
    }

    public CompletableFuture<Void> saveMuteAsync(VoiceMuteRecord mute) {
        return supplyAsync(storage -> {
            storage.saveMute(mute);
            return null;
        });
    }

    public CompletableFuture<Optional<VoiceMuteRecord>> findActiveMuteAsync(UUID playerId) {
        return supplyAsync(storage -> storage.findActiveMute(playerId, clock.instant()));
    }

    public CompletableFuture<List<VoiceMuteRecord>> findAllActiveMutesAsync() {
        return supplyAsync(storage -> storage.findAllActiveMutes(clock.instant()));
    }

    public CompletableFuture<StrikeRecord> recordStrikeAsync(StrikeRecord strike) {
        return supplyAsync(storage -> storage.recordStrike(strike));
    }

    public CompletableFuture<Integer> countActiveStrikesAsync(UUID playerId, Instant since, boolean networkWide) {
        return supplyAsync(storage -> storage.countActiveStrikes(playerId, since, networkWide));
    }

    public CompletableFuture<PardonResult> pardonPlayerAsync(UUID playerId) {
        return supplyAsync(storage -> storage.pardonPlayer(playerId));
    }

    public CompletableFuture<VoiceReportRecord> saveReportAsync(VoiceReportRecord report) {
        return supplyAsync(storage -> storage.saveReport(report));
    }

    public CompletableFuture<List<VoiceReportRecord>> findReportsAsync(boolean unreviewedOnly, int limit) {
        return supplyAsync(storage -> storage.findReports(unreviewedOnly, limit));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        StorageProvider current;
        routingLock.writeLock().lock();
        try {
            current = primary.getAndSet(null);
            pendingWrites.clear();
            pendingWriteCount.set(0);
        } finally {
            routingLock.writeLock().unlock();
        }
        if (current != null) {
            closeQuietly(current);
        }
        virtualThreads.shutdownNow();
        fallback.close();
    }

    private record Attempt<T>(T value, boolean primaryDown) {

        boolean stored() {
            return value != null;
        }
    }

    private StorageProvider beginWriteOrQueue(Consumer<StorageProvider> write) {
        routingLock.readLock().lock();
        try {
            if (closed.get()) {
                return null;
            }
            StorageProvider current = primary.get();
            if (current == null) {
                enqueuePending(write);
            }
            return current;
        } finally {
            routingLock.readLock().unlock();
        }
    }

    private void queueAfterFailure(Consumer<StorageProvider> write) {
        StorageProvider reconnected;
        routingLock.readLock().lock();
        try {
            if (closed.get()) {
                return;
            }
            enqueuePending(write);
            reconnected = primary.get();
        } finally {
            routingLock.readLock().unlock();
        }
        if (reconnected != null) {
            replay(reconnected);
        }
    }

    private void enqueuePending(Consumer<StorageProvider> write) {
        pendingWrites.add(write);
        if (pendingWriteCount.incrementAndGet() > MAX_PENDING_WRITES) {
            if (pendingWrites.poll() != null) {
                pendingWriteCount.decrementAndGet();
            }
        }
    }

    private void recordReplayedReportId(long localId, long primaryId) {
        replayedReportIds.put(localId, primaryId);
        replayedReportOrder.add(localId);
        while (replayedReportIds.size() > MAX_REPLAYED_REPORT_IDS) {
            Long oldest = replayedReportOrder.poll();
            if (oldest == null) {
                break;
            }
            replayedReportIds.remove(oldest);
        }
    }

    private <T> T read(Function<StorageProvider, T> operation) {
        Attempt<T> attempt = onPrimary(operation);
        return attempt.stored() ? attempt.value() : operation.apply(fallback);
    }

    private <T> Attempt<T> onPrimary(Function<StorageProvider, T> operation) {
        StorageProvider current = primary.get();
        if (current == null) {
            return new Attempt<>(null, true);
        }
        return onProvider(current, operation);
    }

    private <T> Attempt<T> onProvider(StorageProvider current, Function<StorageProvider, T> operation) {
        try {
            return new Attempt<>(withTimeout(current, operation), false);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException) {
                return new Attempt<>(null, false);
            }
            if (isTimeout(e) || !stillHealthy(current)) {
                demote(current, e);
                return new Attempt<>(null, true);
            }
            LOGGER.error("{} storage rejected a call while healthy. Serving it from memory and not retrying it.",
                    description, e);
            return new Attempt<>(null, false);
        }
    }

    private boolean stillHealthy(StorageProvider provider) {
        try {
            return Boolean.TRUE.equals(withTimeout(provider, StorageProvider::isAvailable));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isTimeout(RuntimeException e) {
        return e instanceof StorageException && e.getCause() instanceof TimeoutException;
    }

    private <T> T withTimeout(StorageProvider provider, Function<StorageProvider, T> operation) {
        Future<T> future = virtualThreads.submit(() -> operation.apply(provider));
        try {
            return future.get(queryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StorageException(description + " storage did not answer within " + queryTimeout.toMillis() + "ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new StorageException(description + " storage failed: " + cause, cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new StorageException(description + " storage call was interrupted", e);
        }
    }

    private void demote(StorageProvider failed, Throwable cause) {
        if (!primary.compareAndSet(failed, null)) {
            return;
        }
        lastFailure = String.valueOf(cause.getMessage());
        LOGGER.warn("{} storage is unavailable ({}). Using in-memory fallback and retrying every {}s.",
                description, lastFailure, reconnectInterval.toSeconds());
        CompletableFuture<Void> closing = new CompletableFuture<>();
        retiringPrimary.set(closing);
        try {
            virtualThreads.execute(() -> {
                try {
                    closeQuietly(failed);
                } finally {
                    closing.complete(null);
                }
            });
        } catch (RuntimeException e) {
            closeQuietly(failed);
            closing.complete(null);
        }
    }

    private boolean connect() {
        awaitRetiringPrimary();
        StorageProvider opened;
        try {
            opened = factory.open();
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            if (!message.equals(lastFailure)) {
                LOGGER.warn("Cannot reach {} storage ({}). Using in-memory fallback and retrying every {}s.",
                        description, message, reconnectInterval.toSeconds());
            }
            lastFailure = message;
            return false;
        }
        if (closed.get()) {
            closeQuietly(opened);
            return false;
        }
        if (!replay(opened)) {
            closeQuietly(opened);
            return false;
        }
        routingLock.writeLock().lock();
        try {
            if (closed.get()) {
                closeQuietly(opened);
                return false;
            }
            if (primary.get() != null) {
                closeQuietly(opened);
                return true;
            }
            if (!replay(opened)) {
                closeQuietly(opened);
                return false;
            }
            primary.set(opened);
        } finally {
            routingLock.writeLock().unlock();
        }
        if (!lastFailure.isEmpty()) {
            LOGGER.info("{} storage is available again.", description);
        }
        lastFailure = "";
        return true;
    }

    private void awaitRetiringPrimary() {
        CompletableFuture<Void> retiring = retiringPrimary.get();
        if (retiring == null || retiring.isDone()) {
            return;
        }
        try {
            retiring.get(queryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
        }
    }

    private boolean replay(StorageProvider target) {
        replayLock.lock();
        try {
            Consumer<StorageProvider> next;
            while ((next = pendingWrites.peek()) != null) {
                Consumer<StorageProvider> write = next;
                try {
                    withTimeout(target, provider -> {
                        write.accept(provider);
                        return Boolean.TRUE;
                    });
                } catch (RuntimeException e) {
                    if (isTimeout(e) || !stillHealthy(target)) {
                        lastFailure = String.valueOf(e.getMessage());
                        LOGGER.warn("Cannot replay {} queued writes to {} storage ({}). Will retry.",
                                pendingWriteCount.get(), description, lastFailure);
                        demote(target, e);
                        return false;
                    }
                    LOGGER.error("{} storage rejected a queued write while healthy. Dropping that write.", description, e);
                }
                if (pendingWrites.poll() != null) {
                    pendingWriteCount.decrementAndGet();
                }
            }
            return true;
        } finally {
            replayLock.unlock();
        }
    }

    private void maintain() {
        if (closed.get()) {
            return;
        }
        try {
            Instant now = clock.instant();
            fallback.pruneBefore(now.minus(FALLBACK_RETENTION), now);
            StorageProvider current = primary.get();
            if (current == null) {
                reconnectNow();
                return;
            }
            Boolean healthy;
            try {
                healthy = withTimeout(current, StorageProvider::isAvailable);
            } catch (RuntimeException e) {
                demote(current, e);
                return;
            }
            if (!Boolean.TRUE.equals(healthy)) {
                demote(current, new StorageException(description + " storage failed its health check"));
                return;
            }
            if (!pendingWrites.isEmpty()) {
                replay(current);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("{} storage maintenance failed", description, e);
        }
    }

    private static void closeQuietly(StorageProvider provider) {
        try {
            provider.close();
        } catch (RuntimeException e) {
            LOGGER.debug("Closing storage provider failed", e);
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
        return value;
    }
}
