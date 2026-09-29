package com.vexorstudios.vexcore.features.leaderboard;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Numbers;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leaderboards from the database: every stat, balances and prestige, refreshed every
 * refresh-seconds. /leaderboard [category]. Placeholders
 * %vexcore_top_&lt;category&gt;_&lt;place&gt;_name% and %vexcore_top_&lt;category&gt;_&lt;place&gt;_value%.
 */
public final class LeaderboardFeature extends Feature {

    record Entry(String name, double value) {
    }

    /** category -> table, column. */
    private static final Map<String, String[]> SOURCES = new LinkedHashMap<>();

    static {
        for (String stat : List.of("kills", "deaths", "best_streak", "mob_kills", "blocks_broken", "blocks_placed", "duel_wins", "duel_losses", "ffa_kills")) {
            SOURCES.put(stat, new String[]{"stats", stat});
        }
        SOURCES.put("balance", new String[]{"balances", "balance"});
        SOURCES.put("prestige", new String[]{"prestige", "level"});
    }

    private final Map<String, List<Entry>> tops = new ConcurrentHashMap<>();

    /** A parsed {@code <category>_<place>_<name|value>}. */
    private record Spot(String category, int place, String what) {
    }

    private static final Spot BAD = new Spot("", 0, "");
    private final Map<String, Spot> spots = new java.util.concurrent.ConcurrentHashMap<>();

    private static Spot spot(String arg) {
        String[] parts = arg.toLowerCase(Locale.ROOT).split("_");
        if (parts.length < 3) return BAD;
        try {
            return new Spot(String.join("_", java.util.Arrays.copyOf(parts, parts.length - 2)),
                    Integer.parseInt(parts[parts.length - 2]), parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return BAD;
        }
    }

    @Override
    protected void enable() {
        command("leaderboard", this::command, (s, a) -> a.length == 1 ? new ArrayList<>(categories()) : List.of());
        placeholder("top", (p, arg) -> {
            Spot spot = spots.computeIfAbsent(arg, LeaderboardFeature::spot); // parsed once per placeholder
            if (spot == BAD) return null;
            String what = spot.what, category = spot.category;
            int place = spot.place;
            List<Entry> list = tops.getOrDefault(category, List.of());
            if (place < 1 || place > list.size()) return what.equals("name") ? config().getString("empty-name", "-") : "0";
            Entry e = list.get(place - 1);
            return what.equals("name") ? e.name : value(category, e.value);
        });
        every(Math.max(30, config().getInt("refresh-seconds", 120)) * 20L, this::refresh);
        refresh();
    }

    private List<String> categories() {
        List<String> out = new ArrayList<>();
        for (String c : SOURCES.keySet()) if (config().getBoolean("categories." + c + ".enabled", true)) out.add(c);
        return out;
    }

    private String value(String category, double v) {
        return category.equals("balance") ? plugin.money().shortFormat(v) : Numbers.format(v);
    }

    private void refresh() {
        int size = Math.max(1, Math.min(100, config().getInt("size", 10)));
        for (String category : categories()) {
            String[] src = SOURCES.get(category);
            String table = db().table(src[0]);
            db().query("leaderboard " + category, c -> {
                List<Entry> list = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT uuid, " + src[1] + " FROM " + table + " ORDER BY " + src[1] + " DESC LIMIT " + size)) {
                    while (rs.next()) {
                        String name;
                        try {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(rs.getString(1)));
                            name = op.getName() == null ? "?" : op.getName();
                        } catch (IllegalArgumentException e) {
                            continue;
                        }
                        list.add(new Entry(name, rs.getDouble(2)));
                    }
                } catch (java.sql.SQLException missingTable) {
                    return List.<Entry>of(); // that feature has never been on
                }
                return list;
            }).thenAccept(list -> tops.put(category, list));
        }
    }

    private void command(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "categories", "categories", String.join(", ", categories()));
            return;
        }
        String category = args[0].toLowerCase(Locale.ROOT);
        if (!categories().contains(category)) {
            msg(sender, "unknown-category", "category", args[0], "categories", String.join(", ", categories()));
            return;
        }
        ConfigurationSection c = config().getConfigurationSection("categories." + category);
        String title = c == null ? category : c.getString("title", category);
        msg(sender, "header", "title", title);
        List<Entry> list = tops.getOrDefault(category, List.of());
        if (list.isEmpty()) msg(sender, "empty");
        for (int i = 0; i < list.size(); i++) {
            msg(sender, "line", "place", i + 1, "player", list.get(i).name, "value", value(category, list.get(i).value));
        }
    }
}
