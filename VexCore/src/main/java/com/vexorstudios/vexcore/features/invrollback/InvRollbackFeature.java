package com.vexorstudios.vexcore.features.invrollback;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /invrollback &lt;player&gt;: inventory backups, taken
 * <ul>
 *   <li>AUTO: every few minutes while online (skipped when nothing changed),</li>
 *   <li>JOINS: when the player joins,</li>
 *   <li>DEATHS: when the player dies (before the items drop),</li>
 *   <li>QUITS: when the player leaves.</li>
 * </ul>
 * The menu "Rollback | &lt;player&gt;" shows the four kinds with how many are saved. A kind lists its
 * backups newest first: left click restores it onto the (online) player, right click previews it.
 */
public final class InvRollbackFeature extends Feature implements Listener {

    public enum Trigger {
        AUTO("auto"), JOIN("joins"), DEATH("deaths"), QUIT("quits");

        final String key;

        Trigger(String key) {
            this.key = key;
        }

        static Trigger of(String raw) {
            for (Trigger t : values()) if (t.name().equalsIgnoreCase(raw) || t.key.equalsIgnoreCase(raw)) return t;
            return null;
        }
    }

    /** A backup without its items (the list menu). */
    record Backup(long id, UUID uuid, String name, Trigger trigger, long time, int count, int level,
                  String world, int x, int y, int z, String note) {
    }

    /** Last auto backup per player, to skip saving the same inventory again. */
    private final Map<UUID, Integer> lastAuto = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        listen(this);
        boolean mysql = db().type() == Database.Type.MYSQL;
        db().schema("inv_backups", "CREATE TABLE IF NOT EXISTS {t} (id BIGINT NOT NULL PRIMARY KEY, uuid VARCHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL, cause VARCHAR(16) NOT NULL, time BIGINT NOT NULL, items "
                + (mysql ? "MEDIUMTEXT" : "TEXT") + " NOT NULL, count INT NOT NULL, level INT NOT NULL, "
                + "world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, note VARCHAR(255) NOT NULL)");
        db().index("inv_backups", "uuid, cause");
        db().index("inv_backups", "name");

        long minutes = Math.max(1, config().getLong("triggers.auto.interval-minutes", 5));
        if (enabled(Trigger.AUTO)) every(minutes * 60 * 20, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) Scheduler.entity(p, () -> capture(p, Trigger.AUTO, ""));
        });

        command("invrollback", this::command, (sender, args) -> args.length == 1 ? null : List.of());
    }

    @Override
    protected void prepareShutdown() {
        // The server is stopping: quit events come after plugins are gone, so save them now.
        if (plugin.isEnabled() || !enabled(Trigger.QUIT)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                capture(p, Trigger.QUIT, "");
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void disable() {
        lastAuto.clear();
    }

    private boolean enabled(Trigger t) {
        return config().getBoolean("triggers." + t.key + ".enabled", true);
    }

    private int keep(Trigger t) {
        return Math.max(1, Math.min(500, config().getInt("triggers." + t.key + ".keep", 45)));
    }

    // ── Taking backups ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (enabled(Trigger.JOIN)) capture(event.getPlayer(), Trigger.JOIN, "");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (enabled(Trigger.QUIT)) capture(event.getPlayer(), Trigger.QUIT, "");
        lastAuto.remove(event.getPlayer().getUniqueId());
    }

    /** The inventory is still whole here; the drops are made from it afterwards. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled(Trigger.DEATH)) return;
        net.kyori.adventure.text.Component message = event.deathMessage();
        capture(event.getEntity(), Trigger.DEATH, message == null ? "" : Text.plain(message));
    }

    /** Saves the player's inventory now (their thread). */
    void capture(Player p, Trigger trigger, String note) {
        if (!p.isOnline() && trigger != Trigger.QUIT) return;
        ItemStack[] contents = p.getInventory().getContents();
        List<ItemStack> items = new ArrayList<>(contents.length);
        int count = 0;
        for (ItemStack item : contents) {
            if (item == null || item.isEmpty()) {
                items.add(ItemStack.empty());
            } else {
                items.add(item.clone());
                count++;
            }
        }
        if (count == 0 && config().getBoolean("skip-empty", true)) return;
        byte[] bytes = ItemStack.serializeItemsAsBytes(items);
        if (trigger == Trigger.AUTO && config().getBoolean("triggers.auto.skip-unchanged", true)) {
            Integer hash = Arrays.hashCode(bytes);
            if (hash.equals(lastAuto.put(p.getUniqueId(), hash))) return;
        }
        Location l = p.getLocation();
        String world = l.getWorld() == null ? "-" : l.getWorld().getName();
        long id = System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
        long time = System.currentTimeMillis();
        String data = Base64.getEncoder().encodeToString(bytes);
        UUID uuid = p.getUniqueId();
        String name = p.getName();
        int level = p.getLevel();
        int finalCount = count;
        String cleanNote = clean(note, 250);
        int keep = keep(trigger);
        String table = db().table("inv_backups");
        db().queue("inventory backup", c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                    + " (id, uuid, name, cause, time, items, count, level, world, x, y, z, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, id);
                ps.setString(2, uuid.toString());
                ps.setString(3, name);
                ps.setString(4, trigger.name());
                ps.setLong(5, time);
                ps.setString(6, data);
                ps.setInt(7, finalCount);
                ps.setInt(8, level);
                ps.setString(9, world);
                ps.setInt(10, l.getBlockX());
                ps.setInt(11, l.getBlockY());
                ps.setInt(12, l.getBlockZ());
                ps.setString(13, cleanNote);
                ps.executeUpdate();
            }
            // Only the newest `keep` of this kind stay.
            List<Long> old = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + table + " WHERE uuid = ? AND cause = ? ORDER BY time DESC")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, trigger.name());
                try (ResultSet rs = ps.executeQuery()) {
                    int n = 0;
                    while (rs.next()) if (n++ >= keep) old.add(rs.getLong(1));
                }
            }
            if (!old.isEmpty()) try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
                for (long o : old) {
                    ps.setLong(1, o);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    private static String clean(String raw, int max) {
        if (raw == null) return "";
        String s = raw.replace("&", "").replace("§", "").replace("<", "").replace(">", "").replace("%", "")
                .replaceAll("\\s+", " ").strip();
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ── /invrollback <player> ─────────────────────────────────────────────

    private void command(CommandSender sender, String label, String[] args) {
        Player staff = player(sender);
        if (staff == null) return;
        if (args.length == 0) {
            usage(staff, "invrollback");
            return;
        }
        String name = args[0];
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            openMain(staff, online.getUniqueId(), online.getName());
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null) {
            openMain(staff, cached.getUniqueId(), cached.getName());
            return;
        }
        // Not known to the server any more: the backups still know the name.
        String table = db().table("inv_backups");
        db().query("rollback find", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name FROM " + table + " WHERE LOWER(name) = ? ORDER BY time DESC")) {
                ps.setString(1, name.toLowerCase(Locale.ROOT));
                ps.setMaxRows(1);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null;
                }
            }
        }).whenComplete((found, error) -> onViewer(staff, () -> {
            if (found == null) msg(staff, "no-backups", "target", name);
            else openMain(staff, UUID.fromString(found[0]), found[1]);
        }));
    }

    private void onViewer(Player viewer, Runnable run) {
        if (!plugin.isEnabled()) return;
        Scheduler.entity(viewer, () -> {
            if (isEnabled() && viewer.isOnline()) run.run();
        });
    }

    // ── Menus ─────────────────────────────────────────────────────────────

    void openMain(Player staff, UUID target, String targetName) {
        String table = db().table("inv_backups");
        db().query("rollback counts", c -> {
            Map<Trigger, Integer> counts = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT cause, COUNT(*) FROM " + table + " WHERE uuid = ? GROUP BY cause")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Trigger t = Trigger.of(rs.getString(1));
                        if (t != null) counts.put(t, rs.getInt(2));
                    }
                }
            }
            return counts;
        }).whenComplete((counts, error) -> onViewer(staff, () -> {
            if (counts == null) {
                msg(staff, "failed");
                return;
            }
            open(staff, "main", menu -> {
                menu.with("target", targetName);
                for (Trigger t : Trigger.values()) {
                    menu.with(t.key, counts.getOrDefault(t, 0));
                    menu.with(t.key + "_interval", config().getLong("triggers.auto.interval-minutes", 5));
                    menu.function(t.key, click -> openList(click.player(), target, targetName, t));
                }
            });
        }));
    }

    void openList(Player staff, UUID target, String targetName, Trigger trigger) {
        String table = db().table("inv_backups");
        db().query("rollback list", c -> {
            List<Backup> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name, time, count, level, world, x, y, z, note FROM "
                    + table + " WHERE uuid = ? AND cause = ? ORDER BY time DESC")) {
                ps.setString(1, target.toString());
                ps.setString(2, trigger.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Backup(rs.getLong(1), target, rs.getString(2), trigger, rs.getLong(3),
                            rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(10)));
                }
            }
            return out;
        }).whenComplete((list, error) -> onViewer(staff, () -> {
            if (list == null) {
                msg(staff, "failed");
                return;
            }
            open(staff, "list", menu -> {
                menu.with("target", targetName);
                trigger(menu, trigger);
                menu.with("count", list.size());
                menu.function("back", click -> openMain(click.player(), target, targetName));
                if (list.isEmpty()) menu.function("empty", click -> {
                });
                menu.paginate(list, (b, slot) -> menu.place("backup", slot, placeholders(b), click -> {
                    if (click.type().isRightClick()) openPreview(click.player(), b, targetName);
                    else restore(click.player(), b, targetName);
                }));
            });
        }));
    }

    private void openPreview(Player staff, Backup b, String targetName) {
        load(b.id, items -> open(staff, "preview", menu -> {
            menu.with("target", targetName);
            trigger(menu, b.trigger);
            placeholders(b).forEach(menu::with);
            ConfigurationSection layout = menu.file().yml().getConfigurationSection("layout");
            // Player inventory index -> menu slot. 0-8 hotbar, 9-35 storage, 36-39 boots..helmet, 40 offhand.
            int[] map = layout(layout);
            for (int i = 0; i < items.length && i < map.length; i++) {
                ItemStack item = items[i];
                if (item != null && !item.isEmpty() && map[i] >= 0) menu.set(map[i], item.clone(), click -> {
                });
            }
            menu.function("back", click -> openList(click.player(), b.uuid, targetName, b.trigger));
            menu.function("restore", click -> restore(click.player(), b, targetName));
        }), staff);
    }

    /** Where each inventory index is drawn in the preview menu (see layout in gui/preview.yml). */
    private static int[] layout(ConfigurationSection s) {
        int[] map = new int[41];
        Arrays.fill(map, -1);
        int storage = s == null ? 0 : s.getInt("storage-start", 0);   // 27 slots of storage
        int hotbar = s == null ? 27 : s.getInt("hotbar-start", 27);   // 9 slots
        for (int i = 0; i < 27; i++) map[9 + i] = storage + i;
        for (int i = 0; i < 9; i++) map[i] = hotbar + i;
        map[39] = s == null ? 36 : s.getInt("helmet", 36);
        map[38] = s == null ? 37 : s.getInt("chestplate", 37);
        map[37] = s == null ? 38 : s.getInt("leggings", 38);
        map[36] = s == null ? 39 : s.getInt("boots", 39);
        map[40] = s == null ? 40 : s.getInt("offhand", 40);
        return map;
    }

    private void trigger(Menu menu, Trigger t) {
        menu.with("trigger", config().getString("triggers." + t.key + ".name", t.key));
        menu.with("color", config().getString("triggers." + t.key + ".color", "&#FF0000"));
    }

    /** Reads a backup's items, then runs {@code then} on the viewer's thread. */
    private void load(long id, java.util.function.Consumer<ItemStack[]> then, Player viewer) {
        String table = db().table("inv_backups");
        db().query("rollback items", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT items FROM " + table + " WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }).whenComplete((data, error) -> onViewer(viewer, () -> {
            if (data == null) {
                msg(viewer, "gone");
                return;
            }
            ItemStack[] items;
            try {
                items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data));
            } catch (RuntimeException bad) {
                plugin.getLogger().warning("Inventory backup " + id + " could not be read: " + bad.getMessage());
                msg(viewer, "gone");
                return;
            }
            then.accept(items);
        }));
    }

    private void restore(Player staff, Backup b, String targetName) {
        String perm = config().getString("restore-permission", "vexcore.invrollback.restore");
        if (!perm.isBlank() && !staff.hasPermission(perm)) {
            msg(staff, "no-permission", "permission", perm);
            return;
        }
        Player target = Bukkit.getPlayer(b.uuid);
        if (target == null) {
            msg(staff, "not-online", "target", targetName);
            return;
        }
        Map<String, Object> ph = placeholders(b);
        ph.put("target", targetName);
        ph.put("staff", staff.getName());
        load(b.id, items -> Scheduler.entity(target, () -> {
            if (!target.isOnline()) {
                msg(staff, "not-online", "target", targetName);
                return;
            }
            // What they have now is kept first (under AUTO), so a restore of the wrong backup can
            // be undone by restoring this one.
            if (config().getBoolean("backup-before-restore", true)) capture(target, Trigger.AUTO, "before restore by " + staff.getName());
            ItemStack[] contents = new ItemStack[target.getInventory().getSize()];
            for (int i = 0; i < contents.length && i < items.length; i++) {
                contents[i] = items[i] == null || items[i].isEmpty() ? null : items[i];
            }
            target.getInventory().setContents(contents);
            if (config().getBoolean("restore-level", false)) target.setLevel(b.level);
            target.updateInventory();
            msg(staff, "restored", ph);
            if (config().getBoolean("tell-player", true) && !target.equals(staff)) msg(target, "restored-target", ph);
        }), staff);
    }

    // ── Placeholders ──────────────────────────────────────────────────────

    private String date(long time) {
        return com.vexorstudios.vexcore.core.Dates.format(time, config().getString("date-format", "dd.MM.yyyy HH:mm"), config().getString("timezone", ""));
    }

    Map<String, Object> placeholders(Backup b) {
        Map<String, Object> ph = new HashMap<>();
        long seconds = Math.max(0, (System.currentTimeMillis() - b.time) / 1000);
        ph.put("date", date(b.time));
        ph.put("ago", config().getString("ago-format", "%time% ago").replace("%time%", plugin.messages().time(seconds)));
        ph.put("items", b.count);
        ph.put("level", b.level);
        ph.put("world", b.world);
        ph.put("x", b.x);
        ph.put("y", b.y);
        ph.put("z", b.z);
        ph.put("note", b.note == null || b.note.isBlank() ? "-" : b.note);
        ph.put("trigger", config().getString("triggers." + b.trigger.key + ".name", b.trigger.key));
        ph.put("color", config().getString("triggers." + b.trigger.key + ".color", "&#FF0000"));
        return ph;
    }
}
