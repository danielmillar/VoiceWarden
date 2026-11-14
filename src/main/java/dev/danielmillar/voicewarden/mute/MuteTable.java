package dev.danielmillar.voicewarden.mute;

import dev.danielmillar.voicewarden.mute.MuteService.Mute;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** SQL for the {@code mutes}, {@code mute_mirrors} and {@code mute_events} tables. Call only on the database thread. */
final class MuteTable {

    private MuteTable() {
    }

    static List<Mute> loadAll(Connection c) throws SQLException {
        Map<UUID, List<VoiceMute>> mirrors = new HashMap<>();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT player_id, speak_permission, listen_permission, server_context
                FROM mute_mirrors ORDER BY player_id, position""");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                mirrors.computeIfAbsent(UUID.fromString(r.getString(1)), id -> new ArrayList<>())
                        .add(new VoiceMute(r.getString(2), r.getString(3), r.getString(4)));
            }
        }
        List<Mute> mutes = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT player_id, player_name, created_at, expires_at, reason, actor, automatic FROM mutes""");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                UUID id = UUID.fromString(r.getString(1));
                mutes.add(new Mute(id, r.getString(2), r.getLong(3), r.getLong(4), r.getString(5), r.getString(6),
                        r.getBoolean(7), List.copyOf(mirrors.getOrDefault(id, List.of()))));
            }
        }
        return mutes;
    }

    /** Replaces the player's stored mute and mirrors. {@code mute.mirrors()} must not be null. */
    static void save(Connection c, Mute mute) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO mutes (player_id, player_name, created_at, expires_at, reason, actor, automatic)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (player_id) DO UPDATE SET player_name = excluded.player_name,
                    created_at = excluded.created_at, expires_at = excluded.expires_at, reason = excluded.reason,
                    actor = excluded.actor, automatic = excluded.automatic""")) {
            s.setString(1, mute.playerId().toString());
            s.setString(2, mute.playerName());
            s.setLong(3, mute.createdAt());
            s.setLong(4, mute.expiresAt());
            s.setString(5, mute.reason());
            s.setString(6, mute.actor());
            s.setBoolean(7, mute.automatic());
            s.executeUpdate();
        }
        deleteMirrors(c, mute.playerId());
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO mute_mirrors (player_id, position, speak_permission, listen_permission, server_context)
                VALUES (?, ?, ?, ?, ?)""")) {
            int position = 0;
            for (VoiceMute mirror : mute.mirrors()) {
                s.setString(1, mute.playerId().toString());
                s.setInt(2, position++);
                s.setString(3, mirror.speakPermission());
                s.setString(4, mirror.listenPermission());
                s.setString(5, mirror.serverContext());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    static void delete(Connection c, UUID player) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM mutes WHERE player_id = ?")) {
            s.setString(1, player.toString());
            s.executeUpdate();
        }
        deleteMirrors(c, player);
    }

    private static void deleteMirrors(Connection c, UUID player) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM mute_mirrors WHERE player_id = ?")) {
            s.setString(1, player.toString());
            s.executeUpdate();
        }
    }

    static void record(Connection c, MuteEvent event) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO mute_events (player_id, player_name, type, at, actor, reason, expires_at, automatic)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            s.setString(1, event.playerId().toString());
            s.setString(2, event.playerName());
            s.setString(3, event.type().key());
            s.setLong(4, event.at());
            s.setString(5, event.actor());
            s.setString(6, event.reason());
            if (event.expiresAt() == null) {
                s.setNull(7, java.sql.Types.INTEGER);
            } else {
                s.setLong(7, event.expiresAt());
            }
            s.setBoolean(8, event.automatic());
            s.executeUpdate();
        }
    }

    /** Newest first. Insertion order breaks ties, since several events can share a millisecond. */
    static MuteEvent.Page history(Connection c, UUID player, int page, int perPage) throws SQLException {
        List<MuteEvent> events = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT player_name, type, at, actor, reason, expires_at, automatic FROM mute_events
                WHERE player_id = ? ORDER BY id DESC LIMIT ? OFFSET ?""")) {
            s.setString(1, player.toString());
            s.setInt(2, perPage + 1);
            s.setLong(3, (long) (page - 1) * perPage);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    long expires = r.getLong(6);
                    Long expiresAt = r.wasNull() ? null : expires;
                    events.add(new MuteEvent(player, r.getString(1),
                            MuteEvent.Type.valueOf(r.getString(2).toUpperCase(Locale.ROOT)), r.getLong(3),
                            r.getString(4), r.getString(5), expiresAt, r.getBoolean(7)));
                }
            }
        }
        boolean more = events.size() > perPage;
        return new MuteEvent.Page(more ? List.copyOf(events.subList(0, perPage)) : List.copyOf(events), page, more);
    }
}
