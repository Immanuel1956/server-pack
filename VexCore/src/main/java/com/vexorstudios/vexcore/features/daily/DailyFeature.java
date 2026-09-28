package com.vexorstudios.vexcore.features.daily;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.gui.Actions;
import com.vexorstudios.vexcore.gui.Slots;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /daily: pick a few hidden slots once per cooldown; each pick rolls a reward from a weighted
 * table (money and/or commands). The claim time is written before anything is handed out.
 */
public final class DailyFeature extends Feature implements PlayerData.Store {

    record Reward(String key, double weight, double min, double max, List<String> commands, String name) {
    }

    private final Map<UUID, Long> lastClaim = new ConcurrentHashMap<>();
    /** Days claimed in a row. Kept going while each claim comes within cooldown + grace of the last. */
    private final Map<UUID, Integer> streaks = new ConcurrentHashMap<>();
    private List<Reward> rewards = List.of();

    @Override
    protected void enable() {
        List<Reward> list = new ArrayList<>();
        ConfigurationSection root = config().getConfigurationSection("rewards");
        if (root != null) for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null || s.getDouble("chance", 0) <= 0) continue;
            double min = Math.max(0, s.getDouble("money.min", 0));
            list.add(new Reward(key, s.getDouble("chance", 0), min, Math.max(min, s.getDouble("money.max", 0)),
                    ownList(s, "commands"), s.getString("name", key)));
        }
        rewards = List.copyOf(list);
        if (rewards.isEmpty()) problems().add("features/daily/config.yml: no rewards with a chance above 0");
        if (menu("daily") != null) {
            int slots = Slots.parse(menu("daily").yml().get("pick-slots")).size();
            if (slots < config().getInt("picks", 3)) problems().add("features/daily/config.yml: picks is "
                    + config().getInt("picks", 3) + " but gui/daily.yml has only " + slots + " pick-slots; players pick " + slots);
        }
        db().schema("daily", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, last_claim BIGINT NOT NULL)");
        db().addColumn("daily", "streak", "INT NOT NULL DEFAULT 0");
        store(this);
        command("daily", this::command, (s, a) -> !s.hasPermission("vexcore.daily.admin") ? List.of() : a.length == 1 ? List.of("reset") : a.length == 2 && a[0].equalsIgnoreCase("reset") ? playerNames(s) : List.of());
        placeholder("daily_streak", (p, a) -> String.valueOf(currentStreak(p.getUniqueId())));
        placeholder("daily", (p, a) -> {
            long left = left(p.getUniqueId());
            return left <= 0 ? config().getString("placeholder-ready", "Ready") : plugin.messages().time(left);
        });
    }

    private long cooldown() {
        return Math.max(1, config().getLong("cooldown-hours", 24)) * 3600L;
    }

    /** Seconds until the next claim (0 or less = ready). */
    private long left(UUID player) {
        Long last = lastClaim.get(player);
        return last == null ? 0 : last / 1000 + cooldown() - System.currentTimeMillis() / 1000;
    }

    // ── Streak ────────────────────────────────────────────────────────────

    private boolean streakOn() {
        return config().getBoolean("streak.enabled", true);
    }

    /** The streak the player has right now: 0 once it ran out. */
    private int currentStreak(UUID player) {
        Long last = lastClaim.get(player);
        int streak = streaks.getOrDefault(player, 0);
        if (last == null || streak <= 0) return 0;
        long window = (cooldown() + Math.max(0, config().getLong("streak.grace-hours", 24)) * 3600L) * 1000L;
        return System.currentTimeMillis() - last <= window ? streak : 0;
    }

    /** The streak this claim makes: one more, or 1 when it ran out. */
    private int nextStreak(UUID player) {
        return currentStreak(player) + 1;
    }

    /** How much bigger the money prizes are on this day of a streak. */
    private double multiplier(int streak) {
        if (!streakOn() || streak <= 1) return 1;
        double per = Math.max(0, config().getDouble("streak.bonus-per-day", 0.10));
        double max = Math.max(1, config().getDouble("streak.max-multiplier", 3.0));
        return Math.min(max, 1 + (streak - 1) * per);
    }

    /** Extra picks on this day of a streak. */
    private int extraPicks(int streak) {
        int every = config().getInt("streak.extra-pick-every-days", 7);
        if (!streakOn() || every <= 0) return 0;
        return Math.min(Math.max(0, config().getInt("streak.max-extra-picks", 2)), streak / every);
    }

    private static String times(double m) {
        return Numbers.full(m, 2, "") + "x";
    }

    private Reward roll() {
        double total = 0;
        for (Reward r : rewards) total += r.weight;
        double pick = ThreadLocalRandom.current().nextDouble(total);
        for (Reward r : rewards) {
            pick -= r.weight;
            if (pick < 0) return r;
        }
        return rewards.getLast();
    }

    private void command(CommandSender sender, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            reset(sender, args);
            return;
        }
        Player player = player(sender);
        if (player == null || !ready(player)) return;
        long left = left(player.getUniqueId());
        if (left > 0 && !player.hasPermission("vexcore.daily.bypass")) {
            msg(player, "cooldown", "time", plugin.messages().time(left));
            return;
        }
        if (rewards.isEmpty()) return;
        openPicker(player);
    }

    private void openPicker(Player player) {
        List<Integer> pickSlots = menu("daily") == null ? List.of() : Slots.parse(menu("daily").yml().get("pick-slots"));
        // Never ask for more picks than there are slots, or the claim could never be confirmed.
        int streak = nextStreak(player.getUniqueId());
        int picks = Math.max(1, Math.min(config().getInt("picks", 3) + extraPicks(streak), Math.max(1, pickSlots.size())));
        Set<Integer> chosen = new LinkedHashSet<>();
        open(player, "daily", menu -> {
            List<Integer> slots = pickSlots;
            menu.with("selected", chosen.size()).with("max", picks)
                    .with("streak", streak).with("multiplier", times(multiplier(streak)))
                    .with("tomorrow", times(multiplier(streak + 1))).with("extra_picks", extraPicks(streak));
            for (int slot : slots) {
                boolean on = chosen.contains(slot);
                menu.place(on ? "selected" : "hidden", slot, Map.of(), c -> {
                    if (on) chosen.remove(slot);
                    else if (chosen.size() >= picks) {
                        msg(player, "already-picked", "max", picks);
                        return;
                    } else chosen.add(slot);
                    menu.sound(on ? "deselect" : "select");
                    menu.refresh();
                });
            }
            menu.function("confirm", c -> {
                if (chosen.size() < picks) {
                    msg(player, "need-more", "missing", picks - chosen.size());
                    return;
                }
                player.closeInventory();
                claim(player, picks);
            });
        });
    }

    private void claim(Player player, int picks) {
        UUID id = player.getUniqueId();
        if (!plugin.data().isLoaded(id)) {
            msg(player, "data-loading");
            return;
        }
        if (left(id) > 0 && !player.hasPermission("vexcore.daily.bypass")) {
            msg(player, "cooldown", "time", plugin.messages().time(left(id)));
            return;
        }
        var network = com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.claim(player, "daily", "reward", cooldown() * 1000);
        if (network != com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.ALLOWED) {
            com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.deny(player, network, "daily");
            return;
        }
        int before = currentStreak(id);
        int lost = streaks.getOrDefault(id, 0); // what they had, if it ran out
        int streak = streakOn() ? before + 1 : 0;
        double multiplier = multiplier(streak);
        long now = System.currentTimeMillis();
        lastClaim.put(id, now);
        streaks.put(id, streak);
        write(id, now, streak); // written before anything is handed out
        if (streakOn()) {
            if (before == 0 && lost > 1) msg(player, "streak-lost", "lost", lost);
            msg(player, streak > 1 ? "streak" : "streak-start", "streak", streak, "multiplier", times(multiplier),
                    "tomorrow", times(multiplier(streak + 1)));
        }
        double money = 0;
        for (int i = 0; i < picks; i++) {
            Reward r = roll();
            double base = r.max > r.min ? ThreadLocalRandom.current().nextDouble(r.min, r.max) : r.min;
            double amount = Numbers.round(base * multiplier, 0);
            money += amount;
            Map<String, Object> ph = Map.of("player", player.getName(), "reward", r.name,
                    "amount", plugin.money().format(amount), "amount_raw", Numbers.full(amount, 2, ""),
                    "streak", streak, "multiplier", times(multiplier));
            Actions.run(player, r.commands, ph, null);
            msg(player, "rolled", ph);
        }
        milestone(player, streak);
        if (money > 0 && !plugin.money().deposit(player, money)) {
            msg(player, "economy-missing");
            return;
        }
        msg(player, "received", "amount", plugin.money().format(money), "streak", streak, "multiplier", times(multiplier));
    }

    /** streak.milestones.<day>: extra commands (and a broadcast) on that day of a streak. */
    private void milestone(Player player, int streak) {
        org.bukkit.configuration.ConfigurationSection s = config().getConfigurationSection("streak.milestones." + streak);
        if (!streakOn() || s == null) return;
        Map<String, Object> ph = Map.of("player", player.getName(), "streak", streak);
        Actions.run(player, ownList(s, "commands"), ph, null);
        String text = s.getString("broadcast", "");
        if (text != null && !text.isBlank()) {
            plugin.messages().broadcast(com.vexorstudios.vexcore.core.Messages.everyone(), text, ph, plugin.messages().prefix(this));
        }
    }

    private void write(UUID player, long time, int streak) {
        Database db = db();
        db.queue("daily", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("daily", new String[]{"uuid"}, "last_claim", "streak"))) {
                ps.setString(1, player.toString());
                ps.setLong(2, time);
                ps.setInt(3, streak);
                ps.executeUpdate();
            }
        });
    }

    private void reset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vexcore.daily.admin")) {
            msg(sender, "no-permission", "permission", "vexcore.daily.admin");
            return;
        }
        if (args.length < 2) {
            usage(sender, "daily");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            msg(sender, "unknown-player", "player", args.length < 2 ? "?" : args[1]);
            return;
        }
        lastClaim.remove(target.getUniqueId());
        streaks.remove(target.getUniqueId());
        write(target.getUniqueId(), 0, 0);
        msg(sender, "reset", "player", target.getName());
    }

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT last_claim, streak FROM " + db().table("daily") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong(1) > 0) {
                    lastClaim.put(player, rs.getLong(1));
                    streaks.put(player, rs.getInt(2));
                } else {
                    lastClaim.remove(player);
                    streaks.remove(player);
                }
            }
        }
    }

    @Override
    public void unload(UUID player) {
        lastClaim.remove(player);
        streaks.remove(player);
    }
}
