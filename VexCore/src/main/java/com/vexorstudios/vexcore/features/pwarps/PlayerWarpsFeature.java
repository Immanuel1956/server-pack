package com.vexorstudios.vexcore.features.pwarps;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Pos;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.features.chatfilter.ChatFilterFeature;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Public warps that players make: /pwarp opens the browser, /pwarp &lt;name&gt; teleports, and
 * /pwarp set|delete|icon|desc|list|cost manage them. The first warp costs {@code cost.base}
 * (100k), each further one more (see {@link #price}). A player may own {@code default-slots} (3),
 * or the highest vexcore.pwarps.&lt;n&gt; / permission-slots entry they have. Every warp lives in
 * the database (shared across servers on MySQL) and in memory for instant lookups.
 */
public final class PlayerWarpsFeature extends Feature implements org.bukkit.event.Listener {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");
    static final Set<String> RESERVED = Set.of("set", "create", "move", "delete", "del", "remove", "icon", "desc",
            "description", "list", "cost", "info", "help", "mine", "top");
    private static final String ADMIN = "vexcore.pwarp.admin";
    private static final String FREE = "vexcore.pwarp.free";

    static final class PWarp {
        final String key;
        final String name;
        final UUID owner;
        final String ownerName;
        final long created;
        final double paid;
        final AtomicInteger visits;
        volatile Pos pos;
        volatile String icon;
        volatile String description;

        PWarp(String name, UUID owner, String ownerName, Pos pos, String icon, String description, long created, int visits, double paid) {
            this.key = name.toLowerCase(Locale.ROOT);
            this.name = name;
            this.owner = owner;
            this.ownerName = ownerName;
            this.pos = pos;
            this.icon = icon;
            this.description = description;
            this.created = created;
            this.visits = new AtomicInteger(visits);
            this.paid = paid;
        }
    }

    private enum Sort {VISITS, NEWEST, NAME}

    private final Map<String, PWarp> warps = new ConcurrentHashMap<>();
    /** Names being written to the database right now, with who is making them. */
    private final Map<String, UUID> creating = new ConcurrentHashMap<>();
    /** "warp|visitor": visits already counted since the last restart (one per visitor per warp). */
    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Sort> sortOf = new ConcurrentHashMap<>();
    private final Set<UUID> onlyMine = ConcurrentHashMap.newKeySet();
    private volatile boolean loaded;

    // Settings, read once (a reload makes a new instance).
    private double base, increase, multiplier, moveCost, refundPercent;
    private int defaultSlots, maxSlots, minName, maxName, maxDescription;
    private String defaultIcon;
    private boolean iconFromHand, safety, announce;
    private Set<String> blockedWorlds;

    @Override
    protected void enable() {
        base = Math.max(0, config().getDouble("cost.base", 100000));
        increase = Math.max(0, config().getDouble("cost.increase-per-warp", 100000));
        multiplier = Math.max(1, config().getDouble("cost.multiplier-per-warp", 1.0));
        moveCost = Math.max(0, config().getDouble("cost.move", 0));
        refundPercent = Math.max(0, Math.min(100, config().getDouble("refund-percent", 0)));
        defaultSlots = Math.max(0, config().getInt("default-slots", 3));
        maxSlots = Math.max(defaultSlots, config().getInt("max-slots", 54));
        minName = Math.max(1, config().getInt("name.min-length", 3));
        maxName = Math.max(minName, Math.min(32, config().getInt("name.max-length", 16)));
        maxDescription = Math.max(0, Math.min(128, config().getInt("description-max-length", 64)));
        defaultIcon = config().getString("default-icon", "ENDER_EYE");
        iconFromHand = config().getBoolean("icon-from-hand", true);
        safety = config().getBoolean("safety-check", true);
        announce = config().getBoolean("announce-new", true);
        blockedWorlds = Set.copyOf(config().getStringList("blocked-worlds"));

        db().schema("pwarps", "CREATE TABLE IF NOT EXISTS {t} (name VARCHAR(32) NOT NULL PRIMARY KEY, display VARCHAR(32) NOT NULL, "
                + "owner VARCHAR(36) NOT NULL, owner_name VARCHAR(32) NOT NULL, world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, "
                + "y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw DOUBLE NOT NULL, pitch DOUBLE NOT NULL, icon VARCHAR(64) NOT NULL, "
                + "description VARCHAR(160) NOT NULL, created BIGINT NOT NULL, visits INT NOT NULL, paid DOUBLE NOT NULL)");
        db().index("pwarps", "owner");
        Database db = db();
        db.query("load pwarps", c -> {
            List<PWarp> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT display, owner, owner_name, world, x, y, z, yaw, pitch, "
                    + "icon, description, created, visits, paid FROM " + db.table("pwarps"))) {
                while (rs.next()) out.add(new PWarp(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                        new Pos(rs.getString(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getFloat(8), rs.getFloat(9)),
                        rs.getString(10), rs.getString(11), rs.getLong(12), rs.getInt(13), rs.getDouble(14)));
            }
            return out;
        }).thenAccept(list -> {
            for (PWarp w : list) warps.put(w.key, w);
            loaded = true;
        });

        listen(this);
        command("pwarp", this::command, this::complete);
        placeholder("pwarps", (p, arg) -> String.valueOf(owned(p.getUniqueId())));
        placeholder("pwarps_max", (p, arg) -> p.getPlayer() == null ? "0" : String.valueOf(allowance(p.getPlayer())));
        placeholder("pwarps_next_cost", (p, arg) -> plugin.money().format(price(owned(p.getUniqueId()))));
    }

    // ── Rules ─────────────────────────────────────────────────────────────

    /** What the next warp costs a player who already owns {@code owned}: (base + increase*owned) * multiplier^owned. */
    static double price(double base, double increase, double multiplier, int owned) {
        double cost = (base + increase * owned) * Math.pow(multiplier, owned);
        return Double.isFinite(cost) ? Math.max(0, cost) : Double.MAX_VALUE;
    }

    private double price(int owned) {
        return price(base, increase, multiplier, owned);
    }

    static boolean validName(String name, int min, int max) {
        return name != null && name.length() >= min && name.length() <= max && NAME.matcher(name).matches();
    }

    /** Warps owned now, plus ones being saved this moment (so two quick /pwarp set can't pass the limit). */
    private int owned(UUID player) {
        int n = 0;
        for (PWarp w : warps.values()) if (w.owner.equals(player)) n++;
        for (UUID who : creating.values()) if (who.equals(player)) n++;
        return n;
    }

    /** The highest of default-slots, any vexcore.pwarps.&lt;n&gt; and the permission-slots list, at most max-slots. */
    int allowance(Player player) {
        int best = defaultSlots;
        for (int n = maxSlots; n > best; n--) {
            if (player.hasPermission("vexcore.pwarps." + n)) {
                best = n;
                break;
            }
        }
        ConfigurationSection extra = config().getConfigurationSection("permission-slots");
        if (extra != null) for (String node : extra.getKeys(false)) {
            int n = extra.getInt(node, 0);
            if (n > best && player.hasPermission(node)) best = n;
        }
        return Math.min(maxSlots, best);
    }

    private boolean mayManage(CommandSender sender, PWarp w) {
        return (sender instanceof Player p && p.getUniqueId().equals(w.owner)) || sender.hasPermission(ADMIN);
    }

    private static final Set<Material> DANGER = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
            Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.WITHER_ROSE, Material.POINTED_DRIPSTONE);

    /** Null when a warp may stand here, else the message key saying why not. Blocks are read, so: the region's thread. */
    private String unsafe(Location l) {
        if (l.getWorld() == null) return "unsafe";
        if (l.getY() < l.getWorld().getMinHeight() + 1) return "unsafe";
        Block at = l.getBlock();
        // Standing on a slab, carpet, path, farmland or chest puts the feet inside that block:
        // then it is the ground, and the body is the block above it.
        Block ground = at.isPassable() ? null : at;
        Block body = ground == null ? at : at.getRelative(0, 1, 0), head = body.getRelative(0, 1, 0);
        if (DANGER.contains(body.getType()) || DANGER.contains(head.getType())) return "unsafe";
        if (!body.isPassable() || !head.isPassable()) return "unsafe"; // inside a wall
        if (ground != null) return DANGER.contains(ground.getType()) ? "unsafe" : null;
        for (int down = 1; down <= 3; down++) {
            Block below = body.getRelative(0, -down, 0);
            Material m = below.getType();
            if (DANGER.contains(m)) return "unsafe";
            if (!below.isPassable() || m == Material.WATER) return null;
        }
        return "unsafe"; // a drop of more than three blocks
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void command(CommandSender sender, String label, String[] args) {
        if (!loaded) {
            msg(sender, "data-loading");
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                Player p = player(sender);
                if (p != null) openMenu(p);
            }
            case "help" -> msg(sender, "help", "command", label);
            case "set", "create", "move" -> {
                Player p = player(sender);
                if (p == null) return;
                if (args.length < 2) msg(p, "usage-set", "command", label);
                else set(p, args[1]);
            }
            case "delete", "del", "remove" -> {
                if (args.length < 2) msg(sender, "usage-delete", "command", label);
                else delete(sender, args[1]);
            }
            case "icon" -> {
                Player p = player(sender);
                if (p == null) return;
                if (args.length < 2) msg(p, "usage-icon", "command", label);
                else icon(p, args[1]);
            }
            case "desc", "description" -> {
                if (args.length < 2) msg(sender, "usage-desc", "command", label);
                else describe(sender, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            case "list", "mine" -> list(sender, args.length > 1 ? args[1] : null);
            case "cost", "info" -> {
                Player p = player(sender);
                if (p == null) return;
                int owned = owned(p.getUniqueId()), allowed = allowance(p);
                msg(p, "cost", "owned", owned, "max", allowed,
                        "cost", owned >= allowed ? "-" : p.hasPermission(FREE) ? plugin.money().format(0) : plugin.money().format(price(owned)));
            }
            default -> {
                Player p = player(sender);
                if (p != null) go(p, args[0]);
            }
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("set", "delete", "icon", "desc", "list", "cost", "help"));
            for (PWarp w : warps.values()) out.add(w.name);
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("list")) return null; // online players
            if (Set.of("delete", "del", "remove", "icon", "desc", "description", "set", "move").contains(sub)) {
                List<String> out = new ArrayList<>();
                for (PWarp w : warps.values()) if (mayManage(sender, w)) out.add(w.name);
                return out;
            }
        }
        return List.of();
    }

    private void go(Player p, String name) {
        PWarp w = warps.get(name.toLowerCase(Locale.ROOT));
        if (w == null) {
            msg(p, "not-found", "name", name);
            return;
        }
        plugin.teleports().start(this, p, () -> destination(w), "vexcore.pwarp.bypass",
                Map.of("name", w.name, "owner", w.ownerName), () -> visit(p, w));
    }

    /**
     * Where a warp sends people. On Paper the landing spot is checked again when its chunk is
     * already loaded (a trap built after the warp was set fails the teleport); never loads a chunk.
     */
    private Location destination(PWarp w) {
        Location l = w.pos.location();
        if (l == null) return null;
        if (safety && !Scheduler.FOLIA && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4) && unsafe(l) != null) return null;
        return l;
    }

    private void visit(Player p, PWarp w) {
        if (p.getUniqueId().equals(w.owner) || !visited.add(w.key + "|" + p.getUniqueId())) return;
        w.visits.incrementAndGet();
        Database db = db();
        db.queue("pwarp visit", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("pwarps") + " SET visits = visits + 1 WHERE name = ?")) {
                ps.setString(1, w.key);
                ps.executeUpdate();
            }
        });
    }

    private void set(Player p, String rawName) {
        String key = rawName.toLowerCase(Locale.ROOT);
        Location here = p.getLocation();
        if (blockedWorlds.contains(here.getWorld().getName())) {
            msg(p, "blocked-world", "world", here.getWorld().getName());
            return;
        }
        if (safety && !p.hasPermission(ADMIN)) {
            String problem = unsafe(here);
            if (problem != null) {
                msg(p, problem);
                return;
            }
        }
        PWarp existing = warps.get(key);
        if (existing != null) { // moving one of your own
            if (!existing.owner.equals(p.getUniqueId())) {
                msg(p, "name-taken", "name", existing.name);
                return;
            }
            boolean charge = moveCost > 0 && !p.hasPermission(FREE);
            if (charge && !plugin.money().available()) {
                msg(p, "no-economy");
                return;
            }
            if (charge && !plugin.money().withdraw(p, moveCost)) {
                msg(p, "cannot-afford", "amount", plugin.money().format(moveCost));
                return;
            }
            existing.pos = Pos.of(here);
            save(existing);
            msg(p, "moved", "name", existing.name, "cost", plugin.money().format(charge ? moveCost : 0));
            return;
        }
        if (!validName(rawName, minName, maxName)) {
            msg(p, "invalid-name", "min", minName, "max", maxName);
            return;
        }
        ChatFilterFeature filter = ChatFilterFeature.of(plugin);
        if (RESERVED.contains(key) || (filter != null && !filter.cleanName(rawName))) {
            msg(p, "name-blocked", "name", rawName);
            return;
        }
        int owned = owned(p.getUniqueId()), allowed = allowance(p);
        if (owned >= allowed && !p.hasPermission(ADMIN)) {
            msg(p, "limit", "max", allowed);
            return;
        }
        double cost = p.hasPermission(FREE) ? 0 : price(owned);
        if (cost > 0 && !plugin.money().available()) {
            msg(p, "no-economy");
            return;
        }
        if (creating.putIfAbsent(key, p.getUniqueId()) != null || warps.containsKey(key)) {
            creating.remove(key, p.getUniqueId());
            msg(p, "name-taken", "name", rawName);
            return;
        }
        if (cost > 0 && !plugin.money().withdraw(p, cost)) {
            creating.remove(key);
            msg(p, "cannot-afford", "amount", plugin.money().format(cost));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        String icon = iconFromHand && !hand.isEmpty() ? hand.getType().name() : defaultIcon;
        PWarp w = new PWarp(rawName, p.getUniqueId(), p.getName(), Pos.of(here), icon, "", System.currentTimeMillis(), 0, cost);
        Database db = db();
        db.query("pwarp create", c -> {
            // A plain INSERT: on a shared MySQL another server may have taken the name first.
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("pwarps") + " (name, display, owner, owner_name, world, x, y, z, "
                    + "yaw, pitch, icon, description, created, visits, paid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                fill(ps, w);
                ps.executeUpdate();
            }
            return true;
        }).whenComplete((ok, error) -> {
            if (error != null) {
                creating.remove(key);
                if (cost > 0) Scheduler.global(() -> plugin.money().deposit(p, cost)); // refund, offline-safe
                plugin.getLogger().warning("Player warp '" + rawName + "' could not be saved: " + error.getMessage());
                if (isEnabled()) msg(p, "failed", "name", rawName);
                return;
            }
            warps.put(key, w); // before the name is released, so nobody slips in between
            creating.remove(key);
            if (!isEnabled()) return;
            msg(p, "created", "name", w.name, "cost", plugin.money().format(cost), "owned", owned + 1, "max", allowed);
            if (announce) broadcast(com.vexorstudios.vexcore.core.Messages.everyone(), "announce", Map.of("player", p.getName(), "name", w.name));
        });
    }

    private static void fill(PreparedStatement ps, PWarp w) throws java.sql.SQLException {
        ps.setString(1, w.key);
        ps.setString(2, w.name);
        ps.setString(3, w.owner.toString());
        ps.setString(4, w.ownerName);
        Pos pos = w.pos;
        ps.setString(5, pos.world());
        ps.setDouble(6, pos.x());
        ps.setDouble(7, pos.y());
        ps.setDouble(8, pos.z());
        ps.setDouble(9, pos.yaw());
        ps.setDouble(10, pos.pitch());
        ps.setString(11, w.icon);
        ps.setString(12, w.description);
        ps.setLong(13, w.created);
        ps.setInt(14, w.visits.get());
        ps.setDouble(15, w.paid);
    }

    /** Writes the changeable parts (position, icon, description) of a warp. */
    private void save(PWarp w) {
        Database db = db();
        Pos pos = w.pos;
        String icon = w.icon, description = w.description;
        db.queue("pwarp save", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("pwarps")
                    + " SET world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, icon = ?, description = ? WHERE name = ?")) {
                ps.setString(1, pos.world());
                ps.setDouble(2, pos.x());
                ps.setDouble(3, pos.y());
                ps.setDouble(4, pos.z());
                ps.setDouble(5, pos.yaw());
                ps.setDouble(6, pos.pitch());
                ps.setString(7, icon);
                ps.setString(8, description);
                ps.setString(9, w.key);
                ps.executeUpdate();
            }
        });
    }

    private PWarp managed(CommandSender sender, String name) {
        PWarp w = warps.get(name.toLowerCase(Locale.ROOT));
        if (w == null) {
            msg(sender, "not-found", "name", name);
            return null;
        }
        if (!mayManage(sender, w)) {
            msg(sender, "not-yours", "name", w.name);
            return null;
        }
        return w;
    }

    private void delete(CommandSender sender, String name) {
        PWarp w = managed(sender, name);
        if (w == null || !warps.remove(w.key, w)) return;
        Database db = db();
        db.queue("pwarp delete", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("pwarps") + " WHERE name = ?")) {
                ps.setString(1, w.key);
                ps.executeUpdate();
            }
        });
        double refund = w.paid * refundPercent / 100.0;
        OfflinePlayer owner = Bukkit.getOfflinePlayer(w.owner);
        if (refund > 0) Scheduler.global(() -> plugin.money().deposit(owner, refund));
        msg(sender, "deleted", "name", w.name, "refund", plugin.money().format(refund));
        boolean own = sender instanceof Player p && p.getUniqueId().equals(w.owner);
        if (!own && owner.getPlayer() != null) msg(owner.getPlayer(), "deleted-by-staff", "name", w.name, "refund", plugin.money().format(refund));
    }

    private void icon(Player p, String name) {
        PWarp w = managed(p, name);
        if (w == null) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            msg(p, "icon-empty-hand");
            return;
        }
        w.icon = hand.getType().name();
        save(w);
        msg(p, "icon-set", "name", w.name, "icon", w.icon);
    }

    private void describe(CommandSender sender, String name, String text) {
        PWarp w = managed(sender, name);
        if (w == null) return;
        text = text.strip();
        if (text.length() > maxDescription) {
            msg(sender, "description-too-long", "max", maxDescription);
            return;
        }
        ChatFilterFeature filter = ChatFilterFeature.of(plugin);
        if (!text.isEmpty() && filter != null && !filter.cleanName(text)) {
            msg(sender, "name-blocked", "name", text);
            return;
        }
        w.description = text;
        save(w);
        msg(sender, text.isEmpty() ? "description-cleared" : "description-set", "name", w.name);
    }

    private void list(CommandSender sender, String who) {
        UUID owner;
        String shown;
        if (who == null) {
            Player p = player(sender);
            if (p == null) return;
            owner = p.getUniqueId();
            shown = p.getName();
        } else {
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(who);
            if (target == null) {
                msg(sender, "unknown-player", "player", who);
                return;
            }
            owner = target.getUniqueId();
            shown = target.getName() == null ? who : target.getName();
        }
        List<String> names = new ArrayList<>();
        for (PWarp w : warps.values()) if (w.owner.equals(owner)) names.add(w.name);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) msg(sender, "list-none", "player", shown);
        else msg(sender, "list", "player", shown, "amount", names.size(), "warps", String.join(", ", names));
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    private void openMenu(Player p) {
        open(p, "pwarps", menu -> {
            UUID me = p.getUniqueId();
            Sort sort = sortOf.getOrDefault(me, Sort.VISITS);
            boolean mine = onlyMine.contains(me);
            List<PWarp> all = new ArrayList<>();
            for (PWarp w : warps.values()) if (!mine || w.owner.equals(me)) all.add(w);
            all.sort(switch (sort) {
                case VISITS -> Comparator.comparingInt((PWarp w) -> w.visits.get()).reversed().thenComparing(w -> w.key);
                case NEWEST -> Comparator.comparingLong((PWarp w) -> w.created).reversed();
                case NAME -> Comparator.comparing((PWarp w) -> w.key);
            });
            int owned = owned(me), allowed = allowance(p);
            menu.with("warps", all.size()).with("owned", owned).with("max", allowed)
                    .with("sort", config().getString("words.sort-" + sort.name().toLowerCase(Locale.ROOT), sort.name()))
                    .with("filter", config().getString(mine ? "words.filter-mine" : "words.filter-all", mine ? "Mine" : "All"))
                    .with("next_cost", owned >= allowed ? "-" : plugin.money().format(p.hasPermission(FREE) ? 0 : price(owned)));
            menu.function("sort", c -> {
                sortOf.put(me, Sort.values()[(sort.ordinal() + 1) % Sort.values().length]);
                menu.refresh();
            });
            menu.function("filter", c -> {
                if (!onlyMine.remove(me)) onlyMine.add(me);
                menu.refresh();
            });
            menu.function("info", c -> {
            });
            if (all.isEmpty()) menu.function("empty", c -> {
            });
            menu.paginate(all, (w, slot) -> {
                Pos pos = w.pos;
                Map<String, Object> ph = new HashMap<>();
                ph.put("name", w.name);
                ph.put("owner", w.ownerName);
                ph.put("icon", Material.matchMaterial(w.icon) == null ? defaultIcon : w.icon);
                // Player text as a component: colour codes and tags in it stay plain text.
                ph.put("description", Component.text(w.description.isEmpty()
                        ? config().getString("words.no-description", "No description") : w.description));
                ph.put("visits", w.visits.get());
                ph.put("world", pos.world());
                ph.put("created", plugin.messages().time(Math.max(0, (System.currentTimeMillis() - w.created) / 1000)));
                menu.place(w.owner.equals(me) ? "own-warp" : "warp", slot, ph, c -> {
                    p.closeInventory();
                    go(p, w.name);
                });
            });
        });
    }

    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        sortOf.remove(event.getPlayer().getUniqueId());
        onlyMine.remove(event.getPlayer().getUniqueId());
    }
}
