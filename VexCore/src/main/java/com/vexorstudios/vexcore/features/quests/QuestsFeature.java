package com.vexorstudios.vexcore.features.quests;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Messages;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.gui.Actions;
import com.vexorstudios.vexcore.gui.Slots;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The quest board, ported from LifestealCore's.
 *
 * <p>Every reset.hours a new board is drawn (weight decides how often a quest shows up, the size
 * grows with the players online between board.min and board.max). In STEPS mode nothing is ever
 * wiped: clearing the amount a quest asks for pays out and puts a bigger one in its place
 * (target and reward multiply each step, up to steps.max). In RESET mode a quest is done for the
 * day and its progress goes when the next board rolls. The first player to clear a step of a
 * quest gets its first bonus, decided by the database so it happens once.
 *
 * <p>Every quest has a ranking for this board and for all time (/quests → a quest →
 * leaderboard), and one quest at a time can be tracked in the action bar.
 */
public final class QuestsFeature extends Feature implements PlayerData.Store, Listener {

    record Quest(String key, String type, long target, String difficulty, int weight, String accent, Set<String> filter,
                 double money, double firstBonus, List<String> commands, ConfigurationSection item) {
    }

    /** One player's standing on one quest. */
    static final class State {
        int step = 1;
        long into;      // progress into the current step (RESET: this board)
        long board;     // gained on this board, for the ranking
        double earned;  // money from this quest on this board
        boolean done;   // RESET: done for this board, STEPS: every step cleared
        boolean dirty;
        long cycle;     // the board board/earned belong to; reset on first use after a roll
    }

    record Rank(UUID uuid, String name, long value) {
    }

    private final Map<String, Quest> all = new LinkedHashMap<>();
    private final Map<UUID, Map<String, State>> players = new ConcurrentHashMap<>();
    private final Map<String, List<Rank>> rankings = new ConcurrentHashMap<>();
    private final Map<UUID, String> tracking = new ConcurrentHashMap<>();
    private final Map<String, Long> lastKill = new ConcurrentHashMap<>();
    private final Map<String, UUID> brewers = new ConcurrentHashMap<>();
    private volatile List<Quest> board = List.of();
    /** The board by type, so an event only looks at quests it can count for. */
    private volatile Map<String, List<Quest>> byType = Map.of();
    private volatile long cycle = -1;
    private File boardFile;

    @Override
    protected void enable() {
        stepsMode = !config().getString("mode", "STEPS").equalsIgnoreCase("RESET");
        ignoredModes = java.util.EnumSet.noneOf(org.bukkit.GameMode.class);
        for (String m : config().getStringList("ignored-gamemodes")) {
            try {
                ignoredModes.add(org.bukkit.GameMode.valueOf(m.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                problems().add("features/quests/config.yml: unknown game mode in ignored-gamemodes: " + m);
            }
        }
        maxSteps = Math.max(1, config().getInt("steps.max", 25));
        targetGrowth = config().getDouble("steps.target-multiplier", 1.6);
        rewardGrowth = config().getDouble("steps.reward-multiplier", 1.4);
        YamlConfiguration file = plugin.files().settings("features/quests/quests.yml");
        ConfigurationSection quests = file.getConfigurationSection("quests");
        if (quests != null) for (String key : quests.getKeys(false)) {
            ConfigurationSection q = quests.getConfigurationSection(key);
            if (q == null || !q.getBoolean("enabled", true)) continue;
            String difficulty = q.getString("difficulty", "easy");
            List<String> filter = new ArrayList<>();
            for (String list : List.of("blocks", "mobs", "entities", "items")) for (String f : q.getStringList(list)) filter.add(f.toUpperCase(Locale.ROOT));
            ConfigurationSection rewards = file.getConfigurationSection("rewards." + difficulty);
            all.put(key, new Quest(key, q.getString("type", "BREAK_BLOCK").toUpperCase(Locale.ROOT), Math.max(1, q.getLong("target", 1)),
                    difficulty, Math.max(1, q.getInt("weight", 10)), q.getString("accent", "&f"), Set.copyOf(filter),
                    q.getDouble("money", q.getDouble("reward.money", rewards == null ? 0 : rewards.getDouble("money"))),
                    q.getDouble("first-bonus", q.getDouble("reward.first-bonus", rewards == null ? 0 : rewards.getDouble("first-bonus"))),
                    q.getStringList("commands"), q.getConfigurationSection("item")));
        }
        if (all.isEmpty()) problems().add("features/quests/quests.yml: no quests");
        boardFile = plugin.files().data("questboard.yml");
        db().schema("quest_state", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, quest VARCHAR(64) NOT NULL, "
                + "step INT NOT NULL, into_step BIGINT NOT NULL, done INT NOT NULL, PRIMARY KEY (uuid, quest))");
        db().schema("quest_cycle", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, cycle BIGINT NOT NULL, quest VARCHAR(64) NOT NULL, "
                + "progress BIGINT NOT NULL, earned DOUBLE NOT NULL, into_step BIGINT NOT NULL, done INT NOT NULL, PRIMARY KEY (uuid, cycle, quest))");
        db().schema("quest_first", "CREATE TABLE IF NOT EXISTS {t} (cycle BIGINT NOT NULL, quest VARCHAR(64) NOT NULL, step INT NOT NULL, "
                + "uuid VARCHAR(36) NOT NULL, PRIMARY KEY (cycle, quest, step))");
        store(this);
        listen(this);
        roll(false);
        command("quests", (sender, label, args) -> {
            Player p = player(sender);
            if (p != null && ready(p)) openBoard(p);
        });
        every(Math.max(1, config().getLong("roll-check-seconds", 30)) * 20, () -> roll(true));
        every(20 * 60, () -> { // PLAYTIME counts minutes
            // Only players who moved in the last afk-minutes: standing AFK isn't quest time.
            long afk = config().getLong("playtime-afk-minutes", 5);
            long idle = afk <= 0 ? Long.MIN_VALUE : System.currentTimeMillis() - afk * 60_000L;
            for (Player p : Bukkit.getOnlinePlayers()) {
                Long moved = active.get(p.getUniqueId());
                if (moved != null && moved > idle || idle == Long.MIN_VALUE) Scheduler.entity(p, () -> add(p, "PLAYTIME", null, 1));
            }
        });
        every(Math.max(1, config().getLong("track.interval-ticks", 20)), this::trackBar);
        long interval = Math.max(100, config().getLong("write-interval", 600));
        every(interval, () -> {
            flush();
            refreshRankings();
            long cut = System.currentTimeMillis() - config().getLong("kill-cooldown-minutes", 60) * 60_000;
            lastKill.values().removeIf(t -> t < cut); // pairs past their cooldown count again anyway
        });
        refreshRankings();
        placeholder("quests_done", (p, a) -> String.valueOf(doneCount(p.getUniqueId())));
    }

    @Override
    protected void disable() {
        flush();
    }

    // Read once per enable: add() runs for every block moved, broken or placed.
    private boolean stepsMode;
    private java.util.Set<org.bukkit.GameMode> ignoredModes = java.util.EnumSet.noneOf(org.bukkit.GameMode.class);
    private int maxSteps;
    private double targetGrowth, rewardGrowth;

    private boolean steps() {
        return stepsMode;
    }

    private int maxSteps() {
        return maxSteps;
    }

    long target(Quest q, int step) {
        return stepsMode ? Math.round(q.target * Math.pow(targetGrowth, step - 1)) : q.target;
    }

    double money(Quest q, int step) {
        return stepsMode ? Math.round(q.money * Math.pow(rewardGrowth, step - 1)) : q.money;
    }

    double bonus(Quest q, int step) {
        return stepsMode ? Math.round(q.firstBonus * Math.pow(rewardGrowth, step - 1)) : q.firstBonus;
    }

    // ── The board ─────────────────────────────────────────────────────────

    private long currentCycle() {
        return System.currentTimeMillis() / (Math.max(1, config().getLong("reset.hours", 24)) * 3_600_000L);
    }

    /** Draws a new board when the cycle changed. The board is saved, so a restart keeps it. */
    private synchronized void roll(boolean announce) {
        long now = currentCycle();
        if (now == cycle) return;
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(boardFile);
        List<Quest> picked = new ArrayList<>();
        if (saved.getLong("cycle", -1) == now) {
            for (String key : saved.getStringList("quests")) if (all.containsKey(key)) picked.add(all.get(key));
        }
        if (picked.isEmpty()) {
            int online = Bukkit.getOnlinePlayers().size();
            int min = config().getInt("board.min", 28), max = Math.max(min, config().getInt("board.max", 28));
            int size = (int) Math.max(min, Math.min(max, Math.round(min + online * config().getDouble("board.per-player", 0))));
            List<Quest> pool = new ArrayList<>(all.values());
            Random random = new Random(now * 7919L);
            while (picked.size() < size && !pool.isEmpty()) {
                int r = random.nextInt(pool.stream().mapToInt(Quest::weight).sum());
                for (Quest q : pool) {
                    r -= q.weight;
                    if (r < 0) {
                        picked.add(q);
                        pool.remove(q);
                        break;
                    }
                }
            }
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("cycle", now);
            yml.set("quests", picked.stream().map(Quest::key).toList());
            try {
                yml.save(boardFile);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save " + boardFile + ": " + e.getMessage());
            }
        }
        boolean changed = cycle != -1;
        if (changed) flush(); // what was earned so far still belongs to the old board
        // Every state resets itself the first time it's used on the new board (fresh()), under
        // its own lock, so progress made on another thread during the roll is never wiped.
        cycle = now;
        board = List.copyOf(picked);
        Map<String, List<Quest>> index = new HashMap<>();
        for (Quest q : board) index.computeIfAbsent(q.type, k -> new ArrayList<>()).add(q);
        byType = index;
        rankings.clear();
        int keep = config().getInt("reset.keep-cycles", 7);
        if (keep > 0) {
            Database db = db();
            db.queue("prune quests", c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("quest_cycle") + " WHERE cycle < ?")) {
                    ps.setLong(1, now - keep);
                    ps.executeUpdate();
                }
            });
        }
        if (announce && changed && config().getBoolean("reset.announce", true)) broadcast(Messages.everyone(), "reset", Map.of("amount", board.size()));
    }

    private State state(UUID player, String quest) {
        Map<String, State> states = players.get(player);
        if (states == null) return null;
        State s = states.computeIfAbsent(quest, k -> new State());
        synchronized (s) {
            fresh(s);
        }
        return s;
    }

    /** Holding the state's lock: starts it on the current board if it still shows an old one. */
    private void fresh(State s) {
        long now = cycle;
        if (s.cycle == now) return;
        s.cycle = now;
        s.board = 0;
        s.earned = 0;
        if (!steps()) {
            s.into = 0;
            s.done = false;
        }
    }

    private int doneCount(UUID player) {
        Map<String, State> states = players.get(player);
        if (states == null) return 0;
        int n = 0;
        for (Quest q : board) {
            State s = states.get(q.key);
            if (s != null && s.cycle == cycle && (s.done || (steps() && s.board >= target(q, 1)))) n++;
        }
        return n;
    }

    // ── Progress ──────────────────────────────────────────────────────────

    /** Counts toward every quest on the board of this type. Other features call this too (SELL_ITEMS). */
    public void add(Player player, String type, String what, long amount) {
        List<Quest> candidates = byType.get(type);
        if (amount <= 0 || candidates == null) return;
        // Creative and spectator can make anything: no quest money for that (ignored-gamemodes).
        if (ignoredModes.contains(player.getGameMode())) return;
        Map<String, State> states = players.get(player.getUniqueId());
        if (states == null) return;
        for (Quest q : candidates) {
            if (!q.filter.isEmpty() && (what == null || !q.filter.contains(what))) continue;
            State s = states.computeIfAbsent(q.key, k -> new State());
            List<Integer> cleared = null; // almost always stays empty: no list per event
            synchronized (s) {
                fresh(s);
                if (s.done) continue;
                s.board += amount;
                s.into += amount;
                s.dirty = true;
                while (!s.done && s.into >= target(q, s.step)) {
                    if (cleared == null) cleared = new ArrayList<>(1);
                    cleared.add(s.step);
                    s.earned += money(q, s.step);
                    if (steps()) {
                        s.into -= target(q, s.step);
                        if (s.step >= maxSteps()) s.done = true;
                        else s.step++;
                    } else {
                        s.into = target(q, s.step);
                        s.done = true;
                    }
                }
            }
            if (cleared != null) for (int step : cleared) cleared(player, q, step);
        }
    }

    private Map<String, Object> placeholders(Quest q) {
        Map<String, Object> ph = new HashMap<>();
        String name = q.item == null ? q.key : q.item.getString("name", q.key);
        ph.put("quest", name);
        ph.put("accent", q.accent);
        ph.put("emoji", emoji(name));
        ph.put("name", name);
        return ph;
    }

    /** The symbol at the start of a quest name ("#4FD8FF☄ &lDIAMOND RUSH" -> "☄"). */
    private static String emoji(String name) {
        String plain = Text.plain(Text.parse(name)).strip();
        if (plain.isEmpty() || Character.isLetterOrDigit(plain.codePointAt(0))) return "";
        return new String(Character.toChars(plain.codePointAt(0)));
    }

    /** A step was cleared: saved at once, then paid; the first bonus is decided by the database. */
    private void cleared(Player player, Quest q, int step) {
        Database.Work save = save(player.getUniqueId());
        Database db = db();
        String uuid = player.getUniqueId().toString();
        long c = cycle;
        db.query("quest cleared", conn -> {
            if (save != null) save.run(conn);
            try (PreparedStatement ps = conn.prepareStatement(db.insertIgnore("quest_first", "cycle", "quest", "step", "uuid"))) {
                ps.setLong(1, c);
                ps.setString(2, q.key);
                ps.setInt(3, step);
                ps.setString(4, uuid);
                return ps.executeUpdate() == 1;
            }
        }).whenComplete((result, error) -> {
            if (error != null) plugin.getLogger().log(java.util.logging.Level.WARNING, "Quest step of " + player.getName() + " was not saved", error);
            boolean first = error == null && result;
            double pay = money(q, step), extra = first ? bonus(q, step) : 0;
            Scheduler.entity(player, () -> {
                Map<String, Object> ph = placeholders(q);
                ph.put("player", player.getName());
                ph.put("step", step);
                ph.put("money", Numbers.full(pay, 0, ","));
                if (pay > 0) plugin.money().deposit(player, pay);
                Actions.run(player, q.commands, ph, null);
                msg(player, "completed", ph);
                if (first) {
                    if (extra > 0) plugin.money().deposit(player, extra);
                    ph.put("bonus", Numbers.full(extra, 0, ","));
                    broadcast(Messages.everyone(), "first", ph);
                }
            }, () -> {
                // Left before the reward ran: the money still arrives (commands need them online).
                if (pay + extra > 0 && !plugin.money().deposit(Bukkit.getOfflinePlayer(player.getUniqueId()), pay + extra)) {
                    plugin.getLogger().severe("Quest reward of " + plugin.money().format(pay + extra) + " for " + player.getName() + " could not be paid.");
                }
            });
        });
    }

    // ── Rankings ──────────────────────────────────────────────────────────

    private void refreshRankings() {
        Database db = db();
        long c = cycle;
        List<String> keys = board.stream().map(Quest::key).toList();
        int shown = Math.max(10, config().getInt("leaderboard-cache", 45));
        db.query("quest rankings", conn -> {
            Map<String, List<Rank>> out = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT quest, uuid, progress FROM " + db.table("quest_cycle")
                    + " WHERE cycle = ? AND progress > 0 ORDER BY progress DESC")) {
                ps.setLong(1, c);
                try (ResultSet rs = ps.executeQuery()) {
                    Set<String> wanted = new HashSet<>(keys);
                    while (rs.next()) {
                        String quest = rs.getString(1);
                        if (!wanted.contains(quest)) continue;
                        List<Rank> list = out.computeIfAbsent(quest, k -> new ArrayList<>());
                        UUID u = UUID.fromString(rs.getString(2));
                        // Names only for the part anyone sees; the rest only need their place.
                        list.add(new Rank(u, list.size() < shown ? name(u) : null, rs.getLong(3)));
                    }
                }
            }
            return out;
        }).thenAccept(out -> {
            rankings.clear();
            rankings.putAll(out);
        });
    }

    private static String name(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() == null ? uuid.toString().substring(0, 8) : op.getName();
    }

    private int rank(UUID player, List<Rank> list) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).uuid.equals(player)) return i + 1;
        return 0;
    }

    // ── Tracking ──────────────────────────────────────────────────────────

    private void trackBar() {
        for (Map.Entry<UUID, String> e : tracking.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            Quest q = all.get(e.getValue());
            if (p == null || q == null || !board.contains(q)) {
                tracking.remove(e.getKey());
                continue;
            }
            State s = state(p.getUniqueId(), q.key);
            if (s == null) continue;
            Map<String, Object> ph = placeholders(q);
            ph.put("progress", Numbers.full(s.into, 0, ","));
            ph.put("target", Numbers.full(target(q, s.step), 0, ","));
            ph.put("step", s.step);
            Scheduler.entity(p, () -> p.sendActionBar(Text.parse(config().getString("track.format",
                    "%accent%%quest% &8▷ &f%progress%&8/&f%target%"), p, ph)));
        }
    }

    // ── Menus ─────────────────────────────────────────────────────────────

    private Map<String, Object> questPlaceholders(Player p, Quest q) {
        State s = state(p.getUniqueId(), q.key);
        int step = s == null ? 1 : s.step;
        long into = s == null ? 0 : s.into, target = target(q, step);
        List<Rank> list = rankings.getOrDefault(q.key, List.of());
        Map<String, Object> ph = placeholders(q);
        int rank = rank(p.getUniqueId(), list);
        ph.put("progress", Numbers.full(into, 0, ","));
        ph.put("target", Numbers.full(target, 0, ","));
        ph.put("percent", Math.min(100, into * 100 / Math.max(1, target)) + "%");
        ph.put("rank", rank == 0 ? config().getString("empty-rank", "-") : String.valueOf(rank));
        ph.put("players", list.size());
        ph.put("step", step);
        ph.put("money", Numbers.full(money(q, step), 0, ","));
        ph.put("first_bonus", Numbers.full(bonus(q, step), 0, ","));
        ph.put("difficulty", q.difficulty);
        ph.put("started", plugin.messages().time((System.currentTimeMillis() - cycle * Math.max(1, config().getLong("reset.hours", 24)) * 3_600_000L) / 1000));
        boolean done = s != null && s.done;
        ph.put("status", config().getString(done ? "status.done" : "status.open", done ? "Done" : "Open"));
        int top = Math.max(3, config().getInt("leaderboard-cache", 10));
        for (int i = 1; i <= top; i++) {
            Rank r = i <= list.size() ? list.get(i - 1) : null;
            ph.put("top_" + i + "_name", r == null ? config().getString("empty-name", "---") : r.name);
            ph.put("top_" + i + "_value", r == null ? config().getString("empty-value", "0") : Numbers.full(r.value, 0, ","));
        }
        ph.put("material", q.item == null ? "PAPER" : q.item.getString("material", "PAPER"));
        return ph;
    }

    /** The quest's own lore from quests.yml, with its placeholders. */
    private String lore(Quest q, List<String> fallback) {
        List<String> lines = q.item == null ? fallback : q.item.getStringList("lore");
        return String.join("\n", lines.isEmpty() ? fallback : lines);
    }

    private void openBoard(Player p) {
        open(p, "board", menu -> {
            List<Integer> slots = Slots.parse(menu.file().yml().get("quest-slots"));
            double earned = 0;
            Map<String, State> states = players.getOrDefault(p.getUniqueId(), Map.of());
            for (State s : states.values()) earned += s.earned;
            long next = (cycle + 1) * Math.max(1, config().getLong("reset.hours", 24)) * 3_600_000L;
            menu.with("done", doneCount(p.getUniqueId())).with("active", board.size()).with("earned", Numbers.full(earned, 0, ","))
                    .with("reset", plugin.messages().time(Math.max(0, (next - System.currentTimeMillis()) / 1000)));
            for (int i = 0; i < Math.min(slots.size(), board.size()); i++) {
                Quest q = board.get(i);
                State s = state(p.getUniqueId(), q.key);
                Map<String, Object> ph = questPlaceholders(p, q);
                ph.put("lore", lore(q, List.of()));
                menu.place(s != null && s.done ? "completed" : "quest", slots.get(i), ph, c -> openDetail(p, q));
            }
        });
    }

    private void openDetail(Player p, Quest q) {
        open(p, "detail", menu -> {
            Map<String, Object> ph = questPlaceholders(p, q);
            ph.forEach(menu::with);
            menu.function("leaderboard", c -> openLeaderboard(p, q, false));
            menu.function("track", c -> {
                if (q.key.equals(tracking.get(p.getUniqueId()))) {
                    tracking.remove(p.getUniqueId());
                    msg(p, "track-off", ph);
                } else {
                    tracking.put(p.getUniqueId(), q.key);
                    msg(p, "track-on", ph);
                }
                p.closeInventory();
            });
            menu.function("back", c -> openBoard(p));
        });
    }

    private void openLeaderboard(Player p, Quest q, boolean allTime) {
        Database db = db();
        long c = cycle;
        db.query("quest leaderboard", conn -> {
            List<Rank> out = new ArrayList<>();
            String sql = allTime
                    ? "SELECT uuid, SUM(progress) FROM " + db.table("quest_cycle") + " WHERE quest = ? GROUP BY uuid ORDER BY 2 DESC LIMIT 450"
                    : "SELECT uuid, progress FROM " + db.table("quest_cycle") + " WHERE quest = ? AND cycle = ? AND progress > 0 ORDER BY 2 DESC LIMIT 450";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, q.key);
                if (!allTime) ps.setLong(2, c);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID u = UUID.fromString(rs.getString(1));
                        out.add(new Rank(u, name(u), rs.getLong(2)));
                    }
                }
            }
            return out;
        }).thenAccept(list -> Scheduler.entity(p, () -> open(p, "leaderboard", menu -> {
            Map<String, Object> ph = questPlaceholders(p, q);
            ph.forEach(menu::with);
            String active = menu.file().yml().getString("words.active", "&#80EE0B"), inactive = menu.file().yml().getString("words.inactive", "&8");
            menu.with("filter", menu.file().yml().getString(allTime ? "words.all-time" : "words.board", allTime ? "All-Time" : "This Board"))
                    .with("filter_board", allTime ? inactive : active).with("filter_alltime", allTime ? active : inactive);
            int mine = rank(p.getUniqueId(), list);
            menu.with("rank", mine == 0 ? config().getString("empty-rank", "-") : String.valueOf(mine))
                    .with("value", mine == 0 ? "0" : Numbers.full(list.get(mine - 1).value, 0, ","));
            menu.function("filter", x -> openLeaderboard(p, q, !allTime));
            menu.function("back", x -> openDetail(p, q));
            menu.paginate(list, (r, slot) -> {
                Map<String, Object> e = new HashMap<>();
                e.put("name", r.name);
                e.put("rank", list.indexOf(r) + 1);
                e.put("value", Numbers.full(r.value, 0, ","));
                State s = state(r.uuid, q.key);
                boolean done = s != null && s.done;
                e.put("status", config().getString(done ? "status.done" : "status.open", done ? "Done" : "Open"));
                menu.place("entry", slot, e, null);
            });
        })));
    }

    // ── Events ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        String type = b.getType().name();
        if (b.getBlockData() instanceof Ageable age && age.getAge() >= age.getMaximumAge()) add(e.getPlayer(), "HARVEST_CROP", type, 1);
        // A block a player put there doesn't count as mined, and no longer counts as placed
        // either (place, break, repeat); its drops don't count as picked up.
        At key = placed.isEmpty() ? null : placedKey(b); // no key built while nobody placed anything
        UUID placer = key == null ? null : placed.remove(key);
        if (placer != null) {
            Player who = Bukkit.getPlayer(placer);
            if (who != null) take(who, "PLACE_BLOCK", type);
            if (selfDrops.size() >= 10_000) selfDrops.clear();
            selfDrops.add(key);
            return;
        }
        add(e.getPlayer(), "BREAK_BLOCK", type, 1);
        if (type.endsWith("_ORE") || type.equals("ANCIENT_DEBRIS")) add(e.getPlayer(), "MINE_ORE", type, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        add(e.getPlayer(), "PLACE_BLOCK", e.getBlockPlaced().getType().name(), 1);
        if (byType.containsKey("BREAK_BLOCK") || byType.containsKey("MINE_ORE") || byType.containsKey("PLACE_BLOCK")) {
            // ponytail: in memory, so a restart forgets; per-chunk data only if restart farming shows up.
            if (placed.size() >= 500_000) placed.clear();
            placed.put(placedKey(e.getBlockPlaced()), e.getPlayer().getUniqueId());
        }
    }

    /** When each player last moved a block (PLAYTIME skips idle players). */
    private final Map<UUID, Long> active = new ConcurrentHashMap<>();

    /**
     * Blocks players placed (world + position -> who), so breaking them again isn't progress. A
     * world id and a packed position instead of text: a fraction of the memory at 500k blocks,
     * and nothing to build on every block event.
     */
    private record At(UUID world, long pos) {
    }

    private final Map<At, UUID> placed = new ConcurrentHashMap<>();
    /** Self-placed blocks just broken, and the item entities they dropped: not pickup progress. */
    private final java.util.Set<At> selfDrops = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> noPickup = ConcurrentHashMap.newKeySet();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrops(org.bukkit.event.block.BlockDropItemEvent e) {
        if (selfDrops.isEmpty() || !selfDrops.remove(placedKey(e.getBlock()))) return;
        if (noPickup.size() >= 10_000) noPickup.clear();
        for (org.bukkit.entity.Item item : e.getItems()) noPickup.add(item.getUniqueId());
    }

    /** Takes one back from this player's progress on quests of this type (a placed block broken again). */
    private void take(Player player, String type, String what) {
        List<Quest> candidates = byType.get(type);
        Map<String, State> states = candidates == null ? null : players.get(player.getUniqueId());
        if (states == null) return;
        for (Quest q : candidates) {
            if (!q.filter.isEmpty() && (what == null || !q.filter.contains(what))) continue;
            State s = states.get(q.key);
            if (s == null) continue;
            synchronized (s) {
                fresh(s);
                if (s.done || s.into <= 0) continue; // a cleared step stays cleared
                s.into--;
                s.board = Math.max(0, s.board - 1);
                s.dirty = true;
            }
        }
    }

    private static At placedKey(Block b) {
        return new At(b.getWorld().getUID(), b.getBlockKey());
    }

    // Placed blocks keep their mark when they move (pistons, sand falling) and lose it when
    // something other than a player removes them (explosions, a torch losing its wall).

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonOut(org.bukkit.event.block.BlockPistonExtendEvent e) {
        movePlaced(e.getBlock(), e.getBlocks(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonIn(org.bukkit.event.block.BlockPistonRetractEvent e) {
        movePlaced(e.getBlock(), e.getBlocks(), true);
    }

    private void movePlaced(Block piston, List<Block> blocks, boolean retract) {
        if (placed.isEmpty() || blocks.isEmpty() || !(piston.getBlockData() instanceof org.bukkit.block.data.Directional d)) return;
        org.bukkit.block.BlockFace way = retract ? d.getFacing().getOppositeFace() : d.getFacing();
        Map<Block, UUID> moved = new HashMap<>();
        for (Block b : blocks) {
            UUID who = placed.remove(placedKey(b));
            if (who != null) moved.put(b.getRelative(way), who);
        }
        moved.forEach((b, who) -> placed.put(placedKey(b), who));
    }

    /** Placed sand/gravel that starts falling: who placed it, until it lands. */
    private final Map<UUID, UUID> falling = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFall(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if (placed.isEmpty() && falling.isEmpty() || !(e.getEntity() instanceof org.bukkit.entity.FallingBlock)) return;
        if (e.getTo().isAir()) { // starts falling
            UUID who = placed.remove(placedKey(e.getBlock()));
            if (who != null) {
                if (falling.size() >= 10_000) falling.clear();
                falling.put(e.getEntity().getUniqueId(), who);
            }
        } else { // lands
            UUID who = falling.remove(e.getEntity().getUniqueId());
            if (who != null) placed.put(placedKey(e.getBlock()), who);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPop(com.destroystokyo.paper.event.block.BlockDestroyEvent e) {
        if (!placed.isEmpty()) placed.remove(placedKey(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlast(org.bukkit.event.entity.EntityExplodeEvent e) {
        if (!placed.isEmpty()) for (Block b : e.blockList()) placed.remove(placedKey(b));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlast(org.bukkit.event.block.BlockExplodeEvent e) {
        if (!placed.isEmpty()) for (Block b : e.blockList()) placed.remove(placedKey(b));
    }

    /**
     * Items a dropper or dispenser spits out don't count as picked up (put in, pick up, repeat).
     * The item spawns right after the event on the same thread, next to the block.
     */
    private final ThreadLocal<org.bukkit.Location> dispensed = new ThreadLocal<>();
    private final ThreadLocal<Integer> dispensedTick = new ThreadLocal<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(org.bukkit.event.block.BlockDispenseEvent e) {
        if (!byType.containsKey("PICKUP_ITEM")) return;
        dispensed.set(e.getBlock().getLocation());
        dispensedTick.set(Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(org.bukkit.event.entity.ItemSpawnEvent e) {
        org.bukkit.Location from = dispensed.get();
        if (from == null) return;
        Integer tick = dispensedTick.get();
        dispensed.remove();
        dispensedTick.remove();
        org.bukkit.Location at = e.getEntity().getLocation();
        if (tick == null || tick != Bukkit.getCurrentTick() || at.getWorld() != from.getWorld() || at.distanceSquared(from) > 9) return;
        if (noPickup.size() >= 10_000) noPickup.clear();
        noPickup.add(e.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        if (e.getEntity() instanceof Player victim) {
            if (victim.equals(killer)) return;
            // The same victim counts again only after kill-cooldown-minutes (no alt farming).
            String key = killer.getUniqueId() + ":" + victim.getUniqueId();
            long cooldown = config().getLong("kill-cooldown-minutes", 60) * 60_000;
            Long last = lastKill.get(key);
            if (cooldown > 0 && last != null && System.currentTimeMillis() - last < cooldown) return;
            lastKill.put(key, System.currentTimeMillis());
            add(killer, "KILL_PLAYER", null, 1);
        } else {
            // Mobs from spawn eggs or commands can be made on demand: no quest money for those.
            var reason = e.getEntity().getEntitySpawnReason();
            if (reason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                    || reason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DISPENSE_EGG
                    || reason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.COMMAND) return;
            add(killer, "KILL_MOB", e.getEntityType().name(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        add(e.getEntity(), "DEATHS", null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            add(e.getPlayer(), "FISH", e.getCaught() instanceof org.bukkit.entity.Item i ? i.getItemStack().getType().name() : null, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || e.getRecipe() == null) return;
        // A click that crafts nothing (full cursor, wrong item held) counts nothing.
        if (e.getAction() == org.bukkit.event.inventory.InventoryAction.NOTHING) return;
        org.bukkit.inventory.ItemStack result = e.getRecipe().getResult();
        String type = result.getType().name();
        // A number key onto a taken hotbar slot crafts nothing.
        if (e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
            var slot = p.getInventory().getItem(e.getHotbarButton());
            if (slot != null && !slot.getType().isAir()) return;
        }
        if (!e.isShiftClick()) {
            add(p, "CRAFT_ITEM", type, result.getAmount());
            return;
        }
        // Shift-click: count what really landed in the inventory (a full inventory takes less),
        // measured one tick later. Several shift-clicks in one tick share one count.
        String key = p.getUniqueId() + ":" + type;
        if (crafting.putIfAbsent(key, count(p, result.getType())) != null) return;
        Scheduler.entityLater(p, () -> {
            Integer before = crafting.remove(key);
            int made = before == null ? 0 : count(p, result.getType()) - before;
            if (made > 0) add(p, "CRAFT_ITEM", type, made);
        }, () -> crafting.remove(key), 1);
    }

    /** Shift-crafts waiting for their one-tick count: player:item -> how many they had before. */
    private final Map<String, Integer> crafting = new ConcurrentHashMap<>();

    private static int count(Player p, org.bukkit.Material type) {
        int n = 0;
        for (var item : p.getInventory().getStorageContents()) if (item != null && item.getType() == type) n += item.getAmount();
        return n;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent e) {
        add(e.getPlayer(), "SMELT_ITEM", e.getItemType().name(), e.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        add(e.getPlayer(), "CONSUME_ITEM", e.getItem().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        // Items a player dropped don't count (drop, pick up, repeat).
        if (noPickup.remove(e.getItem().getUniqueId())) return;
        if (e.getEntity() instanceof Player p && e.getItem().getThrower() == null) {
            add(p, "PICKUP_ITEM", e.getItem().getItemStack().getType().name(), e.getItem().getItemStack().getAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        add(e.getEnchanter(), "ENCHANT_ITEM", e.getItem().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (e.getBreeder() instanceof Player p) add(p, "BREED_ANIMAL", e.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent e) {
        if (e.getOwner() instanceof Player p) add(p, "TAME_ANIMAL", e.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        add(e.getPlayer(), "SHEAR_SHEEP", e.getEntity().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExp(PlayerExpChangeEvent e) {
        add(e.getPlayer(), "GAIN_EXPERIENCE", null, e.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        double hearts = e.getFinalDamage() / 2; // the quests count hearts
        Entity d = e.getDamager();
        Player attacker = d instanceof Player p ? p : d instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        if (attacker != null) add(attacker, "DAMAGE_DEALT", null, hearts(attacker.getUniqueId() + "d", hearts));
        if (e.getEntity() instanceof Player victim) add(victim, "DAMAGE_TAKEN", null, hearts(victim.getUniqueId() + "t", hearts));
    }

    private final Map<String, Double> heartRest = new ConcurrentHashMap<>();

    /** Whole hearts to count now; the rest is kept for the next hit. */
    private long hearts(String key, double hearts) {
        double total = heartRest.getOrDefault(key, 0.0) + hearts;
        long whole = (long) total;
        heartRest.put(key, total - whole);
        return whole;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        add(e.getPlayer(), "WALK_DISTANCE", null, 1);
        if (byType.containsKey("PLAYTIME")) active.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(io.papermc.paper.event.player.PlayerTradeEvent e) {
        add(e.getPlayer(), "TRADE_VILLAGER", e.getTrade().getResult().getType().name(), 1);
    }

    /** Brewing has no player: the last one who opened the stand gets it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() == InventoryType.BREWING && e.getInventory().getLocation() != null && e.getPlayer() instanceof Player p) {
            if (brewers.size() >= 10_000) brewers.clear(); // one entry per stand ever opened otherwise
            brewers.put(key(e.getInventory().getLocation()), p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        UUID who = brewers.get(key(e.getBlock().getLocation()));
        Player p = who == null ? null : Bukkit.getPlayer(who);
        if (p == null) return;
        int potions = 0;
        for (var item : e.getResults()) if (item != null && !item.isEmpty()) potions++;
        int count = potions;
        Scheduler.entity(p, () -> add(p, "BREW_POTION", null, count));
    }

    private static String key(org.bukkit.Location l) {
        return l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    // ── Player data ───────────────────────────────────────────────────────

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Map<String, State> states = new ConcurrentHashMap<>();
        boolean steps = steps();
        long loadedCycle = cycle;
        if (steps) try (PreparedStatement ps = c.prepareStatement("SELECT quest, step, into_step, done FROM " + db().table("quest_state") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    State s = new State();
                    s.cycle = loadedCycle;
                    s.step = Math.max(1, rs.getInt(2));
                    s.into = rs.getLong(3);
                    s.done = rs.getInt(4) != 0;
                    states.put(rs.getString(1), s);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT quest, progress, earned, into_step, done FROM " + db().table("quest_cycle") + " WHERE uuid = ? AND cycle = ?")) {
            ps.setString(1, player.toString());
            ps.setLong(2, loadedCycle);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    State s = states.computeIfAbsent(rs.getString(1), k -> new State());
                    s.cycle = loadedCycle;
                    s.board = rs.getLong(2);
                    s.earned = rs.getDouble(3);
                    if (!steps) {
                        s.into = rs.getLong(4);
                        s.done = rs.getInt(5) != 0;
                    }
                }
            }
        }
        players.put(player, states);
    }

    @Override
    public Database.Work save(UUID player) {
        Map<String, State> states = players.get(player);
        if (states == null) return null;
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, State> e : states.entrySet()) {
            State s = e.getValue();
            synchronized (s) {
                if (!s.dirty) continue;
                s.dirty = false;
                rows.add(new Object[]{e.getKey(), s.step, s.into, s.done ? 1 : 0, s.board, s.earned, s.cycle});
            }
        }
        if (rows.isEmpty()) return null;
        Database db = db();
        String uuid = player.toString();
        return conn -> {
            try (PreparedStatement st = conn.prepareStatement(db.upsert("quest_state", new String[]{"uuid", "quest"}, "step", "into_step", "done"));
                 PreparedStatement cy = conn.prepareStatement(db.upsert("quest_cycle", new String[]{"uuid", "cycle", "quest"}, "progress", "earned", "into_step", "done"))) {
                for (Object[] r : rows) {
                    st.setString(1, uuid);
                    st.setString(2, (String) r[0]);
                    st.setInt(3, (Integer) r[1]);
                    st.setLong(4, (Long) r[2]);
                    st.setInt(5, (Integer) r[3]);
                    st.addBatch();
                    cy.setString(1, uuid);
                    cy.setLong(2, (Long) r[6]);
                    cy.setString(3, (String) r[0]);
                    cy.setLong(4, (Long) r[4]);
                    cy.setDouble(5, (Double) r[5]);
                    cy.setLong(6, (Long) r[2]);
                    cy.setInt(7, (Integer) r[3]);
                    cy.addBatch();
                }
                st.executeBatch();
                cy.executeBatch();
            }
        };
    }

    private void flush() {
        for (UUID id : players.keySet()) {
            Database.Work w = save(id);
            if (w != null) db().queue("quests", w);
        }
    }

    @Override
    public void unload(UUID player) {
        players.remove(player);
        active.remove(player);
        tracking.remove(player);
        heartRest.remove(player + "d");
        heartRest.remove(player + "t");
    }
}
