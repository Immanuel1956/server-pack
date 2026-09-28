package com.vexorstudios.vexcore.features.economy;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Money;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * VexCore's own money, registered with Vault so every other plugin uses the same balances.
 *
 * <p>Online players' balances live in memory and are written every {@code save-seconds} and on
 * quit. Offline players are changed straight in the database with conditional SQL, so a
 * withdrawal can never take more than there is. A player whose data is still loading can't be
 * charged or paid (their load would overwrite it). Two-player payments lock both accounts in a
 * fixed order.
 */
public final class EconomyFeature extends Feature implements PlayerData.Store, Money.Provider {

    static final class Account {
        final String name;
        double balance;
        boolean frozen;
        boolean dirty;
        /** Changed after loading (not just the name refresh). */
        boolean touched;
        /** Unloaded: changes go to the database row instead. */
        boolean closed;

        Account(String name, double balance, boolean frozen) {
            this.name = name;
            this.balance = balance;
            this.frozen = frozen;
        }
    }

    record Top(UUID uuid, String name, double balance) {
    }

    record Payment(String fromUuid, String from, String to, double amount, long time) {
    }

    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private volatile List<Top> top = List.of();
    private Object vault;

    @Override
    protected void enable() {
        db().schema("balances", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                + "name VARCHAR(32) NOT NULL, balance DOUBLE NOT NULL, frozen INT NOT NULL DEFAULT 0)");
        db().schema("payments", "CREATE TABLE IF NOT EXISTS {t} (from_uuid VARCHAR(36) NOT NULL, from_name VARCHAR(32) NOT NULL, "
                + "to_uuid VARCHAR(36) NOT NULL, to_name VARCHAR(32) NOT NULL, amount DOUBLE NOT NULL, time BIGINT NOT NULL)");
        db().index("balances", "balance");
        db().index("payments", "from_uuid");
        db().index("payments", "to_uuid");
        db().index("payments", "time");
        int keepDays = config().getInt("history.keep-days", 30);
        if (keepDays > 0) {
            long cutoff = System.currentTimeMillis() - keepDays * 86_400_000L;
            db().queue("prune payments", c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db().table("payments") + " WHERE time < ?")) {
                    ps.setLong(1, cutoff);
                    ps.executeUpdate();
                }
            });
        }
        store(this);
        plugin.money().use(this);
        toggle("pay", config().getBoolean("pay.default-toggle", true), p -> flip(p, "pay", "toggle-on", "toggle-off"));

        command("balance", this::balance);
        command("pay", this::pay);
        command("paytoggle", (s, l, a) -> {
            Player p = player(s);
            if (p != null) flip(p, "pay", "toggle-on", "toggle-off");
        });
        command("baltop", (s, l, a) -> {
            Player p = player(s);
            if (p != null) openTop(p);
        });
        command("payhistory", this::history);
        command("eco", this::eco, (s, a) -> a.length == 1 ? List.of("give", "take", "set", "reset", "freeze") : a.length == 2 ? null : List.of());

        long flush = Math.max(1, config().getInt("save-seconds", 3)) * 20L;
        every(flush, this::flush);
        refreshTop();
        every(Math.max(10, config().getInt("baltop.refresh-seconds", 60)) * 20L, this::refreshTop);

        // Plain digits (no grouping, never "1.0E7"), for plugins that read the number back.
        placeholder("balance", (p, a) -> Numbers.full(known(p), decimals(), ""));
        placeholder("balance_formatted", (p, a) -> format(known(p)));
        placeholder("balance_short", (p, a) -> shortFormat(known(p)));
        placeholder("baltop_name", (p, a) -> topEntry(a) == null ? config().getString("baltop.empty-name", "-") : topEntry(a).name);
        placeholder("baltop_balance", (p, a) -> topEntry(a) == null ? "0" : shortFormat(topEntry(a).balance));

        if (config().getBoolean("vault.register", true) && Bukkit.getPluginManager().getPlugin("Vault") != null) {
            vault = VaultEconomy.register(this, plugin, config().getString("vault.priority", "Highest"));
        }
    }

    @Override
    protected void disable() {
        if (vault != null) VaultEconomy.unregister(this, plugin);
        vault = null;
        plugin.money().use(null);
        flush();
    }

    // ── Formatting ────────────────────────────────────────────────────────

    public int decimals() {
        return Math.max(0, Math.min(8, config().getInt("decimals", 2)));
    }

    private String currency(String number) {
        return config().getString("currency-format", "$%amount%").replace("%amount%", number);
    }

    @Override
    public String format(double amount) {
        if ("SHORT".equalsIgnoreCase(config().getString("format.chat", "FULL"))) return shortFormat(amount);
        return currency(Numbers.money(amount, decimals(), config().getString("thousands-separator", ",")));
    }

    @Override
    public String shortFormat(double amount) {
        List<String> list = config().getStringList("format.suffixes");
        String[] suffixes = list.isEmpty() ? new String[]{"K", "M", "B", "T", "Q"} : list.toArray(new String[0]);
        return currency(Numbers.shortened(amount, suffixes));
    }

    String currencyName(boolean plural) {
        return config().getString(plural ? "currency-plural" : "currency-singular", plural ? "Dollars" : "Dollar");
    }

    private double max() {
        double max = config().getDouble("max-balance", 0);
        return max > 0 ? max : Double.MAX_VALUE;
    }

    // ── Accounts ──────────────────────────────────────────────────────────

    /** In-memory account of a loaded player, or null (offline, or still loading). */
    Account account(UUID uuid) {
        return accounts.get(uuid);
    }

    /** Joined but not loaded yet: nothing may touch their money. After unload it's the stored row. */
    boolean loading(UUID uuid) {
        return accounts.get(uuid) == null && plugin.data().isLoading(uuid);
    }

    private boolean onDatabaseThread() {
        return Thread.currentThread().getName().equals("VexCore-Database");
    }

    /** Waits for a database answer; used only for other plugins' synchronous Vault calls. */
    private <T> T await(CompletableFuture<T> future, T fallback) {
        if (onDatabaseThread()) {
            plugin.getLogger().warning("An economy call was made from the database thread and refused.");
            return fallback;
        }
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Economy database call failed", e);
            return fallback;
        }
    }

    @Override
    public double balance(OfflinePlayer player) {
        Account a = accounts.get(player.getUniqueId());
        if (a != null) synchronized (a) {
            return a.balance;
        }
        Double stored = await(stored(player.getUniqueId()), null);
        return stored == null ? 0 : stored;
    }

    /** For placeholders: never waits on the database (online, else the last baltop, else 0). */
    private double known(OfflinePlayer player) {
        Account a = accounts.get(player.getUniqueId());
        if (a != null) return a.balance;
        for (Top t : top) if (t.uuid.equals(player.getUniqueId())) return t.balance;
        return 0;
    }

    /** The stored balance of an offline player (null if they have no account). */
    CompletableFuture<Double> stored(UUID uuid) {
        return db().query("balance", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM " + db().table("balances") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getDouble(1) : null;
                }
            }
        });
    }

    boolean hasAccount(OfflinePlayer player) {
        if (accounts.containsKey(player.getUniqueId())) return true;
        return await(stored(player.getUniqueId()), null) != null;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount) || amount < 0) return false;
        // The amount itself in whole units of this economy: an amount that rounds to nothing is
        // refused instead of "succeeding" without changing the balance.
        double exact = amount;
        amount = Numbers.round(amount, decimals(), java.math.RoundingMode.CEILING);
        if (exact > 0 && amount <= 0) return false;
        Account a = accounts.get(player.getUniqueId());
        if (a != null) {
            synchronized (a) {
                if (!a.closed) {
                    if (a.frozen || a.balance < amount) return false;
                    a.balance = Numbers.round(a.balance - amount, decimals());
                    a.dirty = a.touched = true;
                    return true;
                }
            }
        }
        if (loading(player.getUniqueId())) return false;
        return offline(player.getUniqueId(), -amount);
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount) || amount < 0) return false;
        // The amount itself in whole units of this economy: an amount that rounds to nothing is
        // refused instead of "succeeding" without changing the balance.
        double exact = amount;
        amount = Numbers.round(amount, decimals(), java.math.RoundingMode.FLOOR);
        if (exact > 0 && amount <= 0) return false;
        Account a = accounts.get(player.getUniqueId());
        if (a != null) {
            synchronized (a) {
                if (!a.closed) {
                    if (a.frozen || a.balance + amount > max()) return false;
                    a.balance = Numbers.round(a.balance + amount, decimals());
                    a.dirty = a.touched = true;
                    return true;
                }
            }
        }
        if (loading(player.getUniqueId())) return false;
        if (!player.hasPlayedBefore() && !player.isOnline()) return false;
        return offline(player.getUniqueId(), amount);
    }

    /**
     * Changes an offline balance and waits for the answer. A change that times out before it
     * started is called off, so "failed" always means nothing changed; one already running is
     * waited for, so its real result is returned.
     */
    private boolean offline(UUID uuid, double delta) {
        if (onDatabaseThread()) {
            plugin.getLogger().warning("An economy call was made from the database thread and refused.");
            return false;
        }
        AtomicBoolean claimed = new AtomicBoolean();
        CompletableFuture<Boolean> result = offlineChange(uuid, delta, claimed);
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            if (claimed.compareAndSet(false, true)) return false;
            try {
                return result.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Offline balance change of " + uuid + " by " + delta
                        + " did not finish; check the database", e);
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Economy database call failed", e);
            return false;
        }
    }

    /** Adds {@code delta} to an offline balance in one statement; false if not allowed. */
    CompletableFuture<Boolean> offlineChange(UUID uuid, double delta, AtomicBoolean claimed) {
        Database db = db();
        double max = max();
        double start = config().getDouble("start-balance", 0);
        return db.query("offline balance", c -> {
            if (!claimed.compareAndSet(false, true)) return false;
            String sql = "UPDATE " + db.table("balances") + " SET balance = balance + ? WHERE uuid = ? AND frozen = 0"
                    + (delta < 0 ? " AND balance >= ?" : " AND balance + ? <= ?");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setDouble(1, delta);
                ps.setString(2, uuid.toString());
                ps.setDouble(3, delta < 0 ? -delta : delta);
                if (delta >= 0) ps.setDouble(4, max);
                if (ps.executeUpdate() == 1) return true;
            }
            if (delta < 0) return false;
            // No account yet: a player who was here but never loaded one. Start them off.
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            try (PreparedStatement ps = c.prepareStatement(db.insertIgnore("balances", "uuid", "name", "balance", "frozen"))) {
                ps.setString(1, uuid.toString());
                ps.setString(2, op.getName() == null ? "" : op.getName());
                ps.setDouble(3, Math.min(max, start + delta));
                ps.setInt(4, 0);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Admin change of any player, online or offline. Ignores frozen. Result on the global thread. */
    private void admin(OfflinePlayer target, String op, double amount, Consumer<Double> done) {
        Account a = accounts.get(target.getUniqueId());
        double start = config().getDouble("start-balance", 0);
        if (a != null) {
            double now;
            synchronized (a) {
                a.balance = Numbers.round(switch (op) {
                    case "give" -> Math.min(max(), a.balance + amount);
                    case "take" -> Math.max(0, a.balance - amount);
                    case "set" -> Math.min(max(), amount);
                    default -> start;
                }, decimals());
                a.dirty = a.touched = true;
                now = a.balance;
            }
            done.accept(now);
            return;
        }
        if (loading(target.getUniqueId())) {
            done.accept(null);
            return;
        }
        Database db = db();
        double max = max();
        db.query("admin balance", c -> {
            Double current = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM " + db.table("balances") + " WHERE uuid = ?")) {
                ps.setString(1, target.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) current = rs.getDouble(1);
                }
            }
            double base = current == null ? start : current;
            double value = Math.max(0, Math.min(max, switch (op) {
                case "give" -> base + amount;
                case "take" -> base - amount;
                case "set" -> amount;
                default -> start;
            }));
            try (PreparedStatement ps = c.prepareStatement(db.upsert("balances", new String[]{"uuid"}, "name", "balance"))) {
                ps.setString(1, target.getUniqueId().toString());
                ps.setString(2, target.getName() == null ? "" : target.getName());
                ps.setDouble(3, value);
                ps.executeUpdate();
            }
            return value;
        }).whenComplete((value, error) -> Scheduler.global(() -> done.accept(error == null ? value : null)));
    }

    /** Writes every changed balance, in one transaction. */
    private void flush() {
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<UUID, Account> e : accounts.entrySet()) {
            Account a = e.getValue();
            synchronized (a) {
                if (!a.dirty) continue;
                a.dirty = false;
                rows.add(new Object[]{e.getKey().toString(), a.name, a.balance, a.frozen ? 1 : 0});
            }
        }
        if (rows.isEmpty()) return;
        Database db = db();
        db.queue("save balances", c -> write(db, c, rows));
    }

    private static void write(Database db, Connection c, List<Object[]> rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.upsert("balances", new String[]{"uuid"}, "name", "balance", "frozen"))) {
            for (Object[] r : rows) {
                ps.setString(1, (String) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setDouble(3, (Double) r[2]);
                ps.setInt(4, (Integer) r[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── Player data ───────────────────────────────────────────────────────

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Player online = Bukkit.getPlayer(player);
        String name = online == null ? "" : online.getName();
        Account account;
        try (PreparedStatement ps = c.prepareStatement("SELECT balance, frozen FROM " + db().table("balances") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                account = rs.next() ? new Account(name, rs.getDouble(1), rs.getInt(2) != 0)
                        : new Account(name, config().getDouble("start-balance", 0), false);
            }
        }
        account.dirty = true; // stores the current name (and the start balance for new players)
        accounts.put(player, account);
    }

    @Override
    public Database.Work save(UUID player) {
        Account a = accounts.get(player);
        if (a == null) return null;
        Object[] row;
        synchronized (a) {
            if (!a.dirty) return null;
            a.dirty = false;
            row = new Object[]{player.toString(), a.name, a.balance, a.frozen ? 1 : 0};
        }
        Database db = db();
        return c -> write(db, c, List.<Object[]>of(row));
    }

    @Override
    public void unload(UUID player) {
        Account a = accounts.remove(player);
        if (a == null) return;
        Object[] row;
        synchronized (a) {
            a.closed = true;
            // Changed by another thread after the quit save: write that too (in order, after it).
            // Untouched accounts (a load that finished after its player left) write nothing.
            if (!a.dirty || !a.touched) return;
            a.dirty = false;
            row = new Object[]{player.toString(), a.name, a.balance, a.frozen ? 1 : 0};
        }
        Database db = db();
        db.queue("save balance", c -> write(db, c, List.<Object[]>of(row)));
    }

    // ── Commands ──────────────────────────────────────────────────────────

    /** A player by name who has been on the server (online first). Null after telling the sender. */
    private OfflinePlayer known(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && com.vexorstudios.vexcore.core.Visibility.knows(sender, online)) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null || !cached.hasPlayedBefore()) {
            msg(sender, "unknown-player", "player", name);
            return null;
        }
        return cached;
    }

    private String nameOf(OfflinePlayer p) {
        return p.getName() == null ? p.getUniqueId().toString().substring(0, 8) : p.getName();
    }

    private void balance(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            Player p = player(sender);
            if (p == null || !ready(p)) return;
            msg(p, "balance-self", "amount", format(balance(p)));
            return;
        }
        if (!sender.hasPermission("vexcore.balance.others")) {
            msg(sender, "no-permission", "permission", "vexcore.balance.others");
            return;
        }
        OfflinePlayer target = known(sender, args[0]);
        if (target == null) return;
        Account a = accounts.get(target.getUniqueId());
        if (a != null) {
            msg(sender, "balance-other", "player", nameOf(target), "amount", format(a.balance));
            return;
        }
        stored(target.getUniqueId()).whenComplete((value, error) -> Scheduler.global(() ->
                msg(sender, "balance-other", "player", nameOf(target), "amount", format(value == null ? 0 : value))));
    }

    private void pay(CommandSender sender, String label, String[] args) {
        Player from = player(sender);
        if (from == null) return;
        if (args.length < 2) {
            usage(from, "pay");
            return;
        }
        if (!ready(from)) return;
        OfflinePlayer to = known(from, args[0]);
        if (to == null) return;
        if (to.getUniqueId().equals(from.getUniqueId())) {
            msg(from, "self");
            return;
        }
        double amount = Numbers.amount(args[1], decimals());
        if (Double.isNaN(amount)) {
            msg(from, "invalid-amount");
            return;
        }
        double minimum = config().getDouble("pay.minimum", 1);
        if (amount < minimum) {
            msg(from, "minimum", "amount", format(minimum));
            return;
        }
        if (config().getBoolean("pay.block-when-restricted", true) && plugin.restrictions().check(from) != null) {
            msg(from, "restricted");
            return;
        }
        if (to.isOnline() && !plugin.toggles().isOn(to.getUniqueId(), "pay")) {
            msg(from, "toggle-blocked", "player", nameOf(to));
            return;
        }
        Account mine = accounts.get(from.getUniqueId());
        if (mine != null && mine.frozen) {
            msg(from, "frozen-self");
            return;
        }
        double received = Numbers.round(amount * (1 - Math.max(0, Math.min(100, config().getDouble("pay.tax-percent", 0))) / 100), decimals());
        if (!withdraw(from, amount)) {
            msg(from, "cannot-afford", "amount", format(amount));
            return;
        }
        if (accounts.get(to.getUniqueId()) == null && !loading(to.getUniqueId())) {
            // Offline target: one database statement, answered when it's done (no waiting here).
            offlineChange(to.getUniqueId(), received, new AtomicBoolean()).whenComplete((ok, error) -> Scheduler.entity(from,
                    () -> paid(from, to, amount, received, error == null && ok),
                    () -> {
                        if (error != null || !ok) deposit(from, amount);
                        else record(from, to, received);
                    }));
            return;
        }
        paid(from, to, amount, received, deposit(to, received));
    }

    private void paid(Player from, OfflinePlayer to, double amount, double received, boolean ok) {
        if (!ok) {
            deposit(from, amount); // give it back: target frozen, full, or loading
            msg(from, "target-refused", "player", nameOf(to));
            return;
        }
        msg(from, received < amount ? "pay-sent-taxed" : "pay-sent", "player", nameOf(to), "amount", format(amount), "received", format(received));
        if (to.getPlayer() != null) msg(to.getPlayer(), "pay-received", "player", from.getName(), "amount", format(received));
        record(from, to, received);
    }

    private void record(OfflinePlayer from, OfflinePlayer to, double amount) {
        Database db = db();
        long now = System.currentTimeMillis();
        String fromId = from.getUniqueId().toString();
        String toId = to.getUniqueId().toString();
        String fromName = nameOf(from);
        String toName = nameOf(to);
        db.queue("payment", c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("payments")
                    + " (from_uuid, from_name, to_uuid, to_name, amount, time) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, fromId);
                ps.setString(2, fromName);
                ps.setString(3, toId);
                ps.setString(4, toName);
                ps.setDouble(5, amount);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
        });
    }

    private void eco(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, "eco");
            return;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("give", "take", "set", "reset", "freeze").contains(op)) {
            usage(sender, "eco");
            return;
        }
        OfflinePlayer target = known(sender, args[1]);
        if (target == null) return;
        if (op.equals("freeze")) {
            freeze(sender, target);
            return;
        }
        double amount = 0;
        if (!op.equals("reset")) {
            if (args.length < 3) {
                usage(sender, "eco");
                return;
            }
            amount = Numbers.parse(args[2]);
            if (Double.isNaN(amount) || (amount <= 0 && !op.equals("set"))) {
                msg(sender, "invalid-amount");
                return;
            }
            amount = Numbers.round(amount, decimals());
        }
        double change = amount;
        admin(target, op, amount, now -> {
            if (now == null) {
                msg(sender, "not-ready", "player", nameOf(target));
                return;
            }
            msg(sender, op, "player", nameOf(target), "amount", format(change), "balance", format(now));
            Player online = target.getPlayer();
            if (online != null && config().getBoolean("notify-target", true)) {
                msg(online, op + "-received", "amount", format(change), "balance", format(now));
            }
        });
    }

    private void freeze(CommandSender sender, OfflinePlayer target) {
        Account a = accounts.get(target.getUniqueId());
        if (a != null) {
            boolean now;
            synchronized (a) {
                a.frozen = !a.frozen;
                a.dirty = a.touched = true;
                now = a.frozen;
            }
            msg(sender, now ? "freeze-on" : "freeze-off", "player", nameOf(target));
            if (target.getPlayer() != null) msg(target.getPlayer(), now ? "frozen-notice" : "unfrozen-notice");
            return;
        }
        if (loading(target.getUniqueId())) {
            msg(sender, "not-ready", "player", nameOf(target));
            return;
        }
        Database db = db();
        db.query("freeze", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("balances")
                    + " SET frozen = 1 - frozen WHERE uuid = ?")) {
                ps.setString(1, target.getUniqueId().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT frozen FROM " + db.table("balances") + " WHERE uuid = ?")) {
                ps.setString(1, target.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) != 0 : null;
                }
            }
        }).whenComplete((now, error) -> Scheduler.global(() -> {
            if (now == null) msg(sender, "unknown-player", "player", nameOf(target));
            else msg(sender, now ? "freeze-on" : "freeze-off", "player", nameOf(target));
        }));
    }

    // ── Baltop ────────────────────────────────────────────────────────────

    private void refreshTop() {
        int size = Math.max(10, config().getInt("baltop.size", 100));
        flush();
        db().query("baltop", c -> {
            List<Top> list = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, name, balance FROM " + db().table("balances")
                         + " ORDER BY balance DESC LIMIT " + size)) {
                while (rs.next()) {
                    try {
                        list.add(new Top(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getDouble(3)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return list;
        }).thenAccept(list -> top = List.copyOf(list));
    }

    private Top topEntry(String place) {
        try {
            int n = Integer.parseInt(place);
            List<Top> list = top;
            return n >= 1 && n <= list.size() ? list.get(n - 1) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void openTop(Player player) {
        List<Top> list = top;
        open(player, "baltop", menu -> {
            List<Integer> places = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) places.add(i);
            menu.with("balance", format(known(player))); // redrawn often: never wait on the database
            menu.paginate(places, (i, slot) -> {
                Top t = list.get(i);
                String name = t.name == null || t.name.isEmpty() ? config().getString("baltop.empty-name", "-") : t.name;
                Map<String, Object> ph = new HashMap<>();
                ph.put("place", i + 1);
                ph.put("name", name);
                ph.put("amount", shortFormat(t.balance));
                ph.put("amount_full", format(t.balance));
                menu.place("entry", slot, ph, null);
            });
        });
    }

    // ── History ───────────────────────────────────────────────────────────

    private void history(CommandSender sender, String label, String[] args) {
        Player viewer = player(sender);
        if (viewer == null) return;
        OfflinePlayer target = viewer;
        if (args.length > 0) {
            if (!viewer.hasPermission("vexcore.payhistory.others")) {
                msg(viewer, "no-permission", "permission", "vexcore.payhistory.others");
                return;
            }
            target = known(viewer, args[0]);
            if (target == null) return;
        }
        String uuid = target.getUniqueId().toString();
        Database db = db();
        String who = nameOf(target);
        db.query("history", c -> {
            List<Payment> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT from_uuid, from_name, to_name, amount, time FROM " + db.table("payments")
                    + " WHERE from_uuid = ? OR to_uuid = ? ORDER BY time DESC LIMIT 450")) {
                ps.setString(1, uuid);
                ps.setString(2, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(new Payment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getLong(5)));
                }
            }
            return list;
        }).thenAccept(list -> Scheduler.entity(viewer, () -> {
            String time = config().getString("history.time-format", "dd.MM.yyyy HH:mm");
            open(viewer, "payhistory", menu -> {
                menu.with("target", who);
                if (list.isEmpty()) menu.function("empty", c -> {
                });
                // By UUID, so a name change doesn't flip sent and received.
                menu.paginate(list, (p, slot) -> menu.place(p.fromUuid.equalsIgnoreCase(uuid) ? "sent" : "received", slot,
                        Map.of("from", p.from, "to", p.to, "amount", format(p.amount), "time", com.vexorstudios.vexcore.core.Dates.format(p.time, time)), null));
            });
        }));
    }

    // ── SetupCore import ──────────────────────────────────────────────────

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        if (!SetupCoreImport.Source.has(source.main(), "money")) return;
        int count = 0;
        Database db = db();
        try (Statement st = source.main().createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, value FROM money");
             PreparedStatement ps = target.prepareStatement(db.upsert("balances", new String[]{"uuid"}, "name", "balance"))) {
            while (rs.next()) {
                String uuid = rs.getString(1).toLowerCase(Locale.ROOT);
                OfflinePlayer op;
                try {
                    op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                double value = rs.getDouble(2);
                if (!Double.isFinite(value) || value < 0) continue;
                ps.setString(1, uuid);
                ps.setString(2, op.getName() == null ? "" : op.getName());
                ps.setDouble(3, value);
                ps.addBatch();
                if (++count % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        report.add(count, "balances");
    }
}
