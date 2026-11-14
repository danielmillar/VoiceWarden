package dev.danielmillar.voicewarden.mute;

import dev.danielmillar.voicewarden.mute.MuteService.Mute;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SQL for the {@code mutes} and {@code mute_mirrors} tables. Call only on the database thread. */
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
}
