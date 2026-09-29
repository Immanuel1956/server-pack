package com.vexorstudios.vexcore.features.coinflip;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Money;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coinflip: /cf opens the open games, /cf create &lt;amount&gt;, /cf delete, /cf toggle, /cf history.
 *
 * <p>The bet is taken when a game is created and the game is stored, so it survives restarts.
 * Joining pays out at once (the animation only shows what already happened), so a restart
 * mid-flip can never lose a stake. A payout that can't be delivered right now (the winner is
 * frozen or still loading) is kept and paid on their next login. A game only starts if the one
 * clicked is still the same game at the same amount.
 */
public final class CoinflipFeature extends Feature implements PlayerData.Store {

    record Game(UUID creator, String name, double amount, long created) {
    }

    record Stats(int wins, int losses, double won, double lost) {
    }

    record Result(String winnerUuid, String winner, String loser, double amount, long time) {
    }

    private static final String TOGGLE = "coinflip";

    private final Map<UUID, Game> games = new ConcurrentHashMap<>();
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAction = new ConcurrentHashMap<>();
    /** Open games are read from the database at start; nobody plays until they are in. */
    private volatile boolean gamesLoaded;

    @Override
    protected void enable() {
        db().schema("coinflips", "CREATE TABLE IF NOT EXISTS {t} (creator VARCHAR(36) NOT NULL PRIMARY KEY, "
                + "name VARCHAR(32) NOT NULL, amount DOUBLE NOT NULL, created BIGINT NOT NULL)");
        db().schema("coinflip_stats", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                + "wins INT NOT NULL, losses INT NOT NULL, won DOUBLE NOT NULL, lost DOUBLE NOT NULL)");
        db().schema("coinflip_results", "CREATE TABLE IF NOT EXISTS {t} (winner_uuid VARCHAR(36) NOT NULL, "
                + "winner_name VARCHAR(32) NOT NULL, loser_uuid VARCHAR(36) NOT NULL, loser_name VARCHAR(32) NOT NULL, "
                + "amount DOUBLE NOT NULL, time BIGINT NOT NULL)");
        db().schema("coinflip_payouts", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, amount DOUBLE NOT NULL, reason VARCHAR(16) NOT NULL)");
        db().index("coinflip_payouts", "uuid");
        db().index("coinflip_results", "winner_uuid");
        db().index("coinflip_results", "loser_uuid");
        db().index("coinflip_results", "time");
        int keepDays = config().getInt("history.keep-days", 30);
        if (keepDays > 0) {
            long cutoff = System.currentTimeMillis() - keepDays * 86_400_000L;
            db().queue("prune coinflips", c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db().table("coinflip_results") + " WHERE time < ?")) {
                    ps.setLong(1, cutoff);
                    ps.executeUpdate();
                }
            });
        }
        db().query("load coinflips", c -> {
            List<Game> list = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT creator, name, amount, created FROM " + db().table("coinflips"))) {
                while (rs.next()) list.add(new Game(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getDouble(3), rs.getLong(4)));
            }
            return list;
        }).thenAccept(list -> {
            list.forEach(g -> games.put(g.creator, g));
            gamesLoaded = true;
        });

        store(this);
        toggle(TOGGLE, config().getBoolean("default-toggle", true), p -> flip(p, TOGGLE, "toggle-on", "toggle-off"));
        command("coinflip", this::command, (s, a) -> a.length == 1 ? List.of("create", "delete", "toggle", "history") : List.of());
        placeholder("coinflip_games", (p, a) -> count(games.size(), a));
        placeholder("coinflip_wins", (p, a) -> count(stats.getOrDefault(p.getUniqueId(), new Stats(0, 0, 0, 0)).wins, a));
        placeholder("coinflip_losses", (p, a) -> count(stats.getOrDefault(p.getUniqueId(), new Stats(0, 0, 0, 0)).losses, a));
    }

    private Money money() {
        return plugin.money();
    }

    private int decimals() {
        return Math.max(0, config().getInt("decimals", 2));
    }

    /** Blocks macros spamming create/delete. */
    private boolean tooFast(Player player) {
        long now = System.currentTimeMillis();
        long gap = config().getInt("cooldown-seconds", 3) * 1000L;
        Long last = lastAction.get(player.getUniqueId());
        if (gap > 0 && last != null && now - last < gap && !player.hasPermission("vexcore.coinflip.bypass")) {
            msg(player, "too-fast");
            return true;
        }
        lastAction.put(player.getUniqueId(), now);
        return false;
    }

    private boolean usable(Player player) {
        if (!ready(player)) return false;
        if (!gamesLoaded) {
            msg(player, "data-loading");
            return false;
        }
        if (!money().available()) {
            msg(player, "no-economy");
            return false;
        }
        if (config().getBoolean("block-when-restricted", true) && plugin.restrictions().check(player) != null) {
            msg(player, "restricted");
            return false;
        }
        return true;
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void command(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> openGames(player);
            case "create" -> {
                if (args.length < 2) usage(player, "coinflip");
                else create(player, args[1]);
            }
            case "delete", "cancel", "remove" -> delete(player);
            case "toggle" -> flip(player, TOGGLE, "toggle-on", "toggle-off");
            case "history" -> history(player);
            default -> {
                if (!Double.isNaN(Numbers.parse(args[0]))) create(player, args[0]); // /cf 1000
                else usage(player, "coinflip");
            }
        }
    }

    private void create(Player player, String raw) {
        if (!usable(player)) return;
        if (!player.hasPermission("vexcore.coinflip.create")) {
            msg(player, "no-permission", "permission", "vexcore.coinflip.create");
            return;
        }
        if (games.containsKey(player.getUniqueId())) {
            msg(player, "already-open");
            return;
        }
        double amount = Numbers.amount(raw, decimals());
        if (Double.isNaN(amount)) {
            msg(player, "invalid-amount");
            return;
        }
        double min = config().getDouble("minimum", 10);
        double max = config().getDouble("maximum", 0);
        if (amount < min) {
            msg(player, "minimum", "amount", money().format(min));
            return;
        }
        if (max > 0 && amount > max) {
            msg(player, "maximum", "amount", money().format(max));
            return;
        }
        // The anti-spam wait only starts once the game is really being made (a typo doesn't count).
        if (tooFast(player)) return;
        if (!money().withdraw(player, amount)) {
            msg(player, "cannot-afford", "amount", money().format(amount));
            return;
        }
        Game game = new Game(player.getUniqueId(), player.getName(), amount, System.currentTimeMillis());
        if (games.putIfAbsent(player.getUniqueId(), game) != null) { // a second create in the same tick
            money().deposit(player, amount);
            msg(player, "already-open");
            return;
        }
        Database db = db();
        db.queue("create coinflip", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("coinflips", new String[]{"creator"}, "name", "amount", "created"))) {
                ps.setString(1, game.creator.toString());
                ps.setString(2, game.name);
                ps.setDouble(3, game.amount);
                ps.setLong(4, game.created);
                ps.executeUpdate();
            }
        });
        msg(player, "created-self", "amount", money().format(amount));
        announce("created", Map.of("player", player.getName(), "amount", money().format(amount)), player);
    }

    private void delete(Player player) {
        if (!ready(player)) return;
        if (!games.containsKey(player.getUniqueId())) {
            msg(player, "no-game");
            return;
        }
        if (tooFast(player)) return;
        Game game = games.remove(player.getUniqueId());
        if (game == null) {
            msg(player, "no-game");
            return;
        }
        removeRow(game.creator);
        pay(player, game.amount, "refund");
        msg(player, "deleted", "amount", money().format(game.amount));
    }

    private void removeRow(UUID creator) {
        Database db = db();
        db.queue("remove coinflip", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("coinflips") + " WHERE creator = ?")) {
                ps.setString(1, creator.toString());
                ps.executeUpdate();
            }
        });
    }

    /** Pays out, or keeps the money for the player's next login if it can't be paid now. */
    private void pay(OfflinePlayer player, double amount, String reason) {
        if (amount <= 0) return;
        // Offline: straight to the payouts table (paid on login) instead of waiting on the database.
        if (player.getPlayer() != null && money().deposit(player, amount)) return;
        keep(player, amount, reason);
    }

    /** Stores a payout for the player's next login. */
    private void keep(OfflinePlayer player, double amount, String reason) {
        Database db = db();
        String uuid = player.getUniqueId().toString();
        db.queue("coinflip payout", c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("coinflip_payouts") + " (uuid, amount, reason) VALUES (?, ?, ?)")) {
                ps.setString(1, uuid);
                ps.setDouble(2, amount);
                ps.setString(3, reason);
                ps.executeUpdate();
            }
        });
    }

    /** A message to everyone who did not turn coinflip messages off. */
    private void announce(String key, Map<String, ?> ph, Player except) {
        List<CommandSender> to = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(except) && plugin.toggles().isOn(p.getUniqueId(), TOGGLE)) to.add(p);
        }
        broadcast(to, key, ph);
    }

    // ── Playing ───────────────────────────────────────────────────────────

    private void join(Player joiner, Game shown) {
        if (!usable(joiner)) return;
        if (shown.creator.equals(joiner.getUniqueId())) {
            msg(joiner, "self");
            return;
        }
        Game game = games.get(shown.creator);
        if (game == null || game.amount != shown.amount || game.created != shown.created || !games.remove(shown.creator, game)) {
            msg(joiner, "gone");
            return;
        }
        if (!money().withdraw(joiner, game.amount)) {
            // Put the game back. If the creator opened a new one in this same moment, that one
            // took the slot (and the stored row): pay this stake back instead of losing it.
            if (games.putIfAbsent(game.creator, game) != null) pay(Bukkit.getOfflinePlayer(game.creator), game.amount, "refund");
            msg(joiner, "cannot-afford", "amount", money().format(game.amount));
            return;
        }
        removeRow(game.creator);

        boolean creatorWins = ThreadLocalRandom.current().nextBoolean();
        OfflinePlayer creator = Bukkit.getOfflinePlayer(game.creator);
        OfflinePlayer winner = creatorWins ? creator : joiner;
        OfflinePlayer loser = creatorWins ? joiner : creator;
        String winnerName = creatorWins ? game.name : joiner.getName();
        String loserName = creatorWins ? joiner.getName() : game.name;
        double tax = Math.max(0, Math.min(100, config().getDouble("tax-percent", 0)));
        double pot = Numbers.round(game.amount * 2 * (1 - tax / 100), decimals(), java.math.RoundingMode.FLOOR); // the tax never rounds away
        pay(winner, pot, "win"); // settled now; the animation only shows it
        record(winner, winnerName, loser, loserName, game.amount);

        Map<String, Object> ph = Map.of("winner", winnerName, "loser", loserName,
                "amount", money().format(game.amount), "pot", money().format(pot));
        // Told once: by the joiner's animation, or by the fallback below if they leave mid-flip.
        AtomicBoolean told = new AtomicBoolean();
        Runnable reveal = () -> {
            if (!told.compareAndSet(false, true)) return;
            for (OfflinePlayer p : List.of(winner, loser)) {
                Player online = p.getPlayer();
                if (online != null) msg(online, p == winner ? "won" : "lost", ph);
            }
            if (game.amount >= config().getDouble("broadcast-minimum", 0)) announce("won-broadcast", ph, null);
        };
        if (!config().getBoolean("animation.enabled", true) || menu("animation") == null) {
            reveal.run();
            return;
        }
        var anim = menu("animation").yml();
        long length = Math.max(1, anim.getInt("steps", 12)) * Math.max(1, anim.getInt("interval-ticks", 6))
                + Math.max(1, anim.getInt("hold-ticks", 30));
        Scheduler.globalLater(reveal, length + Math.max(1, config().getLong("animation.reveal-buffer-ticks", 40))); // one-shot; not tracked so flips never pile up handles
        Scheduler.entity(joiner, () -> animate(joiner, game, winnerName, joiner.getName(), reveal));
        Player creatorOnline = creator.getPlayer();
        // The creator's inventory is read on their own thread (another region on Folia).
        if (creatorOnline != null && config().getBoolean("animation.show-creator", true)) Scheduler.entity(creatorOnline, () -> {
            if (creatorOnline.getOpenInventory().getType() == InventoryType.CRAFTING) {
                animate(creatorOnline, game, winnerName, joiner.getName(), null);
            }
        });
    }

    private void animate(Player viewer, Game game, String winnerName, String joinerName, Runnable after) {
        int steps = Math.max(1, menu("animation").yml().getInt("steps", 12));
        long interval = Math.max(1, menu("animation").yml().getInt("interval-ticks", 6));
        long hold = Math.max(1, menu("animation").yml().getInt("hold-ticks", 30));
        List<String> frames = menu("animation").yml().getStringList("frames");
        int slot = menu("animation").yml().getInt("slot", 13);
        int[] step = {0};
        Menu menu = open(viewer, "animation", m -> {
            boolean done = step[0] >= steps;
            String face = done ? winnerName : step[0] % 2 == 0 ? game.name : joinerName;
            m.with("amount", money().format(game.amount));
            if (done) {
                for (int i = 0; i < m.file().size(); i++) if (i != slot) m.place("reveal", i, Map.of(), null);
            } else if (!frames.isEmpty()) {
                String material = frames.get(step[0] % frames.size());
                for (int i = 0; i < m.file().size(); i++) if (i != slot) m.place("frame", i, Map.of("material", material), null);
            }
            m.place(done ? "winner" : "head", slot, Map.of("player", face), null);
        });
        if (menu == null) {
            if (after != null) after.run();
            return;
        }
        Scheduler.Task[] task = new Scheduler.Task[1];
        task[0] = Scheduler.entityTimer(viewer, () -> {
            step[0]++;
            if (step[0] < steps) {
                // Every step is a tick: only the first unless sounds.ticking is on (config.yml).
                if (step[0] == 1 || com.vexorstudios.vexcore.core.SoundSpec.ticking()) menu.sound("flip");
                menu.refresh();
                return;
            }
            task[0].cancel();
            menu.sound(winnerName.equals(viewer.getName()) ? "win" : "lose");
            menu.refresh();
            Scheduler.entityLater(viewer, () -> {
                if (viewer.getOpenInventory().getTopInventory() == menu.getInventory()) viewer.closeInventory();
                if (after != null) after.run();
            }, hold);
        }, interval, interval);
    }

    private void record(OfflinePlayer winner, String winnerName, OfflinePlayer loser, String loserName, double amount) {
        UUID w = winner.getUniqueId();
        UUID l = loser.getUniqueId();
        stats.computeIfPresent(w, (k, s) -> new Stats(s.wins + 1, s.losses, s.won + amount, s.lost));
        stats.computeIfPresent(l, (k, s) -> new Stats(s.wins, s.losses + 1, s.won, s.lost + amount));
        Database db = db();
        long now = System.currentTimeMillis();
        db.queue("coinflip result", c -> {
            bump(db, c, w.toString(), 1, 0, amount, 0);
            bump(db, c, l.toString(), 0, 1, 0, amount);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("coinflip_results")
                    + " (winner_uuid, winner_name, loser_uuid, loser_name, amount, time) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, w.toString());
                ps.setString(2, winnerName);
                ps.setString(3, l.toString());
                ps.setString(4, loserName);
                ps.setDouble(5, amount);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
        });
    }

    private static void bump(Database db, Connection c, String uuid, int wins, int losses, double won, double lost) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("coinflip_stats")
                + " SET wins = wins + ?, losses = losses + ?, won = won + ?, lost = lost + ? WHERE uuid = ?")) {
            ps.setInt(1, wins);
            ps.setInt(2, losses);
            ps.setDouble(3, won);
            ps.setDouble(4, lost);
            ps.setString(5, uuid);
            if (ps.executeUpdate() == 1) return;
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("coinflip_stats")
                + " (uuid, wins, losses, won, lost) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid);
            ps.setInt(2, wins);
            ps.setInt(3, losses);
            ps.setDouble(4, won);
            ps.setDouble(5, lost);
            ps.executeUpdate();
        }
    }

    // ── Menus ─────────────────────────────────────────────────────────────

    private void openGames(Player player) {
        open(player, "coinflip", menu -> {
            List<Game> list = new ArrayList<>(games.values());
            list.sort(Comparator.comparingDouble(Game::amount).reversed());
            Stats s = stats.getOrDefault(player.getUniqueId(), new Stats(0, 0, 0, 0));
            menu.with("wins", Numbers.format(s.wins)).with("losses", Numbers.format(s.losses)).with("games", Numbers.format(list.size()))
                    .with("won", money().format(s.won)).with("lost", money().format(s.lost))
                    .with("profit", money().format(s.won - s.lost));
            menu.function("refresh", c -> menu.refresh());
            menu.function("create", c -> {
                player.closeInventory();
                msg(player, "create-prompt");
                plugin.chatInput().ask(player, Math.max(5, config().getInt("input-seconds", 30)), text -> create(player, text), null);
            });
            if (games.containsKey(player.getUniqueId())) menu.function("delete", c -> {
                delete(player);
                menu.refresh();
            });
            if (list.isEmpty()) menu.function("empty", c -> {
            });
            menu.paginate(list, (game, slot) -> {
                boolean own = game.creator.equals(player.getUniqueId());
                menu.place(own ? "own-game" : "game", slot, Map.of("player", game.name,
                        "amount", money().format(game.amount), "amount_short", money().shortFormat(game.amount),
                        "age", plugin.messages().time((System.currentTimeMillis() - game.created) / 1000)), c -> {
                    if (own) {
                        delete(player);
                        menu.refresh();
                    } else if (menu("confirm") != null) {
                        confirm(player, game);
                    } else {
                        player.closeInventory();
                        join(player, game);
                    }
                });
            });
        });
    }

    private void confirm(Player player, Game game) {
        open(player, "confirm", menu -> {
            menu.with("player", game.name).with("amount", money().format(game.amount));
            menu.function("confirm", c -> {
                player.closeInventory();
                join(player, game);
            });
            menu.function("cancel", c -> openGames(player));
        });
    }

    private void history(Player player) {
        String uuid = player.getUniqueId().toString();
        Database db = db();
        db.query("coinflip history", c -> {
            List<Result> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT winner_uuid, winner_name, loser_name, amount, time FROM " + db.table("coinflip_results")
                    + " WHERE winner_uuid = ? OR loser_uuid = ? ORDER BY time DESC LIMIT 450")) {
                ps.setString(1, uuid);
                ps.setString(2, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(new Result(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getLong(5)));
                }
            }
            return list;
        }).thenAccept(list -> Scheduler.entity(player, () -> {
            String time = config().getString("history.time-format", "dd.MM.yyyy HH:mm");
            open(player, "history", menu -> {
                if (list.isEmpty()) menu.function("empty", c -> {
                });
                menu.paginate(list, (r, slot) -> {
                    boolean won = r.winnerUuid.equalsIgnoreCase(uuid);
                    menu.place(won ? "won" : "lost", slot, Map.of("opponent", won ? r.loser : r.winner,
                            "amount", money().format(r.amount), "time", com.vexorstudios.vexcore.core.Dates.format(r.time, time)), null);
                });
            });
        }));
    }

    // ── Player data ───────────────────────────────────────────────────────

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Stats s = new Stats(0, 0, 0, 0);
        try (PreparedStatement ps = c.prepareStatement("SELECT wins, losses, won, lost FROM " + db().table("coinflip_stats") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) s = new Stats(rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4));
            }
        }
        stats.put(player, s);
    }

    @Override
    public void unload(UUID player) {
        stats.remove(player);
        lastAction.remove(player);
    }

    /** Hands out payouts that couldn't be delivered while the player was away or frozen. */
    @Override
    protected void loaded(Player player) {
        Database db = db();
        String uuid = player.getUniqueId().toString();
        db.query("coinflip payouts", c -> {
            // Paid for exactly the rows this delete removed: a payout stored in the meantime (another
            // server on the same database) is either removed and paid here, or left for next time;
            // never deleted unpaid.
            java.util.Set<java.util.Map.Entry<Double, String>> kinds = new java.util.LinkedHashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT amount, reason FROM " + db.table("coinflip_payouts") + " WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) kinds.add(Map.entry(rs.getDouble(1), String.valueOf(rs.getString(2))));
                }
            }
            double total = 0;
            if (!kinds.isEmpty()) try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("coinflip_payouts")
                    + " WHERE uuid = ? AND amount = ? AND reason = ?")) {
                for (java.util.Map.Entry<Double, String> k : kinds) {
                    ps.setString(1, uuid);
                    ps.setDouble(2, k.getKey());
                    ps.setString(3, k.getValue());
                    total += k.getKey() * ps.executeUpdate();
                }
            }
            return total;
        }).thenAccept(total -> {
            if (total <= 0) return;
            Scheduler.entity(player, () -> {
                if (money().deposit(player, total)) msg(player, "payout-delivered", "amount", money().format(total));
                else keep(player, total, "pending"); // still can't (frozen): keep it for next time
            }, () -> keep(player, total, "pending")); // left before it ran
        });
    }
}
