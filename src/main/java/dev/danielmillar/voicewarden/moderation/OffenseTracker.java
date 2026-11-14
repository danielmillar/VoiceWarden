package dev.danielmillar.voicewarden.moderation;

import dev.danielmillar.voicewarden.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Auto-mute offence counts for the mute ladder. Every count is held in memory (loaded by {@link #load()}) so incidents
 * never wait on storage; changes are written to the {@code offenses} table from inside the same atomic update.
 */
public final class OffenseTracker {

    private final ConcurrentHashMap<UUID, Offenses> counts = new ConcurrentHashMap<>();
    private final Database database;
    private final Logger logger;

    public OffenseTracker(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** Reads every stored count. Startup only. */
    public void load() {
        try {
            counts.putAll(database.read(OffenseTracker::loadAll));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not load offence counts from the database; the mute ladder starts from zero", e);
        }
    }

    /** The offence number the next auto-mute would be (1-based), without recording it. */
    public int peekNext(UUID player, int resetAfterDays) {
        return effective(current(player), resetAfterDays, System.currentTimeMillis()).count() + 1;
    }

    /** Records an auto-mute and returns its offence number (1-based). */
    public int recordNext(UUID player, int resetAfterDays) {
        long now = System.currentTimeMillis();
        return counts.compute(player, (id, stored) -> {
            Offenses base = effective(stored == null ? Offenses.NONE : stored, resetAfterDays, now);
            Offenses next = new Offenses(base.count() + 1, now);
            database.write("an offence count", c -> save(c, id, next));
            return next;
        }).count();
    }

    public CompletableFuture<Offenses> lookup(UUID player, int resetAfterDays) {
        return CompletableFuture.completedFuture(effective(current(player), resetAfterDays, System.currentTimeMillis()));
    }

    /** Clears the player's count. The future completes once the change has been written. */
    public CompletableFuture<Void> reset(UUID player) {
        var written = new AtomicReference<CompletableFuture<Void>>(CompletableFuture.completedFuture(null));
        counts.compute(player, (id, stored) -> {
            written.set(database.write("an offence reset", c -> delete(c, id)));
            return null;
        });
        return written.get();
    }

    /** The stored count, ignoring the ladder reset. */
    public Offenses stored(UUID player) {
        return current(player);
    }

    /** Deletes counts the ladder has already reset. Returns how many. */
    public int prune(long now, int resetAfterDays) {
        if (resetAfterDays <= 0) {
            return 0;
        }
        int[] pruned = {0};
        for (UUID player : counts.keySet()) {
            counts.computeIfPresent(player, (id, stored) -> {
                if (effective(stored, resetAfterDays, now).count() > 0) {
                    return stored;
                }
                pruned[0]++;
                database.write("the removal of a reset offence count", c -> delete(c, id));
                return null;
            });
        }
        return pruned[0];
    }

    private Offenses current(UUID player) {
        return counts.getOrDefault(player, Offenses.NONE);
    }

    private static Offenses effective(Offenses o, int resetAfterDays, long now) {
        if (resetAfterDays > 0 && o.count() > 0 && now - o.lastEpochMillis() > TimeUnit.DAYS.toMillis(resetAfterDays)) {
            return Offenses.NONE;
        }
        return o;
    }

    private static Map<UUID, Offenses> loadAll(Connection c) throws SQLException {
        Map<UUID, Offenses> loaded = new HashMap<>();
        try (PreparedStatement s = c.prepareStatement("SELECT player_id, count, last_offense_at FROM offenses");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                loaded.put(UUID.fromString(r.getString(1)), new Offenses(r.getInt(2), r.getLong(3)));
            }
        }
        return loaded;
    }

    private static void save(Connection c, UUID player, Offenses offenses) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO offenses (player_id, count, last_offense_at) VALUES (?, ?, ?)
                ON CONFLICT (player_id) DO UPDATE SET count = excluded.count,
                    last_offense_at = excluded.last_offense_at""")) {
            s.setString(1, player.toString());
            s.setInt(2, offenses.count());
            s.setLong(3, offenses.lastEpochMillis());
            s.executeUpdate();
        }
    }

    private static void delete(Connection c, UUID player) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM offenses WHERE player_id = ?")) {
            s.setString(1, player.toString());
            s.executeUpdate();
        }
    }
}
