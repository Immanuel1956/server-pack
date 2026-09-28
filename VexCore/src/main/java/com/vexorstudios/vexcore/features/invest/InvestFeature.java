package com.vexorstudios.vexcore.features.invest;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Messages;
import com.vexorstudios.vexcore.core.Money;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import com.vexorstudios.vexcore.core.Time;
import com.vexorstudios.vexcore.features.prestige.PrestigeFeature;
import com.vexorstudios.vexcore.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Investments: money parked here pays out every second while its owner is online.
 * income per second = invested * income.rate-per-second (times an event multiplier).
 * How much a player may invest is capped by their limit (default + admin bonus + prestige bonus).
 * Deleting an investment refunds {@code delete-refund-percent} of it.
 */
public final class InvestFeature extends Feature implements PlayerData.Store {

    static final class Account {
        double invested;
        double pending;
        double bonus;
        double earned;        // everything this investment has paid, ever
        long lastSeen;        // when the player was last online (for offline income)
        double offlineEarned; // paid while away, told once they are loaded
        long offlineSeconds;
        boolean auto;
        boolean dirty;
    }

    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private volatile double eventMultiplier = 1;
    private volatile long eventEnds;

    // Read once per reload: income() runs for every investor every second.
    private double rate;     // money per invested dollar per second
    private double maxLimit; // nobody's limit goes above this (0 = no cap)

    @Override
    protected void enable() {
        rate = readRate();
        maxLimit = Math.max(0, config().getDouble("max-limit", 100_000_000));
        db().schema("invest", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, invested DOUBLE NOT NULL, "
                + "pending DOUBLE NOT NULL, bonus DOUBLE NOT NULL, auto INT NOT NULL)");
        db().addColumn("invest", "earned", "DOUBLE NOT NULL DEFAULT 0");
        db().addColumn("invest", "last_seen", "BIGINT NOT NULL DEFAULT 0");
        store(this);
        // A running income event survives reloads and restarts (data/invest.yml).
        var saved = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plugin.files().data("invest.yml"));
        if (saved.getLong("event-ends", 0) > System.currentTimeMillis()) {
            eventMultiplier = saved.getDouble("event-multiplier", 1);
            eventEnds = saved.getLong("event-ends");
        }
        command("invest", this::command, (s, a) -> a.length == 1
                ? (s.hasPermission("vexcore.invest.admin") ? List.of("add", "withdraw", "collect", "top", "limit", "reset", "event")
                : List.of("add", "withdraw", "collect", "top"))
                : a.length == 2 && a[0].equalsIgnoreCase("limit") ? List.of("add")
                : a.length == 3 && a[0].equalsIgnoreCase("limit") ? withAll(playerNames(s))
                : a.length == 2 && a[0].equalsIgnoreCase("reset") ? playerNames(s) : List.of());
        every(20, this::tick);
        placeholder("invest_invested", (p, a) -> money().shortFormat(get(p).invested));
        placeholder("invest_pending", (p, a) -> money().shortFormat(get(p).pending));
        placeholder("invest_income", (p, a) -> money().shortFormat(income(get(p).invested)));
        placeholder("invest_hourly", (p, a) -> money().shortFormat(income(get(p).invested) * 3600));
        placeholder("invest_limit", (p, a) -> p.getPlayer() == null ? "0" : money().shortFormat(limit(p.getPlayer())));
        placeholder("invest_earned", (p, a) -> money().shortFormat(get(p).earned));
    }


    @Override
    protected void disable() {
        flushAll();
    }

    private Money money() {
        return plugin.money();
    }

    private static final Account EMPTY = new Account();

    private Account get(OfflinePlayer p) {
        return accounts.getOrDefault(p.getUniqueId(), EMPTY);
    }

    private double multiplier() {
        if (eventEnds > 0 && System.currentTimeMillis() > eventEnds) endEvent();
        return eventMultiplier;
    }

    double income(double invested) {
        return invested * rate * multiplier();
    }

    /** Income without an event: what offline time pays. */
    private double baseIncome(double invested) {
        return invested * rate;
    }

    /**
     * income.rate-per-second when the owner wrote it; otherwise the old income.per / per-add pair
     * if their file still has it (same result as before); otherwise the default, 0.00001.
     */
    private double readRate() {
        if (config().contains("income.rate-per-second", true)) {
            return Math.max(0, config().getDouble("income.rate-per-second", 0.00001));
        }
        if (config().contains("income.per", true) || config().contains("income.per-add", true)) {
            return Math.max(0, config().getDouble("income.per-add", 1)) / Math.max(1, config().getDouble("income.per", 100000));
        }
        return 0.00001;
    }

    /** Rounds down to what the economy can hold. */
    private double whole(double amount) {
        double unit = unit();
        return Math.floor(amount / unit + 1e-9) * unit;
    }

    double limit(Player player) {
        Account a = get(player);
        double prestige = plugin.features().get("prestige") instanceof PrestigeFeature p ? p.investBonus(player.getUniqueId()) : 0;
        double limit = config().getDouble("default-limit", 250000) + a.bonus + prestige;
        return maxLimit > 0 ? Math.min(maxLimit, limit) : limit;
    }

    // ── Income ────────────────────────────────────────────────────────────

    /** Players told that this account doesn't earn (IP protection), so it is said once. */
    private final java.util.Set<UUID> notEarning = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Players who have the invest menu open, so the timer only refreshes theirs. */
    private final java.util.Set<UUID> viewing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static List<String> withAll(List<String> names) {
        List<String> out = new java.util.ArrayList<>(names);
        out.add("all");
        return out;
    }

    /** The smallest amount the economy can hold (0.01 with 2 decimals). */
    private double unit() {
        int decimals = plugin.features().get("economy") instanceof com.vexorstudios.vexcore.features.economy.EconomyFeature e ? e.decimals() : 2;
        return Math.pow(10, -decimals);
    }

    private void tick() {
        multiplier(); // ends a finished event once, here
        double unit = unit();
        for (Map.Entry<UUID, Account> e : accounts.entrySet()) {
            Account a = e.getValue();
            if (a.invested <= 0) continue;
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) continue;
            if (!com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.earns(player, "invest")) { // an alt of an account that is already earning
                if (notEarning.add(player.getUniqueId())) com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.notEarning(player, "invest");
                continue;
            }
            notEarning.remove(player.getUniqueId());
            double paid = 0;
            synchronized (a) {
                if (a.invested <= 0) continue;
                // Income always collects here first, so fractions of a cent add up instead of
                // being rounded away one second at a time.
                double income = income(a.invested);
                a.pending += income;
                a.earned += income;
                a.dirty = true;
                // Paid in whole units of the economy (cents, or whole dollars with decimals: 0);
                // the rest waits for the next second.
                if (a.auto) paid = Math.floor(a.pending / unit) * unit;
                a.pending -= paid;
            }
            if (paid > 0 && !money().deposit(player, paid)) {
                synchronized (a) {
                    a.pending += paid;
                }
            }
            // Only players with the menu open; checked again on their thread (Folia).
            if (viewing.contains(player.getUniqueId())) Scheduler.entity(player, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu menu
                        && menu.feature() == this && menu.file() == menu("invest")) menu.refresh();
            });
        }
    }

    /** Writes a player's investment now (after anything that moved money). */
    private void saveNow(UUID player) {
        Database.Work w = save(player);
        if (w != null) db().queue("invest save", w);
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void command(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (List.of("limit", "reset", "event").contains(sub)) {
            if (!sender.hasPermission("vexcore.invest.admin")) {
                msg(sender, "no-permission", "permission", "vexcore.invest.admin");
                return;
            }
            switch (sub) {
                case "limit" -> limitCommand(sender, args);
                case "reset" -> resetCommand(sender, args);
                default -> eventCommand(sender, args);
            }
            return;
        }
        Player player = player(sender);
        if (player == null) return;
        switch (sub) {
            case "" -> openInvest(player);
            case "add" -> {
                if (args.length < 2) usage(player, "invest");
                else invest(player, args[1]);
            }
            case "collect" -> collect(player);
            case "withdraw" -> {
                if (args.length < 2) usage(player, "invest");
                else withdraw(player, args[1]);
            }
            case "top" -> top(player);
            default -> usage(player, "invest");
        }
    }

    private boolean usable(Player player) {
        if (!ready(player)) return false;
        if (!money().available()) {
            msg(player, "no-economy");
            return false;
        }
        return true;
    }

    private void invest(Player player, String raw) {
        if (!usable(player)) return;
        Account a = accounts.get(player.getUniqueId());
        if (a == null) return;
        double room = Math.max(0, limit(player) - a.invested);
        boolean all = raw.equalsIgnoreCase("all");
        if (all && room <= 0) {
            msg(player, "no-room", "limit", money().format(limit(player)), "room", money().format(0));
            return;
        }
        double amount = all ? Math.min(room, money().balance(player)) : Numbers.amount(raw, Math.max(0, (int) Math.round(-Math.log10(unit()))));
        if (Double.isNaN(amount) || amount <= 0) {
            msg(player, "invalid-amount");
            return;
        }
        double minimum = config().getDouble("minimum", 1000);
        if (amount < minimum) {
            msg(player, "minimum", "amount", money().format(minimum));
            return;
        }
        var network = com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.claim(player, "invest", "account", 0);
        if (network != com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.ALLOWED) {
            com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.deny(player, network, "invest");
            return;
        }
        if (amount > room) {
            msg(player, "no-room", "limit", money().format(limit(player)), "room", money().format(room));
            return;
        }
        if (!money().withdraw(player, amount)) {
            msg(player, "cannot-afford", "amount", money().format(amount));
            return;
        }
        synchronized (a) {
            a.invested += amount;
            a.dirty = true;
        }
        saveNow(player.getUniqueId());
        msg(player, "invested", "amount", money().format(amount), "income", money().format(income(a.invested)));
    }

    private void collect(Player player) {
        if (!usable(player)) return;
        Account a = accounts.get(player.getUniqueId());
        if (a == null) return;
        double pending;
        synchronized (a) {
            // Whole units of the economy; the rest stays for next time.
            double unit = unit();
            pending = Math.floor(a.pending / unit + 1e-9) * unit;
            if (pending <= 0) {
                msg(player, "nothing");
                return;
            }
            a.pending -= pending;
            a.dirty = true;
        }
        if (!money().deposit(player, pending)) {
            synchronized (a) {
                a.pending += pending;
            }
            msg(player, "cannot-collect");
            return;
        }
        saveNow(player.getUniqueId());
        msg(player, "collected", "amount", money().format(pending));
    }

    private void delete(Player player) {
        if (!usable(player)) return;
        Account a = accounts.get(player.getUniqueId());
        if (a == null) return;
        double invested;
        synchronized (a) {
            invested = a.invested;
            if (invested <= 0) {
                msg(player, "nothing");
                return;
            }
            a.invested = 0;
            a.dirty = true;
        }
        if (a.pending >= unit()) collect(player);
        double refund = whole(invested * Math.max(0, Math.min(100, config().getDouble("delete-refund-percent", 0))) / 100);
        if (refund > 0 && !money().deposit(player, refund)) {
            synchronized (a) {
                a.pending += refund; // can't pay now (frozen): collectable later
                a.dirty = true;
            }
        }
        saveNow(player.getUniqueId());
        msg(player, "deleted", "amount", money().format(invested), "refund", money().format(refund));
    }

    /** Takes part (or all) of the investment out, minus withdraw.fee-percent. */
    private void withdraw(Player player, String raw) {
        if (!usable(player)) return;
        if (!config().getBoolean("withdraw.enabled", true)) {
            msg(player, "withdraw-disabled");
            return;
        }
        Account a = accounts.get(player.getUniqueId());
        if (a == null) return;
        double fee = Math.max(0, Math.min(100, config().getDouble("withdraw.fee-percent", 50)));
        double amount, payout;
        synchronized (a) {
            if (a.invested <= 0) {
                msg(player, "nothing");
                return;
            }
            amount = raw.equalsIgnoreCase("all") ? a.invested
                    : Numbers.amount(raw, Math.max(0, (int) Math.round(-Math.log10(unit()))));
            if (Double.isNaN(amount) || amount <= 0) {
                msg(player, "invalid-amount");
                return;
            }
            if (amount > a.invested + 1e-9) {
                msg(player, "withdraw-too-much", "invested", money().format(a.invested));
                return;
            }
            double left = a.invested - amount;
            double minimum = config().getDouble("minimum", 1000);
            if (left > 1e-9 && left < minimum && config().getBoolean("withdraw.keep-minimum", true)) {
                msg(player, "withdraw-below-minimum", "amount", money().format(minimum));
                return;
            }
            payout = whole(amount * (100 - fee) / 100);
            a.invested = left <= 1e-9 ? 0 : left;
            a.dirty = true;
        }
        if (payout > 0 && !money().deposit(player, payout)) {
            synchronized (a) {
                a.pending += payout; // can't pay now (frozen): collectable later
                a.dirty = true;
            }
        }
        saveNow(player.getUniqueId());
        msg(player, "withdrawn", "amount", money().format(amount), "payout", money().format(payout),
                "fee", Numbers.full(fee, 1, "") + "%", "income", money().format(income(a.invested)));
    }

    /** /invest top: the biggest investments. */
    private void top(Player player) {
        flushAll(); // online players' latest amounts first; the queue keeps the order
        int size = Math.max(1, Math.min(20, config().getInt("top.size", 10)));
        Database db = db();
        db.query("invest top", c -> {
            List<Object[]> rows = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, invested, earned FROM " + db.table("invest")
                    + " WHERE invested > 0 ORDER BY invested DESC")) {
                ps.setMaxRows(size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[]{rs.getString(1), rs.getDouble(2), rs.getDouble(3)});
                }
            }
            return rows;
        }).whenComplete((rows, error) -> {
            if (!plugin.isEnabled()) return;
            Scheduler.entity(player, () -> {
                if (rows == null || rows.isEmpty()) {
                    msg(player, "top-empty");
                    return;
                }
                msg(player, "top-header", "size", rows.size());
                int place = 1;
                for (Object[] r : rows) {
                    String name;
                    try {
                        name = Bukkit.getOfflinePlayer(UUID.fromString((String) r[0])).getName();
                    } catch (IllegalArgumentException bad) {
                        name = null;
                    }
                    msg(player, "top-line", "place", place++, "player", name == null ? "?" : name,
                            "invested", money().format((double) r[1]), "earned", money().format((double) r[2]),
                            "income", money().format(income((double) r[1])));
                }
            });
        });
    }

    private void toggleAuto(Player player) {
        if (!ready(player)) return;
        Account a = accounts.get(player.getUniqueId());
        if (a == null) return;
        boolean now;
        synchronized (a) {
            a.auto = !a.auto;
            a.dirty = true;
            now = a.auto;
        }
        msg(player, now ? "auto-on" : "auto-off");
        if (now && a.pending >= unit()) collect(player);
    }

    // ── Admin ─────────────────────────────────────────────────────────────

    private void limitCommand(CommandSender sender, String[] args) {
        if (args.length < 4 || !args[1].equalsIgnoreCase("add")) {
            msg(sender, "usage-limit");
            return;
        }
        double amount = Numbers.amount(args[3], 2);
        if (Double.isNaN(amount)) {
            msg(sender, "invalid-amount");
            return;
        }
        Database db = db();
        if (args[2].equalsIgnoreCase("all")) {
            for (Account a : accounts.values()) synchronized (a) {
                a.bonus += amount;
                a.dirty = true; // online players without a row yet get it saved too
            }
            db.queue("invest limit all", c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("invest") + " SET bonus = bonus + ?")) {
                    ps.setDouble(1, amount);
                    ps.executeUpdate();
                }
            });
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (accounts.containsKey(online.getUniqueId())) {
                    msg(online, "limit-received", "amount", money().format(amount), "limit", money().format(limit(online)));
                }
            }
            msg(sender, "limit-all", "amount", money().format(amount));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[2]);
        if (target == null || !target.hasPlayedBefore() && !target.isOnline()) {
            msg(sender, "unknown-player", "player", args[2]);
            return;
        }
        Account a = accounts.get(target.getUniqueId());
        if (a != null) {
            synchronized (a) {
                a.bonus += amount;
                a.dirty = true;
            }
            saveNow(target.getUniqueId());
            Player online = target.getPlayer();
            if (online != null) msg(online, "limit-received", "amount", money().format(amount), "limit", money().format(limit(online)));
        } else if (target.isOnline()) {
            msg(sender, "not-ready");
            return;
        } else {
            String uuid = target.getUniqueId().toString();
            db.queue("invest limit", c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("invest") + " SET bonus = bonus + ? WHERE uuid = ?")) {
                    ps.setDouble(1, amount);
                    ps.setString(2, uuid);
                    if (ps.executeUpdate() == 1) return;
                }
                insert(db, c, uuid, 0, 0, amount, false);
            });
        }
        msg(sender, "limit-added", "player", target.getName(), "amount", money().format(amount));
    }

    private void resetCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "usage-reset");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            msg(sender, "unknown-player", "player", args[1]);
            return;
        }
        Account a = accounts.get(target.getUniqueId());
        if (a != null) {
            synchronized (a) {
                a.invested = 0;
                a.pending = 0;
                a.dirty = true;
            }
            saveNow(target.getUniqueId());
        } else if (target.isOnline()) {
            msg(sender, "not-ready");
            return;
        } else {
            Database db = db();
            String uuid = target.getUniqueId().toString();
            db.queue("invest reset", c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("invest") + " SET invested = 0, pending = 0 WHERE uuid = ?")) {
                    ps.setString(1, uuid);
                    ps.executeUpdate();
                }
            });
        }
        msg(sender, "reset", "player", target.getName());
    }

    private void eventCommand(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            if (!endEvent()) msg(sender, "no-event");
            return;
        }
        double multiplier = args.length < 3 ? Double.NaN : Numbers.parse(args[1]);
        long seconds = args.length < 3 ? -1 : Time.seconds(args[2]);
        if (Double.isNaN(multiplier) || multiplier <= 0 || seconds <= 0) {
            msg(sender, "usage-event");
            return;
        }
        eventMultiplier = multiplier;
        eventEnds = System.currentTimeMillis() + seconds * 1000;
        saveEvent();
        broadcast(Messages.everyone(), "event-start", Map.of("multiplier", Numbers.full(multiplier, 2, ""), "time", plugin.messages().time(seconds)));
    }

    /** Ends a running event. False if none was running. */
    private synchronized boolean endEvent() {
        if (eventMultiplier == 1 && eventEnds == 0) return false;
        eventMultiplier = 1;
        eventEnds = 0;
        saveEvent();
        broadcast(Messages.everyone(), "event-end", Map.of());
        return true;
    }

    private void saveEvent() {
        var yml = new org.bukkit.configuration.file.YamlConfiguration();
        yml.set("event-multiplier", eventMultiplier);
        yml.set("event-ends", eventEnds);
        try {
            yml.save(plugin.files().data("invest.yml"));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not save data/invest.yml: " + e.getMessage());
        }
    }

    // ── Menus ─────────────────────────────────────────────────────────────

    private void openInvest(Player player) {
        if (!ready(player)) return;
        open(player, "invest", menu -> {
            viewing.add(player.getUniqueId());
            menu.onClose(m -> viewing.remove(player.getUniqueId()));
            Account a = get(player);
            double limit = limit(player);
            menu.with("invested", money().format(a.invested)).with("pending", money().format(a.pending))
                    .with("income", money().format(income(a.invested))).with("limit", money().format(limit))
                    .with("room", money().format(Math.max(0, limit - a.invested)))
                    .with("multiplier", Numbers.full(multiplier(), 2, ""))
                    .with("earned", money().format(a.earned))
                    .with("rate", Numbers.full(rate * 100, 6, "") + "%")
                    .with("hourly", money().format(income(a.invested) * 3600))
                    .with("max_limit", maxLimit > 0 ? money().format(maxLimit) : "-")
                    .with("earning", com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.earns(player, "invest") ? config().getString("words.earning", "&#7CFC00Yes")
                            : config().getString("words.not-earning", "&#FB0000No &7(another account on your network)"))
                    .with("offline_percent", Numbers.full(offlinePercent(), 1, "") + "%")
                    .with("offline_hours", Numbers.full(config().getDouble("offline-income.max-hours", 12), 1, ""))
                    .with("withdraw_fee", Numbers.full(config().getDouble("withdraw.fee-percent", 50), 1, "") + "%")
                    .with("refund", Numbers.full(config().getDouble("delete-refund-percent", 0), 1, "") + "%");
            menu.function("invest", c -> {
                player.closeInventory();
                msg(player, "invest-prompt", "room", money().format(Math.max(0, limit(player) - get(player).invested)));
                plugin.chatInput().ask(player, Math.max(5, config().getInt("input-seconds", 30)), text -> {
                    invest(player, text);
                    openInvest(player);
                }, () -> openInvest(player));
            });
            if (a.invested > 0 && config().getBoolean("withdraw.enabled", true)) menu.function("withdraw", c -> {
                player.closeInventory();
                msg(player, "withdraw-prompt", "invested", money().format(get(player).invested),
                        "fee", Numbers.full(config().getDouble("withdraw.fee-percent", 50), 1, "") + "%");
                plugin.chatInput().ask(player, Math.max(5, config().getInt("input-seconds", 30)), text -> {
                    withdraw(player, text);
                    openInvest(player);
                }, () -> openInvest(player));
            });
            menu.function("top", c -> {
                player.closeInventory();
                top(player);
            });
            menu.function("collect", c -> {
                collect(player);
                menu.refresh();
            });
            menu.function(a.auto ? "auto-collect-off" : "auto-collect-on", c -> {
                toggleAuto(player);
                menu.refresh();
            });
            if (a.invested > 0) menu.function("delete", c -> {
                if (menu("confirm-delete") == null) {
                    delete(player);
                    menu.refresh();
                    return;
                }
                open(player, "confirm-delete", m -> {
                    m.with("invested", money().format(get(player).invested)).with("refund",
                            money().format(get(player).invested * config().getDouble("delete-refund-percent", 0) / 100));
                    m.function("confirm", x -> {
                        delete(player);
                        openInvest(player);
                    });
                    m.function("cancel", x -> openInvest(player));
                });
            });
        });
    }

    // ── Player data ───────────────────────────────────────────────────────

    private static void insert(Database db, Connection c, String uuid, double invested, double pending, double bonus, boolean auto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.upsert("invest", new String[]{"uuid"}, "invested", "pending", "bonus", "auto"))) {
            ps.setString(1, uuid);
            ps.setDouble(2, invested);
            ps.setDouble(3, pending);
            ps.setDouble(4, bonus);
            ps.setInt(5, auto ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private static void insert(Database db, Connection c, String uuid, double invested, double pending, double bonus, boolean auto,
                               double earned, long lastSeen) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.upsert("invest", new String[]{"uuid"},
                "invested", "pending", "bonus", "auto", "earned", "last_seen"))) {
            ps.setString(1, uuid);
            ps.setDouble(2, invested);
            ps.setDouble(3, pending);
            ps.setDouble(4, bonus);
            ps.setInt(5, auto ? 1 : 0);
            ps.setDouble(6, earned);
            ps.setLong(7, lastSeen);
            ps.executeUpdate();
        }
    }

    private double offlinePercent() {
        return config().getBoolean("offline-income.enabled", true)
                ? Math.max(0, Math.min(100, config().getDouble("offline-income.percent", 25))) : 0;
    }

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Account a = new Account();
        try (PreparedStatement ps = c.prepareStatement("SELECT invested, pending, bonus, auto, earned, last_seen FROM " + db().table("invest") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    a.invested = rs.getDouble(1);
                    a.pending = rs.getDouble(2);
                    a.bonus = rs.getDouble(3);
                    a.auto = rs.getInt(4) != 0;
                    a.earned = rs.getDouble(5);
                    a.lastSeen = rs.getLong(6);
                }
            }
        }
        // Offline income: a share of the normal rate for the time away, up to max-hours.
        double percent = offlinePercent();
        long now = System.currentTimeMillis();
        if (percent > 0 && a.invested > 0 && a.lastSeen > 0 && now > a.lastSeen) {
            long max = (long) (Math.max(0, config().getDouble("offline-income.max-hours", 12)) * 3600);
            long seconds = Math.min(max, (now - a.lastSeen) / 1000);
            if (seconds >= Math.max(0, config().getLong("offline-income.min-minutes", 5)) * 60) {
                // Credited in loaded(), once IP protection knows whose network this is.
                a.offlineEarned = baseIncome(a.invested) * seconds * percent / 100;
                a.offlineSeconds = seconds;
            }
        }
        a.lastSeen = now;
        accounts.put(player, a);
    }

    @Override
    protected void loaded(Player player) {
        Account a = accounts.get(player.getUniqueId());
        if (a == null) return;
        double earned;
        long seconds;
        synchronized (a) {
            earned = a.offlineEarned;
            seconds = a.offlineSeconds;
            a.offlineEarned = 0;
        }
        if (earned < unit()) return;
        // Only the network's investing account earns while away (LOADING: let it through).
        if (com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.claim(player, "invest", "account", 0) == com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.TAKEN) {
            com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.notEarning(player, "invest");
            return;
        }
        synchronized (a) {
            a.pending += earned;
            a.earned += earned;
            a.dirty = true;
        }
        msg(player, "offline-earned", "amount", money().format(whole(earned)), "time", plugin.messages().time(seconds),
                "percent", Numbers.full(offlinePercent(), 1, "") + "%");
        if (a.auto) collect(player);
    }

    @Override
    public Database.Work save(UUID player) {
        Account a = accounts.get(player);
        if (a == null) return null;
        double invested, pending, bonus, earned;
        boolean auto;
        long seen = System.currentTimeMillis();
        synchronized (a) {
            if (!a.dirty) return null;
            a.dirty = false;
            invested = a.invested;
            pending = a.pending;
            bonus = a.bonus;
            auto = a.auto;
            earned = a.earned;
            a.lastSeen = seen;
        }
        Database db = db();
        String uuid = player.toString();
        return c -> insert(db, c, uuid, invested, pending, bonus, auto, earned, seen);
    }

    private void flushAll() {
        for (UUID id : accounts.keySet()) saveNow(id);
    }

    @Override
    public void unload(UUID player) {
        accounts.remove(player);
        viewing.remove(player);
        notEarning.remove(player);
    }

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        if (!SetupCoreImport.Source.has(source.main(), "invests")) return;
        boolean autoSell = SetupCoreImport.Source.has(source.main(), "auto_sell");
        int count = 0;
        String sql = "SELECT i.uuid, i.value" + (autoSell ? ", a.value" : ", 0") + " FROM invests i"
                + (autoSell ? " LEFT JOIN auto_sell a ON a.uuid = i.uuid" : "");
        try (Statement st = source.main().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                double value = rs.getDouble(2);
                if (!Double.isFinite(value) || value < 0) continue;
                insert(db(), target, rs.getString(1).toLowerCase(Locale.ROOT), value, 0, 0, rs.getInt(3) == 1);
                count++;
            }
        }
        report.add(count, "investments");
    }
}
