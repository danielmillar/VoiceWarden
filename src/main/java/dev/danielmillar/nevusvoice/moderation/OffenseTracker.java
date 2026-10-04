package dev.danielmillar.nevusvoice.moderation;

import dev.danielmillar.nevusvoice.luckperms.LuckPermsHook;
import dev.danielmillar.nevusvoice.luckperms.LuckPermsHook.Offenses;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Auto-mute offense counts for the mute ladder. LuckPerms meta is the durable, network-wide store; this class keeps an
 * in-memory copy so concurrent incidents for one player are counted atomically, and writes through asynchronously.
 */
public final class OffenseTracker {

    private final ConcurrentHashMap<UUID, Offenses> cache = new ConcurrentHashMap<>();
    private final LuckPermsHook luckPerms;
    private final Logger logger;

    public OffenseTracker(LuckPermsHook luckPerms, Logger logger) {
        this.luckPerms = luckPerms;
        this.logger = logger;
    }

    /** The offense number the next auto-mute would be (1-based), without recording it. */
    public int peekNext(UUID player, int resetAfterDays) {
        return effective(current(player), resetAfterDays, System.currentTimeMillis()).count() + 1;
    }

    /** Records an auto-mute and returns its offense number (1-based). */
    public int recordNext(UUID player, int resetAfterDays) {
        long now = System.currentTimeMillis();
        Offenses updated = cache.compute(player, (k, v) -> {
            Offenses base = effective(v != null ? v : luckPerms.cachedOffenses(k).orElse(Offenses.NONE), resetAfterDays, now);
            return new Offenses(base.count() + 1, now);
        });
        persist(player, updated);
        return updated.count();
    }

    public CompletableFuture<Offenses> lookup(UUID player, int resetAfterDays) {
        Offenses cached = cache.get(player);
        CompletableFuture<Offenses> source = cached != null ? CompletableFuture.completedFuture(cached) : luckPerms.offenses(player);
        return source.thenApply(o -> effective(o, resetAfterDays, System.currentTimeMillis()));
    }

    public CompletableFuture<Void> reset(UUID player) {
        cache.put(player, Offenses.NONE);
        return luckPerms.storeOffenses(player, Offenses.NONE);
    }

    /** Forget the in-memory copy (player left) so the next lookup sees changes made on other servers. */
    public void forget(UUID player) {
        cache.remove(player);
    }

    private Offenses current(UUID player) {
        Offenses cached = cache.get(player);
        return cached != null ? cached : luckPerms.cachedOffenses(player).orElse(Offenses.NONE);
    }

    private static Offenses effective(Offenses o, int resetAfterDays, long now) {
        if (resetAfterDays > 0 && o.count() > 0 && now - o.lastEpochMillis() > TimeUnit.DAYS.toMillis(resetAfterDays)) {
            return Offenses.NONE;
        }
        return o;
    }

    private void persist(UUID player, Offenses offenses) {
        luckPerms.storeOffenses(player, offenses).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not store offense count in LuckPerms", error);
            return null;
        });
    }
}
