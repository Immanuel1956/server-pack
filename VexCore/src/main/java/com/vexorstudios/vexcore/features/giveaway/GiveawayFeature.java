package com.vexorstudios.vexcore.features.giveaway;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Messages;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Time;
import com.vexorstudios.vexcore.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Player giveaways, like LifestealCore's: /giveaway opens the list of running giveaways (click to
 * enter). Create puts items into a menu; START takes them into the database straight away and
 * right click on it cycles the duration. When a giveaway ends a random entrant wins; the items
 * wait in the database until the winner takes them from Your Items (the owner gets them back
 * when nobody entered). A claim is deleted before the items are handed over, so nothing is ever
 * given twice.
 *
 * <p>/giveaway cancel stops your own giveaway, /giveaway forcecancel &lt;player&gt; [reason] is for
 * staff; the prize goes back to the owner's Your Prizes. Winners (and owners whose prize came
 * back) who were offline are told when they next join: a title for a win.
 */
public final class GiveawayFeature extends Feature implements org.bukkit.event.Listener {

    record Giveaway(long id, UUID owner, String ownerName, String items, int count, long ends, Set<UUID> entries) {
    }

    record Claim(long id, String items, int count, String from) {
    }

    private final Map<Long, Giveaway> running = new ConcurrentHashMap<>();
    /** What a claim still has to tell its owner when they join (giveaway_claims.notify). */
    private static final int NOTIFY_NONE = 0, NOTIFY_WON = 1, NOTIFY_RETURNED = 2, NOTIFY_CANCELLED = 3;
    private final Map<UUID, Integer> durationChoice = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastStart = new ConcurrentHashMap<>();
    private record Delivery(UUID player, Claim claim) {}
    private final Map<Long, Delivery> delivering = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private final Set<UUID> starting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Double> moneyDraft = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        listen(this);
        // MySQL's TEXT stops at 64 KB; a few full shulker boxes are more than that.
        boolean mysql = db().type() == Database.Type.MYSQL;
        String text = mysql ? "MEDIUMTEXT" : "TEXT";
        db().schema("giveaways", "CREATE TABLE IF NOT EXISTS {t} (id BIGINT NOT NULL PRIMARY KEY, owner VARCHAR(36) NOT NULL, "
                + "owner_name VARCHAR(32) NOT NULL, items " + text + " NOT NULL, count INT NOT NULL, ends BIGINT NOT NULL)");
        db().schema("giveaway_entries", "CREATE TABLE IF NOT EXISTS {t} (id BIGINT NOT NULL, uuid VARCHAR(36) NOT NULL, PRIMARY KEY (id, uuid))");
        db().schema("giveaway_claims", "CREATE TABLE IF NOT EXISTS {t} (id BIGINT NOT NULL PRIMARY KEY, uuid VARCHAR(36) NOT NULL, "
                + "items " + text + " NOT NULL, count INT NOT NULL, source VARCHAR(32) NOT NULL, time BIGINT NOT NULL)");
        if (mysql) {
            // Tables made by older versions.
            db().schema("giveaways", "ALTER TABLE {t} MODIFY items MEDIUMTEXT NOT NULL");
            db().schema("giveaway_claims", "ALTER TABLE {t} MODIFY items MEDIUMTEXT NOT NULL");
        }
        db().index("giveaway_claims", "uuid");
        db().addColumn("giveaway_claims", "notify", "INT NOT NULL DEFAULT 0");
        Database db = db();
        db.query("load giveaways", c -> {
            Map<Long, Giveaway> out = new java.util.HashMap<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, owner, owner_name, items, count, ends FROM " + db.table("giveaways"))) {
                while (rs.next()) out.put(rs.getLong(1), new Giveaway(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getLong(6), ConcurrentHashMap.newKeySet()));
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, uuid FROM " + db.table("giveaway_entries"))) {
                while (rs.next()) {
                    Giveaway g = out.get(rs.getLong(1));
                    if (g != null) g.entries.add(UUID.fromString(rs.getString(2)));
                }
            }
            return out;
        }).thenAccept(out -> {
            running.putAll(out);
            loaded = true;
        });
        command("giveaway", this::command, this::complete);
        every(Math.max(1, config().getInt("tick-seconds", 10)) * 20L, this::tick);
    }

    @Override protected void prepareShutdown() {
        synchronized (delivering) {
            for (Delivery d : new ArrayList<>(delivering.values())) {
                if (delivering.remove(d.claim.id, d)) restore(d.player, d.claim);
            }
        }
    }

    private List<String> durations() {
        List<String> d = config().getStringList("durations");
        return d.isEmpty() ? List.of("1h") : d;
    }

    // ── Menus ─────────────────────────────────────────────────────────────

    private void openList(Player p) {
        if (!loaded) {
            msg(p, "data-loading");
            return;
        }
        claims(p.getUniqueId(), list -> open(p, "main", menu -> {
            List<Giveaway> all = new ArrayList<>(running.values());
            all.sort((a, b) -> Long.compare(a.ends, b.ends));
            menu.with("claims", list.size());
            menu.function("create", c -> openType(p));
            menu.function("claims", c -> openClaims(p));
            if (all.isEmpty()) menu.function("empty", c -> {
            });
            menu.paginate(all, (g, slot) -> {
                boolean in = g.entries.contains(p.getUniqueId());
                menu.place(in ? "entered" : "giveaway", slot, Map.of("player", g.ownerName, "items", g.count, "prize", prize(g.items, g.count),
                        "entries", g.entries.size(), "time", plugin.messages().time(Math.max(0, (g.ends - System.currentTimeMillis()) / 1000))), c -> {
                    if (c.type().isRightClick()) preview(p, g.items, () -> openList(p));
                    else {
                        enter(p, g);
                        menu.refresh();
                    }
                });
            });
        }));
    }

    private void enter(Player p, Giveaway g) {
        if (g.owner.equals(p.getUniqueId()) && !config().getBoolean("rules.owner-can-enter", false)) {
            msg(p, "own-giveaway");
            return;
        }
        if (!running.containsKey(g.id)) {
            msg(p, "ended");
            openList(p); // the list without it
            return;
        }
        synchronized (g) { // two linked accounts clicking at once (Folia) can't both get in
            if (g.entries.contains(p.getUniqueId())) {
                msg(p, "already-entered");
                return;
            }
            // Linked accounts (all time, see IP protection) share one entry. Entries are in the
            // database, so this holds across restarts.
            // An alt of the owner can't enter either (a giveaway that always "wins" back to its owner).
            if (!config().getBoolean("rules.owner-can-enter", false) && com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature
                    .linked(p, "giveaway", Set.of(g.owner)) == com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.TAKEN) {
                msg(p, "own-giveaway");
                return;
            }
            var check = com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.linked(p, "giveaway", g.entries);
            if (check != com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.ALLOWED) {
                com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.deny(p, check, "giveaway");
                return;
            }
            g.entries.add(p.getUniqueId());
        }
        Database db = db();
        db.queue("giveaway enter", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.insertIgnore("giveaway_entries", "id", "uuid"))) {
                ps.setLong(1, g.id);
                ps.setString(2, p.getUniqueId().toString());
                ps.executeUpdate();
            }
        });
        msg(p, "entered", "player", g.ownerName);
    }

    private static double moneyAmount(String data) {
        if (!data.startsWith("money:")) return 0;
        try {
            double amount = Double.parseDouble(data.substring(6));
            return Double.isFinite(amount) && amount > 0 ? amount : 0;
        } catch (NumberFormatException bad) { return 0; }
    }

    private String prize(String data, int count) {
        double amount = moneyAmount(data);
        return amount > 0 ? plugin.money().format(amount) : count + " items";
    }

    private boolean canStart(Player p) {
        if (!isEnabled() || !loaded || !ready(p)) return false;
        // Items made in creative must not reach survival players through a giveaway.
        if (config().getStringList("rules.blocked-gamemodes").stream().anyMatch(m -> m.equalsIgnoreCase(p.getGameMode().name()))) {
            msg(p, "gamemode-blocked", "gamemode", p.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
            return false;
        }
        if (starting.contains(p.getUniqueId())) { msg(p, "data-loading"); return false; }
        int max = Math.max(1, config().getInt("rules.max-running", 1));
        if (running.values().stream().filter(g -> g.owner.equals(p.getUniqueId())).count() >= max) {
            msg(p, "max-running", "max", max); return false;
        }
        long cooldown = Math.max(0, config().getLong("rules.cooldown-seconds", 0)) * 1000;
        long left = lastStart.getOrDefault(p.getUniqueId(), 0L) + cooldown - System.currentTimeMillis();
        if (left > 0) { msg(p, "cooldown", "time", plugin.messages().time((left + 999) / 1000)); return false; }
        return true;
    }

    private void openType(Player p) {
        if (!canStart(p)) return;
        open(p, "type", menu -> {
            menu.function("items", c -> openCreate(p));
            menu.function("money", c -> askMoney(p));
            menu.function("back", c -> openList(p));
        });
    }

    private void askMoney(Player p) {
        if (!plugin.money().available()) { msg(p, "no-economy"); return; }
        p.closeInventory();
        msg(p, "money-prompt");
        plugin.chatInput().ask(p, 60, text -> {
            if (!isEnabled() || !ready(p)) return;
            double amount = com.vexorstudios.vexcore.core.Numbers.amount(text, 2);
            if (!Double.isFinite(amount) || amount < Math.max(0.01, config().getDouble("money.min", 1))
                    || amount > config().getDouble("money.max", 1000000000)) {
                msg(p, "invalid-amount"); return;
            }
            moneyDraft.put(p.getUniqueId(), amount);
            openMoney(p);
        }, () -> openType(p));
    }

    private void openMoney(Player p) {
        double amount = moneyDraft.getOrDefault(p.getUniqueId(), 0.0);
        if (amount <= 0) { askMoney(p); return; }
        open(p, "money", menu -> {
            int choice = Math.floorMod(durationChoice.getOrDefault(p.getUniqueId(), 0), durations().size());
            menu.with("amount", plugin.money().format(amount)).with("duration", durations().get(choice));
            menu.function("amount", c -> askMoney(p));
            menu.function("back", c -> openType(p));
            menu.function("start", c -> {
                if (c.type().isRightClick()) { durationChoice.put(p.getUniqueId(), choice + 1); menu.refresh(); return; }
                if (!canStart(p)) return;
                if (!plugin.money().withdraw(p, amount)) { msg(p, "cannot-afford", "amount", plugin.money().format(amount)); return; }
                moneyDraft.remove(p.getUniqueId());
                persist(p, "money:" + Double.toString(amount), 0, durations().get(choice));
            });
        });
    }

    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        durationChoice.remove(event.getPlayer().getUniqueId());
        moneyDraft.remove(event.getPlayer().getUniqueId());
    }

    private void openCreate(Player p) {
        if (!canStart(p)) return;
        open(p, "create", menu -> {
            List<Integer> slots = com.vexorstudios.vexcore.gui.Slots.parse(menu.file().yml().get("item-slots"));
            menu.editable(slots);
            int choice = Math.floorMod(durationChoice.getOrDefault(p.getUniqueId(), 0), durations().size());
            menu.with("duration", durations().get(choice));
            menu.function("back", c -> openType(p)); // closing gives the items back first
            menu.function("start", c -> {
                if (c.type().isRightClick()) {
                    durationChoice.put(p.getUniqueId(), choice + 1);
                    menu.refresh();
                    return;
                }
                start(p, menu, slots, durations().get(choice));
            });
            menu.onClose(m -> {
                for (int slot : slots) { // not started: everything goes back
                    ItemStack item = m.getInventory().getItem(slot);
                    if (item == null || item.isEmpty()) continue;
                    m.getInventory().setItem(slot, null);
                    Menu.giveBack(p, item);
                }
            });
        });
    }

    private void start(Player p, Menu menu, List<Integer> slots, String duration) {
        if (!canStart(p)) return;
        Inventory inv = menu.getInventory();
        List<ItemStack> items = new ArrayList<>();
        List<String> blocked = config().getStringList("rules.blocked-materials");
        for (int slot : slots) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            if (blocked.stream().anyMatch(m -> Material.matchMaterial(m) == item.getType())) {
                msg(p, "blocked-item");
                return;
            }
            items.add(item.clone());
        }
        if (items.size() < config().getInt("rules.min-items", 1) || items.size() > config().getInt("rules.max-items", 36)) {
            msg(p, "item-count", "min", config().getInt("rules.min-items", 1), "max", config().getInt("rules.max-items", 36));
            return;
        }
        String data = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
        for (int slot : slots) inv.setItem(slot, null);
        persist(p, data, items.size(), duration);
    }

    private void persist(Player p, String data, int count, String duration) {
        starting.add(p.getUniqueId());
        long id = System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
        Giveaway g = new Giveaway(id, p.getUniqueId(), p.getName(), data, count,
                System.currentTimeMillis() + Math.max(Math.max(1, config().getLong("rules.min-duration-seconds", 60)), Time.seconds(duration)) * 1000, ConcurrentHashMap.newKeySet());
        Database db = db();
        db.query("giveaway start", c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("giveaways")
                    + " (id, owner, owner_name, items, count, ends) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, g.id);
                ps.setString(2, g.owner.toString());
                ps.setString(3, g.ownerName);
                ps.setString(4, g.items);
                ps.setInt(5, g.count);
                ps.setLong(6, g.ends);
                ps.executeUpdate();
            }
            return true;
        }).whenComplete((saved, error) -> {
            if (error != null) {
                // Retain an offline-safe claim for either prize type; no entity task can drop a refund.
                restore(g.owner, new Claim(g.id, data, count, g.ownerName));
                starting.remove(g.owner);
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Giveaway could not be started; refund claim queued for " + g.ownerName, error);
                if (isEnabled()) msg(p, "failed");
                return;
            }
            running.put(id, g);
            lastStart.put(g.owner, System.currentTimeMillis());
            starting.remove(g.owner);
            if (isEnabled()) broadcast(Messages.everyone(), "started", Map.of("player", g.ownerName, "items", count,
                    "prize", prize(data, count), "time", plugin.messages().time(Math.max(1, (g.ends - System.currentTimeMillis()) / 1000))));
        });
        p.closeInventory();
    }

    private void preview(Player p, String data, Runnable back) {
        double amount = moneyAmount(data);
        if (amount > 0) {
            open(p, "preview", menu -> {
                int slot = menu.file().yml().getInt("money-slot", 22);
                if (menu.file().template("money") != null) {
                    menu.place("money", slot, Map.of("prize", plugin.money().format(amount)), null);
                } else { // preview.yml from before money giveaways
                    var item = new ItemStack(Material.GOLD_INGOT);
                    var meta = item.getItemMeta();
                    meta.displayName(com.vexorstudios.vexcore.core.Text.parse("&#FFD900" + plugin.money().format(amount)));
                    item.setItemMeta(meta);
                    menu.set(slot, item, null);
                }
                menu.function("back", c -> back.run());
            });
            return;
        }
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data));
        open(p, "preview", menu -> {
            for (int i = 0; i < items.length && i < menu.file().size() - 9; i++) if (items[i] != null && !items[i].isEmpty()) menu.set(i, items[i], null);
            menu.function("back", c -> back.run());
        });
    }

    // ── Claims ────────────────────────────────────────────────────────────

    private void claims(UUID player, java.util.function.Consumer<List<Claim>> then) {
        Database db = db();
        Player online = Bukkit.getPlayer(player);
        db.query("giveaway claims", c -> {
            List<Claim> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, items, count, source FROM " + db.table("giveaway_claims") + " WHERE uuid = ? ORDER BY time")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Claim(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
                }
            }
            return out;
        }).thenAccept(list -> {
            if (online != null) Scheduler.entity(online, () -> then.accept(list));
        });
    }

    private void openClaims(Player p) {
        claims(p.getUniqueId(), list -> open(p, "claims", menu -> {
            if (list.isEmpty()) menu.function("empty", c -> {
            });
            menu.function("back", c -> openList(p));
            menu.paginate(list, (claim, slot) -> menu.place("claim", slot, Map.of("items", claim.count, "player", claim.from, "prize", prize(claim.items, claim.count)), c -> {
                if (c.type().isRightClick()) {
                    preview(p, claim.items, () -> openClaims(p));
                    return;
                }
                take(p, claim);
            }));
        }));
    }

    /** Deletes the claim first; only if that worked are the items handed over. */
    private void take(Player p, Claim claim) {
        if (!ready(p) || !isEnabled()) return;
        double amount = moneyAmount(claim.items);
        ItemStack[] contents;
        try {
            if (claim.items.startsWith("money:") && amount <= 0) throw new IllegalArgumentException("Invalid money prize");
            contents = amount > 0 ? new ItemStack[0] : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(claim.items));
        } catch (RuntimeException invalid) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Invalid giveaway claim " + claim.id + "; kept in database", invalid);
            msg(p, "claim-failed"); return;
        }
        Delivery delivery = new Delivery(p.getUniqueId(), claim);
        if (delivering.putIfAbsent(claim.id, delivery) != null) return;
        Database db = db();
        db.query("giveaway take", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("giveaway_claims") + " WHERE id = ? AND uuid = ?")) {
                ps.setLong(1, claim.id);
                ps.setString(2, p.getUniqueId().toString());
                return ps.executeUpdate() == 1;
            }
        }).whenComplete((deleted, error) -> {
            if (error != null || !Boolean.TRUE.equals(deleted)) { delivering.remove(claim.id, delivery); return; }
            if (!delivering.containsKey(claim.id)) return; // shutdown already restored it
            if (!isEnabled()) {
                if (delivering.remove(claim.id, delivery)) restore(delivery.player, claim);
                return;
            }
            Scheduler.entity(p, () -> {
                synchronized (delivering) {
                    if (!delivering.remove(claim.id, delivery)) return;
                    if (!isEnabled() || !plugin.data().isLoaded(p.getUniqueId()) || (amount > 0 && !plugin.money().deposit(p, amount))) {
                        restore(p.getUniqueId(), claim); msg(p, "claim-failed"); return;
                    }
                    for (ItemStack item : contents) Menu.giveBack(p, item);
                    msg(p, "claimed", "items", claim.count, "prize", prize(claim.items, claim.count));
                }
                openClaims(p);
            }, () -> {
                if (delivering.remove(claim.id, delivery)) restore(delivery.player, claim);
            });
        });
    }

    private void restore(UUID player, Claim claim) {
        Database db = db();
        db.queue("giveaway claim back", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.insertIgnore("giveaway_claims", "id", "uuid", "items", "count", "source", "time"))) {
                ps.setLong(1, claim.id);
                ps.setString(2, player.toString());
                ps.setString(3, claim.items);
                ps.setInt(4, claim.count);
                ps.setString(5, claim.from);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    // ── Ending ────────────────────────────────────────────────────────────

    private void tick() {
        long now = System.currentTimeMillis();
        long warn = config().getLong("warn-seconds", 300) * 1000;
        for (Giveaway g : new ArrayList<>(running.values())) {
            long left = g.ends - now;
            long step = Math.max(1, config().getInt("tick-seconds", 10)) * 1000L;
            if (warn > 0 && left <= warn && left > warn - step) {
                broadcast(Messages.everyone(), "ending", Map.of("player", g.ownerName, "time", plugin.messages().time(Math.max(1, left / 1000))));
            }
            if (left > 0 || running.remove(g.id) == null) continue;
            List<UUID> entries = new ArrayList<>(g.entries);
            UUID winner = entries.isEmpty() ? g.owner : entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
            String winnerName = Bukkit.getOfflinePlayer(winner).getName();
            Database db = db();
            db.query("giveaway end", c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("giveaway_claims")
                        + " (id, uuid, items, count, source, time, notify) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setLong(1, g.id);
                    ps.setString(2, winner.toString());
                    ps.setString(3, g.items);
                    ps.setInt(4, g.count);
                    ps.setString(5, g.ownerName);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.setInt(7, entries.isEmpty() ? NOTIFY_RETURNED : NOTIFY_WON); // cleared once they have been told
                    ps.executeUpdate();
                }
                for (String table : List.of("giveaway_entries", "giveaways")) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table(table) + " WHERE id = ?")) {
                        ps.setLong(1, g.id);
                        ps.executeUpdate();
                    }
                }
                return true;
            }).whenComplete((saved, error) -> {
                if (error != null) { running.putIfAbsent(g.id, g); return; }
                if (!isEnabled()) return;
                String prize = prize(g.items, g.count);
                if (!entries.isEmpty()) {
                    broadcast(Messages.everyone(), "winner", Map.of("winner", winnerName == null ? "?" : winnerName, "player", g.ownerName,
                            "entries", entries.size(), "prize", prize));
                }
                // Online now: told right away. Offline: told on their next join (see loaded()).
                Player w = Bukkit.getPlayer(winner);
                if (w != null) Scheduler.entity(w, () -> {
                    if (!isEnabled()) return;
                    if (entries.isEmpty()) msg(w, "no-entries", "prize", prize);
                    else {
                        msg(w, "you-won", "player", g.ownerName, "prize", prize);
                        msg(w, "won-title", "player", g.ownerName, "prize", prize);
                    }
                    clearNotify(winner, List.of(g.id));
                });
            });
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private String staffPermission() {
        return config().getString("staff-permission", "vexcore.giveaway.forcecancel");
    }

    private void command(org.bukkit.command.CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "cancel" -> {
                Player p = player(sender);
                if (p != null && ready(p)) cancelOwn(p, label, args);
            }
            case "forcecancel", "fc", "staffcancel" -> forceCancel(sender, label, args);
            default -> {
                Player p = player(sender);
                if (p != null && ready(p)) openList(p);
            }
        }
    }

    private List<String> complete(org.bukkit.command.CommandSender sender, String[] args) {
        boolean staff = sender.hasPermission(staffPermission());
        if (args.length == 1) return staff ? List.of("cancel", "forcecancel") : List.of("cancel");
        if (args.length == 2 && staff && args[0].equalsIgnoreCase("forcecancel")) {
            return running.values().stream().map(Giveaway::ownerName).distinct().toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel") && sender instanceof Player p) {
            List<String> out = new ArrayList<>();
            for (int i = 1; i <= mine(p.getUniqueId()).size(); i++) out.add(String.valueOf(i));
            return out;
        }
        return List.of();
    }

    /** A player's running giveaways, soonest ending first (what /giveaway cancel <n> counts). */
    private List<Giveaway> mine(UUID owner) {
        List<Giveaway> out = new ArrayList<>();
        for (Giveaway g : running.values()) if (g.owner.equals(owner)) out.add(g);
        out.sort((a, b) -> Long.compare(a.ends, b.ends));
        return out;
    }

    private void cancelOwn(Player p, String label, String[] args) {
        if (!config().getBoolean("rules.owner-can-cancel", true)) {
            msg(p, "cancel-disabled");
            return;
        }
        List<Giveaway> mine = mine(p.getUniqueId());
        if (mine.isEmpty()) {
            msg(p, "cancel-none");
            return;
        }
        Giveaway g;
        if (mine.size() == 1 && args.length < 2) {
            g = mine.getFirst();
        } else {
            int n;
            try {
                n = args.length < 2 ? 0 : Integer.parseInt(args[1]);
            } catch (NumberFormatException bad) {
                n = 0;
            }
            if (n < 1 || n > mine.size()) {
                msg(p, "cancel-which", "command", label);
                for (int i = 0; i < mine.size(); i++) {
                    Giveaway each = mine.get(i);
                    msg(p, "cancel-which-line", "number", i + 1, "prize", prize(each.items, each.count), "entries", each.entries.size(),
                            "time", plugin.messages().time(Math.max(0, (each.ends - System.currentTimeMillis()) / 1000)));
                }
                return;
            }
            g = mine.get(n - 1);
        }
        if (!g.entries.isEmpty() && !config().getBoolean("rules.owner-cancel-with-entries", false)) {
            msg(p, "cancel-has-entries", "entries", g.entries.size());
            return;
        }
        cancel(g, p, false, "");
    }

    private void forceCancel(org.bukkit.command.CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(staffPermission())) {
            msg(sender, "no-permission", "permission", staffPermission());
            return;
        }
        if (args.length < 2) {
            msg(sender, "forcecancel-usage", "command", label);
            return;
        }
        String who = args[1];
        // Plain text: it is broadcast, so no colour codes, clickable tags or placeholders from what was typed.
        String typed = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                .replace("&", "").replace("§", "").replace("<", "").replace(">", "").replace("%", "").strip() : "";
        if (typed.length() > 200) typed = typed.substring(0, 200);
        String reason = typed.isEmpty() ? config().getString("words.no-reason", "No reason given") : typed;
        List<Giveaway> theirs = new ArrayList<>();
        for (Giveaway g : running.values()) if (g.ownerName.equalsIgnoreCase(who)) theirs.add(g);
        if (theirs.isEmpty()) {
            msg(sender, "forcecancel-none", "player", who);
            return;
        }
        for (Giveaway g : theirs) cancel(g, sender, true, reason);
    }

    /**
     * Stops a giveaway: whoever removes it from {@code running} first (this or the end timer) owns
     * it, so it can't both end and be cancelled. The prize goes back to the owner as a claim, in the
     * same transaction that deletes the giveaway; if that fails it keeps running.
     */
    private void cancel(Giveaway g, org.bukkit.command.CommandSender by, boolean staff, String reason) {
        if (running.remove(g.id) == null) {
            msg(by, "ended");
            return;
        }
        Player ownerNow = Bukkit.getPlayer(g.owner);
        Database db = db();
        db.query("giveaway cancel", c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("giveaway_claims")
                    + " (id, uuid, items, count, source, time, notify) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, g.id);
                ps.setString(2, g.owner.toString());
                ps.setString(3, g.items);
                ps.setInt(4, g.count);
                ps.setString(5, g.ownerName);
                ps.setLong(6, System.currentTimeMillis());
                ps.setInt(7, staff ? NOTIFY_CANCELLED : NOTIFY_NONE); // the owner learns it at their next join
                ps.executeUpdate();
            }
            for (String table : List.of("giveaway_entries", "giveaways")) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table(table) + " WHERE id = ?")) {
                    ps.setLong(1, g.id);
                    ps.executeUpdate();
                }
            }
            return true;
        }).whenComplete((saved, error) -> {
            if (error != null) {
                running.putIfAbsent(g.id, g);
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Giveaway " + g.id + " could not be cancelled; it keeps running", error);
                if (isEnabled()) msg(by, "cancel-failed");
                return;
            }
            if (!isEnabled()) return;
            String prize = prize(g.items, g.count);
            String byName = by instanceof Player p ? p.getName() : config().getString("words.console", "Console");
            broadcast(Messages.everyone(), staff ? "cancelled-by-staff" : "cancelled",
                    Map.of("player", g.ownerName, "prize", prize, "staff", byName, "reason", reason, "entries", g.entries.size()));
            if (staff) {
                msg(by, "forcecancel-done", "player", g.ownerName, "prize", prize);
                if (ownerNow != null && ownerNow.isOnline()) Scheduler.entity(ownerNow, () -> {
                    msg(ownerNow, "your-cancelled-by-staff", "staff", byName, "reason", reason, "prize", prize);
                    clearNotify(g.owner, List.of(g.id));
                });
            } else {
                msg(by, "cancel-done", "prize", prize);
            }
        });
    }

    // ── Telling players what happened while they were away ────────────────

    /** Runs when the player's data finished loading: wins, returns and cancels they missed. */
    @Override
    protected void loaded(Player player) {
        UUID id = player.getUniqueId();
        Database db = db();
        db.query("giveaway notices", c -> {
            List<Object[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, items, count, source, notify FROM " + db.table("giveaway_claims")
                    + " WHERE uuid = ? AND notify > 0 ORDER BY time")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getInt(5)});
                }
            }
            return out;
        }).thenAccept(notices -> {
            if (notices.isEmpty() || !isEnabled()) return;
            long delay = Math.max(1, config().getLong("join-notice-delay-ticks", 60)); // after the join screen settles
            Scheduler.entityLater(player, () -> {
                if (!isEnabled() || !player.isOnline()) return;
                List<Long> told = new ArrayList<>();
                boolean titled = false;
                for (Object[] n : notices) {
                    String prize = prize((String) n[1], (int) n[2]);
                    String from = (String) n[3];
                    switch ((int) n[4]) {
                        case NOTIFY_WON -> {
                            if (!titled) msg(player, "won-title", "player", from, "prize", prize); // one title, even for several wins
                            titled = true;
                            msg(player, "won-while-away", "player", from, "prize", prize);
                        }
                        case NOTIFY_RETURNED -> msg(player, "returned-while-away", "prize", prize);
                        case NOTIFY_CANCELLED -> msg(player, "cancelled-while-away", "prize", prize);
                        default -> {
                        }
                    }
                    told.add((Long) n[0]);
                }
                clearNotify(id, told);
            }, delay);
        });
    }

    private void clearNotify(UUID player, List<Long> ids) {
        if (ids.isEmpty()) return;
        Database db = db();
        db.queue("giveaway notices seen", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("giveaway_claims") + " SET notify = 0 WHERE uuid = ? AND id = ?")) {
                for (long claim : ids) {
                    ps.setString(1, player.toString());
                    ps.setLong(2, claim);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }
}
