package com.vexorstudios.vexcore.features.prestige;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import com.vexorstudios.vexcore.gui.Actions;
import com.vexorstudios.vexcore.gui.Slots;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prestige levels, taken one after another. Each level can ask for money (taken), player kills
 * and hours played (totals, not taken), and gives commands plus a permanent raise of the
 * invest limit. The level is written the moment it is reached.
 */
public final class PrestigeFeature extends Feature implements PlayerData.Store {

    record Level(String key, String material, double cost, int kills, double hours, double investLimit,
                 List<String> commands, List<String> display) {
    }

    private final Map<UUID, Integer> levels = new ConcurrentHashMap<>();
    private List<Level> defined = List.of();

    @Override
    protected void enable() {
        List<Level> list = new ArrayList<>();
        ConfigurationSection root = config().getConfigurationSection("levels");
        if (root != null) for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            list.add(new Level(key, s.getString("material", "NETHER_STAR"), Math.max(0, s.getDouble("cost", 0)),
                    Math.max(0, s.getInt("kills", 0)), Math.max(0, s.getDouble("playtime-hours", 0)), Math.max(0, s.getDouble("invest-limit", 0)),
                    ownList(s, "commands"), ownList(s, "display")));
        }
        defined = List.copyOf(list);
        if (menu("prestige") != null) {
            int slots = Slots.parse(menu("prestige").yml().get("level-slots")).size();
            if (defined.size() > slots) problems().add("features/prestige/gui/prestige.yml: " + defined.size()
                    + " levels but only " + slots + " level-slots; the rest can't be seen or taken");
        }
        db().schema("prestige", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, level INT NOT NULL)");
        store(this);
        command("prestige", this::command, (s, a) -> !s.hasPermission("vexcore.prestige.admin") ? List.of() : a.length == 1 ? List.of("set") : a.length == 2 && a[0].equalsIgnoreCase("set") ? playerNames(s) : List.of());
        placeholder("prestige", (p, a) -> String.valueOf(levels.getOrDefault(p.getUniqueId(), 0)));
        placeholder("prestige_max", (p, a) -> String.valueOf(defined.size()));
    }

    public int level(UUID player) {
        return levels.getOrDefault(player, 0);
    }

    /** The invest limit every reached level added. */
    public double investBonus(UUID player) {
        int level = level(player);
        double bonus = 0;
        for (int i = 0; i < Math.min(level, defined.size()); i++) bonus += defined.get(i).investLimit;
        return bonus;
    }

    private static long kills(Player p) {
        return p.getStatistic(Statistic.PLAYER_KILLS);
    }

    private static double hours(Player p) {
        return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20.0 / 3600.0;
    }

    private boolean meets(Player p, Level l) {
        return kills(p) >= l.kills && hours(p) >= l.hours && plugin.money().has(p, l.cost);
    }

    private Map<String, Object> placeholders(Player p, Level l, int number) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("number", number);
        ph.put("cost", plugin.money().format(l.cost));
        ph.put("kills", com.vexorstudios.vexcore.core.Numbers.format(l.kills));
        ph.put("kills_raw", l.kills); // for commands
        ph.put("playtime", trim(l.hours));
        ph.put("kills_have", com.vexorstudios.vexcore.core.Numbers.format(kills(p)));
        ph.put("playtime_have", trim(Math.floor(hours(p) * 10) / 10));
        ph.put("invest_limit", plugin.money().format(l.investLimit));
        ph.put("material", l.material);
        List<String> display = l.display.isEmpty() ? List.of(config().getString("no-rewards", "&f- &7none")) : l.display;
        ph.put("rewards", String.join("\n", display));
        return ph;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void command(CommandSender sender, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("set")) {
            setCommand(sender, args);
            return;
        }
        Player player = player(sender);
        if (player == null) return;
        if (menu("prestige") == null) prestige(player);
        else openMenu(player);
    }

    private void prestige(Player player) {
        if (!ready(player)) return;
        int current = level(player.getUniqueId());
        if (current >= defined.size()) {
            msg(player, "max");
            return;
        }
        Level next = defined.get(current);
        if (!meets(player, next)) {
            msg(player, "not-ready", placeholders(player, next, current + 1));
            return;
        }
        if (next.cost > 0 && !plugin.money().withdraw(player, next.cost)) {
            msg(player, "not-ready", placeholders(player, next, current + 1));
            return;
        }
        if (!levels.replace(player.getUniqueId(), current, current + 1)) { // clicked twice at once
            if (next.cost > 0) plugin.money().deposit(player, next.cost);
            return;
        }
        write(player.getUniqueId(), current + 1);
        Map<String, Object> ph = placeholders(player, next, current + 1);
        ph.put("player", player.getName());
        ph.put("level", current + 1);
        Actions.run(player, next.commands, ph, null);
        msg(player, "prestiged", ph);
        if (config().getBoolean("broadcast", true)) {
            broadcast(Bukkit.getOnlinePlayers(), "broadcast", ph);
        }
    }

    private void write(UUID player, int level) {
        Database db = db();
        db.queue("prestige", c -> write(db, c, player.toString(), level));
    }

    private static void write(Database db, Connection c, String uuid, int level) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.upsert("prestige", new String[]{"uuid"}, "level"))) {
            ps.setString(1, uuid);
            ps.setInt(2, level);
            ps.executeUpdate();
        }
    }

    private void setCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vexcore.prestige.admin")) {
            msg(sender, "no-permission", "permission", "vexcore.prestige.admin");
            return;
        }
        if (args.length < 3) {
            msg(sender, "usage-set");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        int level;
        try {
            level = Math.max(0, Math.min(defined.size(), Integer.parseInt(args[2])));
        } catch (NumberFormatException e) {
            msg(sender, "usage-set");
            return;
        }
        if (target == null) {
            msg(sender, "unknown-player", "player", args[1]);
            return;
        }
        if (target.isOnline() && !levels.containsKey(target.getUniqueId())) {
            msg(sender, "data-loading");
            return;
        }
        levels.computeIfPresent(target.getUniqueId(), (k, v) -> level);
        write(target.getUniqueId(), level);
        msg(sender, "set", "player", target.getName(), "level", level);
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    private void openMenu(Player player) {
        open(player, "prestige", menu -> {
            int current = level(player.getUniqueId());
            List<Integer> slots = Slots.parse(menu.file().yml().get("level-slots"));
            menu.with("level", current).with("total", defined.size())
                    .with("kills_have", kills(player)).with("playtime_have", trim(Math.floor(hours(player) * 10) / 10))
                    .with("next", current < defined.size() ? String.valueOf(current + 1) : config().getString("no-next", "-"));
            for (int i = 0; i < Math.min(slots.size(), defined.size()); i++) {
                Level l = defined.get(i);
                String state = i < current ? "done" : i == current && meets(player, l) ? "ready" : "locked";
                boolean next = i == current;
                menu.place(state, slots.get(i), placeholders(player, l, i + 1), c -> {
                    if (next) {
                        prestige(player);
                        menu.refresh();
                    } else if (!state.equals("done")) {
                        msg(player, "not-next");
                    }
                });
            }
        });
    }

    // ── Player data ───────────────────────────────────────────────────────

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        int level = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT level FROM " + db().table("prestige") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) level = rs.getInt(1);
            }
        }
        levels.put(player, level);
    }

    @Override
    public void unload(UUID player) {
        levels.remove(player);
    }

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        if (!SetupCoreImport.Source.has(source.main(), "player_prestige")) return;
        int count = 0;
        try (Statement st = source.main().createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, prestige_level FROM player_prestige WHERE prestige_level > 0")) {
            while (rs.next()) {
                write(db(), target, rs.getString(1).toLowerCase(Locale.ROOT), Math.min(defined.size(), rs.getInt(2)));
                count++;
            }
        }
        report.add(count, "prestige levels");
    }
}
