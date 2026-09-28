package com.vexorstudios.vexcore.features.home;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Pos;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import com.vexorstudios.vexcore.gui.Slots;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
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
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Numbered homes. /home opens the menu, /home &lt;n&gt; teleports, /sethome [n], /delhome &lt;n&gt;.
 * How many a player may own: the highest {@code vexcore.home.<n>} they have, else
 * {@code default-homes}. Every change is written to the database at once.
 */
public final class HomeFeature extends Feature implements PlayerData.Store {

    private final Map<UUID, Map<Integer, Pos>> homes = new ConcurrentHashMap<>();

    /** A home's own name and icon (the dialog sets them); null parts mean the defaults. */
    record Meta(String name, String icon) {
    }

    private final Map<UUID, Map<Integer, Meta>> meta = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        db().schema("homes", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, slot INT NOT NULL, "
                + "world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                + "yaw DOUBLE NOT NULL, pitch DOUBLE NOT NULL, PRIMARY KEY (uuid, slot))");
        db().schema("home_meta", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, slot INT NOT NULL, "
                + "name VARCHAR(64) NOT NULL, icon VARCHAR(64) NOT NULL, PRIMARY KEY (uuid, slot))");
        store(this);
        command("home", this::home, (s, a) -> s instanceof Player p && a.length == 1 ? numbers(owned(p)) : List.of());
        command("sethome", this::setHome, (s, a) -> s instanceof Player p && a.length == 1 ? range(allowance(p)) : List.of());
        command("delhome", this::delHome, (s, a) -> s instanceof Player p && a.length == 1 ? numbers(owned(p)) : List.of());
        placeholder("homes", (p, arg) -> String.valueOf(homes.getOrDefault(p.getUniqueId(), Map.of()).size()));
        placeholder("homes_max", (p, arg) -> p.getPlayer() == null ? "0" : String.valueOf(allowance(p.getPlayer())));
    }

    // ── Rules ─────────────────────────────────────────────────────────────

    private int max() {
        return Math.max(1, config().getInt("max-homes", 14));
    }

    /**
     * How many homes the player may own: the highest of default-homes, any vexcore.home.&lt;n&gt;
     * permission and the permission-homes list, never more than max-homes.
     */
    int allowance(Player player) {
        int max = max();
        int best = Math.max(0, config().getInt("default-homes", 3));
        for (int n = max; n > best; n--) {
            if (player.hasPermission("vexcore.home." + n)) {
                best = n;
                break;
            }
        }
        org.bukkit.configuration.ConfigurationSection extra = config().getConfigurationSection("permission-homes");
        if (extra != null) for (String node : extra.getKeys(false)) {
            int n = extra.getInt(node, 0);
            if (n > best && player.hasPermission(node)) best = n;
        }
        return Math.min(max, best);
    }

    private Map<Integer, Pos> owned(Player player) {
        return homes.getOrDefault(player.getUniqueId(), Map.of());
    }

    private static List<String> numbers(Map<Integer, Pos> map) {
        List<String> out = new ArrayList<>();
        for (Integer n : map.keySet()) out.add(String.valueOf(n));
        return out;
    }

    private static List<String> range(int to) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= to; i++) out.add(String.valueOf(i));
        return out;
    }

    private Integer number(CommandSender sender, String raw) {
        try {
            int n = Integer.parseInt(raw);
            if (n >= 1 && n <= max()) return n;
        } catch (NumberFormatException ignored) {
        }
        msg(sender, "invalid-home", "home", raw, "max", max());
        return null;
    }

    private Map<String, Object> placeholders(int n, Pos pos) {
        return placeholders(null, n, pos);
    }

    /** With the player: also %name% (the home's name) and %icon% (its material). */
    private Map<String, Object> placeholders(Player player, int n, Pos pos) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("home", n);
        if (player != null) {
            ph.put("name", homeName(player.getUniqueId(), n));
            ph.put("icon", icon(player.getUniqueId(), n).name());
        }
        if (pos != null) {
            ph.put("world", pos.world());
            ph.put("x", (long) Math.floor(pos.x()));
            ph.put("y", (long) Math.floor(pos.y()));
            ph.put("z", (long) Math.floor(pos.z()));
        }
        return ph;
    }

    // ── Actions (commands and menu share these) ───────────────────────────

    private void teleport(Player player, int n) {
        Pos pos = owned(player).get(n);
        if (pos == null) {
            msg(player, "not-set", "home", n);
            return;
        }
        // A home above what the rank allows now (a rank that ran out) is locked, like in the menu.
        if (n > allowance(player)) {
            msg(player, "locked", "home", n, "allowed", allowance(player));
            return;
        }
        // Homes in a world that was blocked later (or imported ones) can't be used either.
        for (String world : config().getStringList("disabled-worlds")) {
            if (world.equalsIgnoreCase(pos.world())) {
                msg(player, "disabled-world", "world", pos.world());
                return;
            }
        }
        plugin.teleports().start(this, player, () -> {
            Pos now = owned(player).get(n);
            return now == null ? null : now.location();
        }, "vexcore.home.bypass", placeholders(n, pos), null);
    }

    private boolean set(Player player, int n) {
        if (!ready(player)) return false;
        if (n > allowance(player)) {
            msg(player, "locked", "home", n, "allowed", allowance(player));
            return false;
        }
        Location here = player.getLocation();
        for (String world : config().getStringList("disabled-worlds")) {
            if (world.equalsIgnoreCase(here.getWorld().getName())) {
                msg(player, "disabled-world", "world", here.getWorld().getName());
                return false;
            }
        }
        Pos pos = Pos.of(here);
        boolean replaced = homes.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentSkipListMap<>()).put(n, pos) != null;
        Database db = db();
        String uuid = player.getUniqueId().toString();
        db.queue("sethome", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("homes", new String[]{"uuid", "slot"},
                    "world", "x", "y", "z", "yaw", "pitch"))) {
                write(ps, uuid, n, pos);
                ps.executeUpdate();
            }
        });
        msg(player, replaced ? "updated" : "set", placeholders(n, pos));
        return true;
    }

    private boolean delete(Player player, int n) {
        if (!ready(player)) return false;
        Map<Integer, Pos> own = homes.get(player.getUniqueId());
        Map<String, Object> ph = placeholders(player, n, own == null ? null : own.get(n));
        Pos removed = own == null ? null : own.remove(n);
        if (removed == null) {
            msg(player, "not-set", "home", n);
            return false;
        }
        Map<Integer, Meta> mine = meta.get(player.getUniqueId());
        if (mine != null) mine.remove(n); // a new home here starts with the default name and icon
        Database db = db();
        String uuid = player.getUniqueId().toString();
        db.queue("delhome", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("homes") + " WHERE uuid = ? AND slot = ?")) {
                ps.setString(1, uuid);
                ps.setInt(2, n);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("home_meta") + " WHERE uuid = ? AND slot = ?")) {
                ps.setString(1, uuid);
                ps.setInt(2, n);
                ps.executeUpdate();
            }
        });
        ph.putAll(placeholders(n, removed));
        msg(player, "deleted", ph);
        return true;
    }

    private static void write(PreparedStatement ps, String uuid, int n, Pos pos) throws SQLException {
        ps.setString(1, uuid);
        ps.setInt(2, n);
        ps.setString(3, pos.world());
        ps.setDouble(4, pos.x());
        ps.setDouble(5, pos.y());
        ps.setDouble(6, pos.z());
        ps.setDouble(7, pos.yaw());
        ps.setDouble(8, pos.pitch());
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void home(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        if (args.length == 0) {
            if (menu("homes") != null) openHomes(player);
            else list(player);
            return;
        }
        Integer n = number(player, args[0]);
        if (n != null) teleport(player, n);
    }

    private void list(Player player) {
        Map<Integer, Pos> own = owned(player);
        if (own.isEmpty()) msg(player, "none");
        else msg(player, "list", "homes", String.join(", ", numbers(own)), "amount", own.size(), "allowed", allowance(player));
    }

    private void setHome(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null || !ready(player)) return;
        Integer n;
        if (args.length > 0) {
            n = number(player, args[0]);
            if (n == null) return;
        } else {
            n = null;
            Map<Integer, Pos> own = owned(player);
            for (int i = 1; i <= allowance(player); i++) {
                if (!own.containsKey(i)) {
                    n = i;
                    break;
                }
            }
            if (n == null) {
                msg(player, "limit-reached", "allowed", allowance(player));
                return;
            }
        }
        set(player, n);
    }

    private void delHome(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        if (args.length == 0) {
            usage(player, "delhome");
            return;
        }
        Integer n = number(player, args[0]);
        if (n != null) delete(player, n);
    }

    // ── Menus ─────────────────────────────────────────────────────────────

    /** The page each player last looked at, so going back (dialogs, confirm) lands there again. */
    private final Map<UUID, Integer> lastPage = new ConcurrentHashMap<>();

    private void openHomes(Player player) {
        open(player, "homes", menu -> {
            List<Integer> beds = Slots.parse(menu.file().yml().get("home-slots"));
            List<Integer> dyes = Slots.parse(menu.file().yml().get("delete-slots"));
            int allowed = allowance(player);
            Map<Integer, Pos> own = owned(player);
            // One page holds as many homes as the longer slot list; locked homes can be left out.
            int perPage = Math.max(1, Math.max(beds.size(), dyes.size()));
            int shown = config().getBoolean("show-locked-homes", true) ? max() : Math.max(1, Math.min(max(), allowed));
            int pages = Math.max(1, (shown + perPage - 1) / perPage);
            int page = Math.max(0, Math.min(pages - 1, lastPage.getOrDefault(player.getUniqueId(), 0)));
            lastPage.put(player.getUniqueId(), page);
            menu.with("homes", own.size()).with("allowed", allowed).with("max", max())
                    .with("page", page + 1).with("pages", pages)
                    .with("previous_page", Math.max(1, page)).with("next_page", Math.min(pages, page + 2));
            if (page > 0) menu.function("previous-page", c -> turn(player, menu, -1));
            if (page < pages - 1) menu.function("next-page", c -> turn(player, menu, 1));
            for (int i = page * perPage; i < Math.min(shown, (page + 1) * perPage); i++) {
                int n = i + 1;
                int at = i - page * perPage;
                Pos pos = own.get(n);
                String state = n > allowed ? "locked" : pos != null ? "set" : "empty";
                Map<String, Object> ph = placeholders(player, n, pos);
                if (at < beds.size()) menu.place("home-" + state, beds.get(at), ph, click -> {
                    if (state.equals("locked")) msg(player, "locked", "home", n, "allowed", allowed);
                    else if (state.equals("set")) {
                        player.closeInventory();
                        // Right click: the home's dialog (teleport, icon, rename, delete).
                        if (click.type().isRightClick() && HomeDialogs.available()) dialogs.home(player, n);
                        else teleport(player, n);
                    } else if (set(player, n)) menu.refresh();
                });
                if (at < dyes.size()) menu.place("delete-" + state, dyes.get(at), ph, click -> {
                    if (state.equals("locked")) msg(player, "locked", "home", n, "allowed", allowed);
                    else if (state.equals("set")) {
                        if (config().getBoolean("confirm-delete", true) && menu("confirm-delete") != null) confirm(player, n);
                        else if (delete(player, n)) menu.refresh();
                    } else if (set(player, n)) menu.refresh();
                });
            }
        });
    }

    private void turn(Player player, com.vexorstudios.vexcore.gui.Menu menu, int by) {
        lastPage.merge(player.getUniqueId(), by, Integer::sum);
        menu.sound("page");
        menu.refresh();
    }

    private void confirm(Player player, int n) {
        Pos pos = owned(player).get(n);
        open(player, "confirm-delete", menu -> {
            placeholders(n, pos).forEach(menu::with);
            menu.function("confirm", click -> {
                delete(player, n);
                openHomes(player);
            });
            menu.function("cancel", click -> openHomes(player));
        });
    }

    // ── Names and icons ───────────────────────────────────────────────────

    private final HomeDialogs dialogs = new HomeDialogs(this);

    String homeName(UUID player, int n) {
        Meta m = meta.getOrDefault(player, Map.of()).get(n);
        return m != null && !m.name().isEmpty() ? m.name() : config().getString("default-name", "Home %home%").replace("%home%", String.valueOf(n));
    }

    org.bukkit.Material icon(UUID player, int n) {
        Meta m = meta.getOrDefault(player, Map.of()).get(n);
        org.bukkit.Material icon = m == null || m.icon().isEmpty() ? null : org.bukkit.Material.matchMaterial(m.icon());
        if (icon == null || !icon.isItem() || icon.isAir()) icon = org.bukkit.Material.matchMaterial(config().getString("default-icon", "LIME_BED"));
        return icon == null || !icon.isItem() ? org.bukkit.Material.LIME_BED : icon;
    }

    /** Saves a home's name or icon; null keeps what it has, "" goes back to the default. */
    void setMeta(Player player, int n, String name, String icon) {
        Map<Integer, Meta> mine = meta.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        Meta old = mine.getOrDefault(n, new Meta("", ""));
        Meta now = new Meta(name == null ? old.name() : name, icon == null ? old.icon() : icon);
        mine.put(n, now);
        Database db = db();
        String uuid = player.getUniqueId().toString();
        db.queue("home meta", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("home_meta", new String[]{"uuid", "slot"}, "name", "icon"))) {
                ps.setString(1, uuid);
                ps.setInt(2, n);
                ps.setString(3, now.name());
                ps.setString(4, now.icon());
                ps.executeUpdate();
            }
        });
    }

    // Used by HomeDialogs.
    Map<Integer, Pos> homesOf(Player player) {
        return owned(player);
    }

    void teleportTo(Player player, int n) {
        teleport(player, n);
    }

    boolean deleteHome(Player player, int n) {
        return delete(player, n);
    }

    void openMenu(Player player) {
        openHomes(player);
    }

    Map<String, Object> placeholdersOf(Player player, int n) {
        return placeholders(player, n, owned(player).get(n));
    }

    // ── Player data ───────────────────────────────────────────────────────

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Map<Integer, Pos> own = new ConcurrentSkipListMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT slot, world, x, y, z, yaw, pitch FROM "
                + db().table("homes") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    own.put(rs.getInt(1), new Pos(rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getDouble(5), (float) rs.getDouble(6), (float) rs.getDouble(7)));
                }
            }
        }
        homes.put(player, own);
        Map<Integer, Meta> names = new ConcurrentHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT slot, name, icon FROM " + db().table("home_meta") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.put(rs.getInt(1), new Meta(rs.getString(2), rs.getString(3)));
            }
        }
        meta.put(player, names);
    }

    @Override
    public void unload(UUID player) {
        homes.remove(player);
        meta.remove(player);
        lastPage.remove(player);
    }

    // ── SetupCore import ──────────────────────────────────────────────────

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        Connection old = source.main();
        if (!SetupCoreImport.Source.has(old, "home_locations") || !SetupCoreImport.Source.has(old, "home_set")) return;
        int count = 0;
        Database db = db();
        try (Statement st = old.createStatement();
             ResultSet rs = st.executeQuery("SELECT l.uuid, l.home_number, l.world, l.x, l.y, l.z, l.yaw, l.pitch "
                     + "FROM home_locations l JOIN home_set s ON s.uuid = l.uuid AND s.home_number = l.home_number "
                     + "WHERE s.value = 1 AND l.world IS NOT NULL");
             PreparedStatement ps = target.prepareStatement(db.upsert("homes", new String[]{"uuid", "slot"},
                     "world", "x", "y", "z", "yaw", "pitch"))) {
            while (rs.next()) {
                write(ps, rs.getString(1).toLowerCase(Locale.ROOT), rs.getInt(2),
                        new Pos(rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getFloat(7), rs.getFloat(8)));
                ps.addBatch();
                if (++count % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        report.add(count, "homes");
    }
}
