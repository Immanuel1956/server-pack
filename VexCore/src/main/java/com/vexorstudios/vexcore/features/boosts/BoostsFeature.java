package com.vexorstudios.vexcore.features.boosts;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Time;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /boosts: short potion boosts. Using any boost starts one shared cooldown; money can buy the
 * cooldown away. Each boost in config.yml becomes a menu function {@code boost-<name>}.
 *
 * <p>Only one boost runs at a time ({@code one-at-a-time}): while one is active, another can't be
 * started, not even after buying the cooldown away or with the cooldown bypass. The active boost is
 * saved with the cooldown, so it holds across relogs; drinking milk or dying ends it early.
 */
public final class BoostsFeature extends Feature implements PlayerData.Store {

    /** The boost a player has running: its key in config.yml and when it ends. */
    private record Active(String key, long until) {
    }

    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        db().schema("boosts", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, until BIGINT NOT NULL)");
        db().addColumn("boosts", "boost", "VARCHAR(64) NOT NULL DEFAULT ''");
        db().addColumn("boosts", "boost_until", "BIGINT NOT NULL DEFAULT 0");
        ConfigurationSection boosts = config().getConfigurationSection("boosts");
        if (boosts != null) for (String key : boosts.getKeys(false)) {
            if (effect(boosts.getString(key + ".effect", "")) == null) {
                problems().add("features/boosts/config.yml: boosts." + key + ": unknown effect '" + boosts.getString(key + ".effect") + "'");
            }
            if (Time.seconds(boosts.getString(key + ".duration", "3m")) <= 0) {
                problems().add("features/boosts/config.yml: boosts." + key + ".duration: not a time like 3m or 1h30m");
            }
        }
        if (Time.seconds(config().getString("cooldown", "30m")) < 0) {
            problems().add("features/boosts/config.yml: cooldown: not a time like 30m (0 for none)");
        }
        store(this);
        command("boosts", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null && ready(player)) openMenu(player);
        });
        placeholder("boosts_cooldown", (p, a) -> left(p.getUniqueId()) <= 0
                ? config().getString("status.ready", "Ready") : plugin.messages().time(left(p.getUniqueId())));
        // Placeholders may be read off the player's thread: time only, no look at their effects.
        placeholder("boosts_active", (p, a) -> {
            Active now = running(p.getUniqueId());
            return now == null ? config().getString("status.none", "&7None") : config().getString("boosts." + now.key + ".name", now.key);
        });
        placeholder("boosts_active_time", (p, a) -> {
            Active now = running(p.getUniqueId());
            return now == null ? "" : plugin.messages().time(secondsLeft(now));
        });
    }

    private static PotionEffectType effect(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.MOB_EFFECT.get(key);
    }

    private long left(UUID player) {
        Long until = cooldownUntil.get(player);
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis()) / 1000);
    }

    private static long secondsLeft(Active a) {
        return Math.max(0, (a.until - System.currentTimeMillis() + 999) / 1000);
    }

    /** The saved boost if its time isn't up (effects not checked). */
    private Active running(UUID player) {
        Active a = active.get(player);
        return a == null || a.until <= System.currentTimeMillis() ? null : a;
    }

    /**
     * Player's thread: the boost the player has running, or null. A boost whose effect is gone (milk, death, a
     * plugin clearing effects) or that ran out counts as over.
     */
    private Active current(Player player) {
        Active a = active.get(player.getUniqueId());
        if (a == null) return null;
        ConfigurationSection b = config().getConfigurationSection("boosts." + a.key);
        PotionEffectType type = b == null ? null : effect(b.getString("effect", ""));
        if (a.until <= System.currentTimeMillis() || type == null || !player.hasPotionEffect(type)) {
            active.remove(player.getUniqueId(), a);
            return null;
        }
        return a;
    }

    private String activeName(Player player) {
        Active a = current(player);
        if (a == null) return config().getString("status.none", "&7None");
        return config().getString("boosts." + a.key + ".name", a.key);
    }

    /** Saves the cooldown and the active boost together (the row needs both). */
    private void persist(UUID player) {
        long until = cooldownUntil.getOrDefault(player, 0L);
        Active a = active.get(player);
        String boost = a == null ? "" : a.key;
        long boostUntil = a == null ? 0 : a.until;
        Database db = db();
        db.queue("boost state", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("boosts", new String[]{"uuid"}, "until", "boost", "boost_until"))) {
                ps.setString(1, player.toString());
                ps.setLong(2, until);
                ps.setString(3, boost);
                ps.setLong(4, boostUntil);
                ps.executeUpdate();
            }
        });
    }

    private void activate(Player player, String key) {
        if (!ready(player)) return;
        ConfigurationSection b = config().getConfigurationSection("boosts." + key);
        PotionEffectType type = b == null ? null : effect(b.getString("effect", ""));
        if (type == null) return;
        Active running = current(player);
        if (running != null && config().getBoolean("one-at-a-time", true) && !player.hasPermission("vexcore.boosts.stack")) {
            msg(player, running.key.equals(key) ? "already-running" : "already-active",
                    "boost", config().getString("boosts." + running.key + ".name", running.key),
                    "time", plugin.messages().time(secondsLeft(running)));
            return;
        }
        long left = left(player.getUniqueId());
        if (left > 0 && !player.hasPermission("vexcore.boosts.bypass")) {
            msg(player, "on-cooldown", "time", plugin.messages().time(left));
            return;
        }
        String permission = b.getString("permission", "");
        if (!permission.isEmpty() && !player.hasPermission(permission)) {
            msg(player, "no-permission", "permission", permission);
            return;
        }
        long duration = Math.max(1, Time.seconds(b.getString("duration", "3m")));
        player.addPotionEffect(new PotionEffect(type, (int) Math.min(Integer.MAX_VALUE, duration * 20),
                Math.max(0, b.getInt("amplifier", 0)), false, config().getBoolean("show-particles", true), config().getBoolean("show-icon", true)));
        long now = System.currentTimeMillis();
        active.put(player.getUniqueId(), new Active(key, now + duration * 1000));
        long cooldown = Math.max(0, Time.seconds(config().getString("cooldown", "30m")));
        if (cooldown > 0) cooldownUntil.put(player.getUniqueId(), now + cooldown * 1000);
        persist(player.getUniqueId());
        msg(player, "activated", "boost", b.getString("name", key), "time", plugin.messages().time(duration));
    }

    private void buyReset(Player player) {
        if (!ready(player)) return;
        if (left(player.getUniqueId()) <= 0) {
            msg(player, "no-cooldown");
            return;
        }
        double cost = config().getDouble("reset-cost", 0);
        if (cost > 0 && !plugin.money().withdraw(player, cost)) {
            msg(player, "cannot-afford", "amount", plugin.money().format(cost));
            return;
        }
        cooldownUntil.put(player.getUniqueId(), 0L);
        persist(player.getUniqueId());
        msg(player, "reset", "amount", plugin.money().format(cost));
    }

    private void openMenu(Player player) {
        open(player, "boosts", menu -> {
            long left = left(player.getUniqueId());
            Active running = current(player);
            menu.with("cooldown", left <= 0 ? config().getString("status.ready", "&aReady") : config().getString("status.active", "&c%time%")
                    .replace("%time%", plugin.messages().time(left)));
            menu.with("reset_cost", plugin.money().format(config().getDouble("reset-cost", 0)));
            menu.with("active", activeName(player));
            menu.with("active_time", running == null ? "" : plugin.messages().time(secondsLeft(running)));
            ConfigurationSection boosts = config().getConfigurationSection("boosts");
            if (boosts != null) for (String key : boosts.getKeys(false)) {
                menu.function("boost-" + key.toLowerCase(Locale.ROOT), c -> {
                    activate(player, key);
                    menu.refresh();
                });
            }
            if (left > 0) menu.function("reset-cooldown", c -> {
                buyReset(player);
                menu.refresh();
            });
        });
    }

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT until, boost, boost_until FROM " + db().table("boosts") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    cooldownUntil.put(player, rs.getLong(1));
                    String boost = rs.getString(2);
                    long until = rs.getLong(3);
                    if (boost != null && !boost.isEmpty() && until > System.currentTimeMillis()) active.put(player, new Active(boost, until));
                    else active.remove(player);
                } else {
                    cooldownUntil.remove(player);
                    active.remove(player);
                }
            }
        }
    }

    @Override
    public void unload(UUID player) {
        cooldownUntil.remove(player);
        active.remove(player);
    }
}
