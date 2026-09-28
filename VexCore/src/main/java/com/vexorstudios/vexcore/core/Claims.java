package com.vexorstudios.vexcore.core;

import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which one-time rewards a player has taken, per track ("playtime", "killrewards"). A claim is
 * written at once and can only be made after the player's data loaded, so a reward can never be
 * taken twice across a relog or a crash.
 */
public final class Claims implements PlayerData.Store {

    private final Feature owner;
    private final String track;
    private final Map<UUID, Set<String>> claimed = new ConcurrentHashMap<>();

    public Claims(Feature owner, String track) {
        this.owner = owner;
        this.track = track;
        owner.db().schema("reward_claims", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, "
                + "track VARCHAR(32) NOT NULL, reward VARCHAR(64) NOT NULL, PRIMARY KEY (uuid, track, reward))");
    }

    public Set<String> of(UUID player) {
        return claimed.getOrDefault(player, Set.of());
    }

    /** Marks a reward taken. False if it already was, or the player's data isn't loaded. */
    public boolean claim(Player player, String reward) {
        if (!owner.plugin.data().isLoaded(player.getUniqueId())) return false;
        Set<String> own = claimed.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        if (!own.add(reward)) return false;
        Database db = owner.db();
        String uuid = player.getUniqueId().toString();
        db.queue("claim " + track, c -> insert(db, c, uuid, track, reward));
        return true;
    }

    /** Forgets every claim of a player on this track (admin reset). */
    public void reset(UUID player) {
        claimed.computeIfPresent(player, (k, v) -> ConcurrentHashMap.newKeySet());
        Database db = owner.db();
        db.queue("reset " + track, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("reward_claims") + " WHERE uuid = ? AND track = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, track);
                ps.executeUpdate();
            }
        });
    }

    static void insert(Database db, Connection c, String uuid, String track, String reward) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.insertIgnore("reward_claims", "uuid", "track", "reward"))) {
            ps.setString(1, uuid);
            ps.setString(2, track);
            ps.setString(3, reward);
            ps.executeUpdate();
        }
    }

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Set<String> own = ConcurrentHashMap.newKeySet();
        try (PreparedStatement ps = c.prepareStatement("SELECT reward FROM " + owner.db().table("reward_claims")
                + " WHERE uuid = ? AND track = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, track);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) own.add(rs.getString(1));
            }
        }
        claimed.put(player, own);
    }

    @Override
    public void unload(UUID player) {
        claimed.remove(player);
    }
}
