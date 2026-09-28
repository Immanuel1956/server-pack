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
 */
public final class BoostsFeature extends Feature implements PlayerData.Store {

    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        db().schema("boosts", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, until BIGINT NOT NULL)");
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
    }

    private static PotionEffectType effect(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.MOB_EFFECT.get(key);
    }

    private long left(UUID player) {
        Long until = cooldownUntil.get(player);
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis()) / 1000);
    }

    private void setCooldown(UUID player, long until) {
        cooldownUntil.put(player, until);
        Database db = db();
        db.queue("boost cooldown", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("boosts", new String[]{"uuid"}, "until"))) {
                ps.setString(1, player.toString());
                ps.setLong(2, until);
                ps.executeUpdate();
            }
        });
    }

    private void activate(Player player, String key) {
        if (!ready(player)) return;
        ConfigurationSection b = config().getConfigurationSection("boosts." + key);
        PotionEffectType type = b == null ? null : effect(b.getString("effect", ""));
        if (type == null) return;
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
        long cooldown = Math.max(0, Time.seconds(config().getString("cooldown", "30m")));
        if (cooldown > 0) setCooldown(player.getUniqueId(), System.currentTimeMillis() + cooldown * 1000);
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
        setCooldown(player.getUniqueId(), 0);
        msg(player, "reset", "amount", plugin.money().format(cost));
    }

    private void openMenu(Player player) {
        open(player, "boosts", menu -> {
            long left = left(player.getUniqueId());
            menu.with("cooldown", left <= 0 ? config().getString("status.ready", "&aReady") : config().getString("status.active", "&c%time%")
                    .replace("%time%", plugin.messages().time(left)));
            menu.with("reset_cost", plugin.money().format(config().getDouble("reset-cost", 0)));
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
        try (PreparedStatement ps = c.prepareStatement("SELECT until FROM " + db().table("boosts") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) cooldownUntil.put(player, rs.getLong(1));
                else cooldownUntil.remove(player);
            }
        }
    }

    @Override
    public void unload(UUID player) {
        cooldownUntil.remove(player);
    }
}
