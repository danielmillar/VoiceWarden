package dev.danielmillar.voicewarden.consent;

import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Each player's answer to the voice moderation consent prompt. Answers are held in memory (loaded by {@link #load()})
 * so the voice thread checks them with one map lookup; changes are written to the {@code consents} table from inside
 * the same atomic update.
 */
public final class ConsentService {

    /** A player's consent under the current settings. */
    public enum Status {
        /** Notice mode: joining counts as consent. */
        NOT_REQUIRED,
        ACCEPTED,
        DENIED,
        /** Never answered, or answered an older rules version. */
        PENDING
    }

    /** What the caller should do after an answer. */
    public enum Outcome { ACCEPTED, DENIED, KICK }

    /** @param lastSeenAt when the player last joined or left, so answers of players who stopped playing can be deleted */
    public record Answer(boolean accepted, int rulesVersion, long decidedAt, long lastSeenAt) {
    }

    private final ConcurrentHashMap<UUID, Answer> answers = new ConcurrentHashMap<>();
    private final Supplier<PluginConfig.Consent> settings;
    private final Database database;
    private final LongSupplier clock;
    private final Logger logger;
    private volatile Consumer<UUID> deniedListener = player -> { };

    public ConsentService(Supplier<PluginConfig.Consent> settings, Database database, LongSupplier clock, Logger logger) {
        this.settings = settings;
        this.database = database;
        this.clock = clock;
        this.logger = logger;
    }

    /** Reads every stored answer. Startup only, before voice packets flow. */
    public void load() {
        try {
            answers.putAll(database.read(ConsentService::loadAll));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not load voice consent answers; in confirm mode everyone is asked again", e);
        }
    }

    /** The player's consent under the current settings. Safe from any thread. */
    public Status status(UUID player) {
        PluginConfig.Consent consent = settings.get();
        if (!consent.confirm()) {
            return Status.NOT_REQUIRED;
        }
        Answer answer = answers.get(player);
        if (answer == null || answer.rulesVersion() < consent.rulesVersion()) {
            return Status.PENDING;
        }
        return answer.accepted() ? Status.ACCEPTED : Status.DENIED;
    }

    /** The last stored answer, whatever the current mode and rules version. */
    public Optional<Answer> answer(UUID player) {
        return Optional.ofNullable(answers.get(player));
    }

    /** Voice thread. Whether VoiceWarden may decode, transcribe or record this player's voice. */
    public boolean allowsProcessing(UUID player) {
        Status status = status(player);
        return status == Status.NOT_REQUIRED || status == Status.ACCEPTED;
    }

    /** Voice thread. Whether this player's microphone is blocked because they have not accepted. */
    public boolean blocksSpeech(UUID player) {
        return !settings.get().speakWithoutConsent() && !allowsProcessing(player);
    }

    /**
     * Whether to show the prompt on join or after a reload. A denial under {@code stay} stands until the player changes
     * it with /voiceconsent; under {@code kick} the player is asked again, so a kick is never a dead end.
     */
    public boolean shouldAsk(UUID player) {
        return switch (status(player)) {
            case PENDING -> true;
            case DENIED -> settings.get().onDeny() == PluginConfig.OnDeny.KICK;
            case NOT_REQUIRED, ACCEPTED -> false;
        };
    }

    /** Stores the player's answer to the current rules version. */
    public Outcome answer(UUID player, boolean accepted) {
        PluginConfig.Consent consent = settings.get();
        long now = clock.getAsLong();
        Answer answer = new Answer(accepted, consent.rulesVersion(), now, now);
        answers.compute(player, (id, previous) -> {
            database.write("a voice consent answer", c -> save(c, id, answer));
            return answer;
        });
        if (accepted) {
            return Outcome.ACCEPTED;
        }
        deniedListener.accept(player);
        return consent.onDeny() == PluginConfig.OnDeny.KICK ? Outcome.KICK : Outcome.DENIED;
    }

    /** Called with the player after they deny. */
    public void onDenied(Consumer<UUID> listener) {
        this.deniedListener = listener;
    }

    /** Join and quit. Keeps a returning player's answer from being pruned. */
    public void seen(UUID player) {
        long now = clock.getAsLong();
        answers.computeIfPresent(player, (id, a) -> {
            database.write("when a player was last seen", c -> {
                try (PreparedStatement s = c.prepareStatement("UPDATE consents SET last_seen_at = ? WHERE player_id = ?")) {
                    s.setLong(1, now);
                    s.setString(2, id.toString());
                    s.executeUpdate();
                }
            });
            return new Answer(a.accepted(), a.rulesVersion(), a.decidedAt(), now);
        });
    }

    /** Deletes answers of players not seen for {@code days} days, except players online now. Returns how many. */
    public int prune(long now, int days, Predicate<UUID> online) {
        if (days <= 0) {
            return 0;
        }
        long cutoff = now - TimeUnit.DAYS.toMillis(days);
        int[] pruned = {0};
        for (UUID player : answers.keySet()) {
            answers.computeIfPresent(player, (id, a) -> {
                if (a.lastSeenAt() >= cutoff || online.test(id)) {
                    return a;
                }
                pruned[0]++;
                database.write("the removal of an old voice consent answer", c -> delete(c, id));
                return null;
            });
        }
        return pruned[0];
    }

    /** Deletes the player's answer. Returns whether there was one. */
    public boolean forget(UUID player) {
        boolean[] had = {false};
        answers.compute(player, (id, a) -> {
            had[0] = a != null;
            database.write("the removal of a voice consent answer", c -> delete(c, id));
            return null;
        });
        return had[0];
    }

    private static void delete(Connection c, UUID player) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM consents WHERE player_id = ?")) {
            s.setString(1, player.toString());
            s.executeUpdate();
        }
    }

    private static Map<UUID, Answer> loadAll(Connection c) throws SQLException {
        Map<UUID, Answer> loaded = new HashMap<>();
        try (PreparedStatement s = c.prepareStatement("SELECT player_id, accepted, rules_version, decided_at, last_seen_at FROM consents");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                loaded.put(UUID.fromString(r.getString(1)), new Answer(r.getInt(2) != 0, r.getInt(3), r.getLong(4), r.getLong(5)));
            }
        }
        return loaded;
    }

    private static void save(Connection c, UUID player, Answer answer) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO consents (player_id, accepted, rules_version, decided_at, last_seen_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (player_id) DO UPDATE SET accepted = excluded.accepted,
                    rules_version = excluded.rules_version, decided_at = excluded.decided_at,
                    last_seen_at = excluded.last_seen_at""")) {
            s.setString(1, player.toString());
            s.setInt(2, answer.accepted() ? 1 : 0);
            s.setInt(3, answer.rulesVersion());
            s.setLong(4, answer.decidedAt());
            s.setLong(5, answer.lastSeenAt());
            s.executeUpdate();
        }
    }
}
