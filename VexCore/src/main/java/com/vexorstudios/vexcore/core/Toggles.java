package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Every on/off choice a player makes (tpa requests, night vision, join messages...).
 *
 * <p>Features define their toggles with a default. Only a choice a player actually made gets a
 * row, so changing a default later moves everyone who never touched it. A change is written
 * straight away, through the ordered queue. The settings menu lists toggles by id and runs the
 * owning feature's action, which is the same code its command runs.
 */
public final class Toggles implements PlayerData.Store {

    public record Definition(Feature owner, String id, boolean defaultOn, Consumer<Player> action) {
    }

    private final VexCore plugin;
    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Boolean>> chosen = new ConcurrentHashMap<>();

    public Toggles(VexCore plugin) {
        this.plugin = plugin;
    }

    /** Creates the table. Call after the database opened. */
    public void schema() {
        plugin.database().schema("toggles",
                "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, toggle VARCHAR(64) NOT NULL, "
                        + "state INT NOT NULL, PRIMARY KEY (uuid, toggle))");
    }

    public void define(Feature owner, String id, boolean defaultOn, Consumer<Player> action) {
        definitions.put(id, new Definition(owner, id, defaultOn, action));
    }

    public void clear(Feature owner) {
        definitions.values().removeIf(d -> d.owner == owner);
    }

    public Definition definition(String id) {
        return definitions.get(id);
    }

    public boolean isOn(UUID player, String id) {
        Map<String, Boolean> own = chosen.get(player);
        Boolean value = own == null ? null : own.get(id);
        if (value != null) return value;
        Definition d = definitions.get(id);
        return d == null || d.defaultOn;
    }

    /**
     * Sets a toggle. Returns false (and changes nothing) while the player's data is still
     * loading, so a click during login cannot be overwritten by the load.
     */
    public boolean set(Player player, String id, boolean on) {
        UUID uuid = player.getUniqueId();
        if (!plugin.data().isLoaded(uuid)) return false;
        Boolean before = chosen.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(id, on);
        if (before == null || before != on) {
            Database db = plugin.database();
            String uid = uuid.toString();
            db.queue("toggle " + id, c -> write(db, c, uid, id, on));
        }
        return true;
    }

    /** Flips a toggle and returns the new state; null while the player's data is still loading. */
    public Boolean flip(Player player, String id) {
        boolean now = !isOn(player.getUniqueId(), id);
        return set(player, id, now) ? now : null;
    }

    /** Writes one toggle row. Also used by the SetupCore importer. */
    public static void write(Database db, Connection c, String uuid, String id, boolean on) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.upsert("toggles", new String[]{"uuid", "toggle"}, "state"))) {
            ps.setString(1, uuid);
            ps.setString(2, id);
            ps.setInt(3, on ? 1 : 0);
            ps.executeUpdate();
        }
    }

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Map<String, Boolean> own = new ConcurrentHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT toggle, state FROM " + plugin.database().table("toggles") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) own.put(rs.getString(1), rs.getInt(2) != 0);
            }
        }
        chosen.put(player, own);
    }

    @Override
    public Database.Work save(UUID player) {
        Map<String, Boolean> own = chosen.get(player);
        if (own == null) return null;
        Map<String, Boolean> snapshot = Map.copyOf(own);
        Database db = plugin.database();
        return c -> {
            for (var e : snapshot.entrySet()) write(db, c, player.toString(), e.getKey(), e.getValue());
        };
    }

    @Override
    public void unload(UUID player) {
        chosen.remove(player);
    }
}
