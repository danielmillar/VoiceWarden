package dev.voicewarden.plugin.mute;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

public final class StrikeCountCache {

    public static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(30);
    private static final int PRUNE_THRESHOLD = 4096;

    private static final class Slot {
        int count;
        long fetchedAtMillis;
        boolean hasValue;
        long version;
        boolean refreshing;
        boolean dirty;
    }

    private final BiFunction<UUID, Instant, Integer> loader;
    private final Executor executor;
    private final Clock clock;
    private final long maxAgeMillis;
    private final Map<UUID, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicLong windowEpoch = new AtomicLong();
    private volatile Duration window;

    public StrikeCountCache(BiFunction<UUID, Instant, Integer> loader, Executor executor, Clock clock, Duration window, Duration maxAge) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.window = Objects.requireNonNull(window, "window");
        this.maxAgeMillis = maxAge.toMillis();
    }

    public void setWindow(Duration newWindow) {
        this.window = Objects.requireNonNull(newWindow, "newWindow");
        windowEpoch.incrementAndGet();
        slots.clear();
    }

    public int count(UUID playerId) {
        long now = clock.millis();
        if (slots.size() > PRUNE_THRESHOLD) {
            pruneExpired(now);
        }
        Slot slot = slots.get(playerId);
        if (slot == null || !slot.hasValue || now - slot.fetchedAtMillis >= maxAgeMillis) {
            refresh(playerId);
        }
        Slot latest = slots.get(playerId);
        if (latest == null) {
            return 0;
        }
        synchronized (latest) {
            return latest.hasValue ? latest.count : 0;
        }
    }

    public void put(UUID playerId, int count) {
        long now = clock.millis();
        int clamped = Math.max(0, count);
        slots.compute(playerId, (id, existing) -> {
            Slot slot = existing != null ? existing : new Slot();
            synchronized (slot) {
                slot.count = clamped;
                slot.fetchedAtMillis = now;
                slot.hasValue = true;
                slot.version++;
                slot.dirty = false;
            }
            return slot;
        });
    }

    public void invalidate(UUID playerId) {
        AtomicBoolean shouldLaunch = new AtomicBoolean();
        AtomicLong capturedVersion = new AtomicLong();
        Slot[] holder = new Slot[1];
        long epoch = windowEpoch.get();
        slots.compute(playerId, (id, existing) -> {
            Slot slot = existing != null ? existing : new Slot();
            synchronized (slot) {
                slot.hasValue = false;
                slot.version++;
                if (slot.refreshing) {
                    slot.dirty = true;
                } else {
                    slot.refreshing = true;
                    slot.dirty = false;
                    capturedVersion.set(slot.version);
                    holder[0] = slot;
                    shouldLaunch.set(true);
                }
            }
            return slot;
        });
        if (shouldLaunch.get()) {
            launchRefresh(playerId, holder[0], capturedVersion.get(), epoch);
        }
    }

    public void forget(UUID playerId) {
        slots.computeIfPresent(playerId, (id, slot) -> {
            synchronized (slot) {
                slot.version++;
                slot.hasValue = false;
                slot.dirty = false;
            }
            return null;
        });
    }

    public void refresh(UUID playerId) {
        AtomicBoolean shouldLaunch = new AtomicBoolean();
        AtomicLong capturedVersion = new AtomicLong();
        Slot[] holder = new Slot[1];
        long epoch = windowEpoch.get();
        slots.compute(playerId, (id, existing) -> {
            Slot slot = existing != null ? existing : new Slot();
            synchronized (slot) {
                if (slot.refreshing) {
                    return slot;
                }
                slot.refreshing = true;
                slot.dirty = false;
                capturedVersion.set(slot.version);
                holder[0] = slot;
                shouldLaunch.set(true);
            }
            return slot;
        });
        if (shouldLaunch.get()) {
            launchRefresh(playerId, holder[0], capturedVersion.get(), epoch);
        }
    }

    private void launchRefresh(UUID playerId, Slot capturedSlot, long capturedVersion, long capturedEpoch) {
        Duration activeWindow = window;
        try {
            executor.execute(() -> {
                Integer loaded = null;
                try {
                    Instant now = clock.instant();
                    loaded = loader.apply(playerId, now.minus(activeWindow));
                } finally {
                    finishRefresh(playerId, capturedSlot, capturedVersion, capturedEpoch, loaded);
                }
            });
        } catch (RuntimeException e) {
            finishRefresh(playerId, capturedSlot, capturedVersion, capturedEpoch, null);
            throw e;
        }
    }

    private void finishRefresh(UUID playerId, Slot capturedSlot, long capturedVersion, long capturedEpoch, Integer loaded) {
        AtomicBoolean rerun = new AtomicBoolean();
        AtomicLong nextVersion = new AtomicLong();
        long nextEpoch = windowEpoch.get();
        slots.computeIfPresent(playerId, (id, current) -> {
            if (current != capturedSlot) {
                return current;
            }
            synchronized (current) {
                if (loaded != null && windowEpoch.get() == capturedEpoch && current.version == capturedVersion) {
                    current.count = Math.max(0, loaded);
                    current.fetchedAtMillis = clock.millis();
                    current.hasValue = true;
                }
                if (current.dirty) {
                    current.dirty = false;
                    nextVersion.set(current.version);
                    rerun.set(true);
                } else {
                    current.refreshing = false;
                }
            }
            return current;
        });
        if (rerun.get()) {
            launchRefresh(playerId, capturedSlot, nextVersion.get(), nextEpoch);
        }
    }

    private void pruneExpired(long now) {
        long staleCutoff = Math.max(maxAgeMillis * 4L, maxAgeMillis);
        slots.entrySet().removeIf(entry -> {
            Slot slot = entry.getValue();
            synchronized (slot) {
                return !slot.refreshing && (!slot.hasValue || now - slot.fetchedAtMillis >= staleCutoff);
            }
        });
    }
}
