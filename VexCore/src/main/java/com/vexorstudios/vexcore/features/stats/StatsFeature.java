package com.vexorstudios.vexcore.features.stats;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player stats: kills, deaths, kill streak (and best), mob kills, blocks broken and placed, duel
 * wins and losses, FFA kills. /stats [player]. Placeholders %vexcore_stats_&lt;stat&gt;% and
 * %vexcore_stats_kdr%. Saved every save-seconds and on quit.
 */
public final class StatsFeature extends Feature implements PlayerData.Store, Listener {

    private static final Map<String, Stat> BY_NAME = new java.util.HashMap<>();

    static {
        for (Stat s : Stat.values()) BY_NAME.put(s.column(), s);
    }

    public enum Stat {KILLS, DEATHS, STREAK, BEST_STREAK, MOB_KILLS, BLOCKS_BROKEN, BLOCKS_PLACED, DUEL_WINS, DUEL_LOSSES, FFA_KILLS;

        public String column() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final class Row {
        final long[] values = new long[Stat.values().length];
        String name;
        boolean dirty;
    }

    private final Map<UUID, Row> rows = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        StringBuilder columns = new StringBuilder();
        for (Stat s : Stat.values()) columns.append(", ").append(s.column()).append(" BIGINT NOT NULL DEFAULT 0");
        db().schema("stats", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL" + columns + ")");
        store(this);
        listen(this);
        every(Math.max(5, config().getInt("save-seconds", 30)) * 20L, this::flush);
        command("stats", this::command, (s, a) -> a.length == 1 ? null : java.util.List.of());
        placeholder("stats", (p, key) -> {
            if (key.equalsIgnoreCase("kdr")) {
                Row r = rows.get(p.getUniqueId());
                return r == null ? "0.00" : kdr(r);
            }
            // stats_kills: 1.5k (numbers: in config.yml); stats_kills_raw: 1500.
            boolean raw = key.toLowerCase(Locale.ROOT).endsWith("_raw");
            Stat stat = BY_NAME.get((raw ? key.substring(0, key.length() - 4) : key).toLowerCase(Locale.ROOT)); // not Stat.valueOf: it throws for unknown names
            if (stat == null) return null;
            Row r = rows.get(p.getUniqueId());
            return count(r == null ? 0 : r.values[stat.ordinal()], raw ? "raw" : "");
        });
    }

    @Override
    protected void disable() {
        flush();
    }

    private static String kdr(Row r) {
        long d = r.values[Stat.DEATHS.ordinal()];
        double v = d == 0 ? r.values[Stat.KILLS.ordinal()] : (double) r.values[Stat.KILLS.ordinal()] / d;
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Adds to a stat of an online, loaded player (does nothing otherwise). */
    public void add(UUID player, Stat stat, long amount) {
        Row r = rows.get(player);
        if (r == null) return;
        synchronized (r) {
            long[] v = r.values;
            v[stat.ordinal()] += amount;
            if (stat == Stat.STREAK && v[Stat.STREAK.ordinal()] > v[Stat.BEST_STREAK.ordinal()]) v[Stat.BEST_STREAK.ordinal()] = v[Stat.STREAK.ordinal()];
            r.dirty = true;
        }
    }

    public long get(UUID player, Stat stat) {
        Row r = rows.get(player);
        return r == null ? 0 : r.values[stat.ordinal()];
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        add(dead.getUniqueId(), Stat.DEATHS, 1);
        Row r = rows.get(dead.getUniqueId());
        if (r != null) synchronized (r) {
            r.values[Stat.STREAK.ordinal()] = 0;
            r.dirty = true;
        }
        Player killer = dead.getKiller();
        if (killer != null && !killer.equals(dead) && !com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.voidedKill(dead)) {
            add(killer.getUniqueId(), Stat.KILLS, 1);
            add(killer.getUniqueId(), Stat.STREAK, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) return;
        Player killer = event.getEntity().getKiller();
        if (killer != null) add(killer.getUniqueId(), Stat.MOB_KILLS, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        add(event.getPlayer().getUniqueId(), Stat.BLOCKS_BROKEN, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        add(event.getPlayer().getUniqueId(), Stat.BLOCKS_PLACED, 1);
    }

    private void command(CommandSender sender, String label, String[] args) {
        OfflinePlayer target;
        if (args.length > 0) {
            if (!sender.hasPermission("vexcore.stats.others")) {
                msg(sender, "no-permission", "permission", "vexcore.stats.others");
                return;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null) {
                msg(sender, "unknown-player", "player", args[0]);
                return;
            }
        } else {
            Player p = player(sender);
            if (p == null) return;
            target = p;
        }
        Row loaded = rows.get(target.getUniqueId());
        String name = target.getName() == null ? args[0] : target.getName();
        if (loaded != null) {
            show(sender, name, loaded);
            return;
        }
        UUID id = target.getUniqueId();
        db().query("stats", c -> read(c, id)).thenAccept(r -> Scheduler.global(() -> show(sender, name, r)));
    }

    private void show(CommandSender sender, String name, Row r) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("player", name);
        for (Stat s : Stat.values()) ph.put(s.column(), com.vexorstudios.vexcore.core.Numbers.format(r.values[s.ordinal()]));
        ph.put("kdr", kdr(r));
        msg(sender, "stats", ph);
    }

    private Row read(Connection c, UUID player) throws SQLException {
        Row r = new Row();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + db().table("stats") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) for (Stat s : Stat.values()) r.values[s.ordinal()] = rs.getLong(s.column());
            }
        }
        return r;
    }

    private Database.Work write(UUID player, Row r) {
        long[] v;
        String name;
        synchronized (r) {
            if (!r.dirty) return null;
            r.dirty = false;
            v = r.values.clone();
            name = r.name;
        }
        Database db = db();
        String[] cols = new String[Stat.values().length + 1];
        cols[0] = "name";
        for (Stat s : Stat.values()) cols[s.ordinal() + 1] = s.column();
        return c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("stats", new String[]{"uuid"}, cols))) {
                ps.setString(1, player.toString());
                ps.setString(2, name == null ? "" : name);
                for (int i = 0; i < v.length; i++) ps.setLong(i + 3, v[i]);
                ps.executeUpdate();
            }
        };
    }

    private void flush() {
        for (Map.Entry<UUID, Row> e : rows.entrySet()) {
            Database.Work w = write(e.getKey(), e.getValue());
            if (w != null) db().queue("stats", w);
        }
    }

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Row r = read(c, player);
        Player online = Bukkit.getPlayer(player);
        r.name = online == null ? "" : online.getName();
        r.dirty = true;
        rows.put(player, r);
    }

    @Override
    public Database.Work save(UUID player) {
        Row r = rows.get(player);
        return r == null ? null : write(player, r);
    }

    @Override
    public void unload(UUID player) {
        rows.remove(player);
    }
}
