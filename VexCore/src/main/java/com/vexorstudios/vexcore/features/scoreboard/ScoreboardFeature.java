package com.vexorstudios.vexcore.features.scoreboard;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sidebar: a title (optionally animated) and up to 15 lines, with every VexCore and
 * PlaceholderAPI placeholder. Players can hide it (/scoreboard, or the settings menu); worlds
 * can be left out. Paper only: Folia has no scoreboard API.
 */
public final class ScoreboardFeature extends Feature implements Listener {

    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    /** What each player's board shows now, so unchanged lines aren't sent again. */
    private final Map<UUID, net.kyori.adventure.text.Component[]> shown = new ConcurrentHashMap<>();
    private int frame;
    // Read once per enable (a reload makes a new instance): the update runs for every player,
    // every update-ticks, and getStringList builds a fresh list on each call.
    private List<String> titles = List.of(), lines = List.of();
    private String title;
    private java.util.Set<String> disabledWorlds = java.util.Set.of();
    private boolean hideNumbers;
    /** Online count for this round of updates (same for everyone). */
    private int onlineNow;

    @Override
    protected void enable() {
        titles = List.copyOf(config().getStringList("title"));
        title = config().getString("title", "&#A66CFF&lVEXCORE");
        lines = List.copyOf(config().getStringList("lines"));
        disabledWorlds = java.util.Set.copyOf(config().getStringList("disabled-worlds"));
        hideNumbers = config().getBoolean("hide-numbers", true);
        if (Scheduler.FOLIA) {
            problems().add("features/scoreboard: Folia has no scoreboard API, so the scoreboard is off on this server");
            return;
        }
        listen(this);
        toggle("scoreboard", true, p -> {
            flip(p, "scoreboard", "toggle-on", "toggle-off");
            update(p);
        });
        command("scoreboard", (sender, label, args) -> {
            Player p = player(sender);
            if (p == null) return;
            flip(p, "scoreboard", "toggle-on", "toggle-off");
            update(p);
        });
        every(Math.max(1, config().getInt("update-ticks", 20)), () -> {
            frame++;
            onlineNow = online();
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        });
    }

    @Override
    protected void disable() {
        if (Scheduler.FOLIA) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (boards.containsKey(p.getUniqueId()) && p.getScoreboard() == boards.get(p.getUniqueId())) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
    }

    private static final String[] ENTRIES = new String[15];

    static {
        for (int i = 0; i < ENTRIES.length; i++) ENTRIES[i] = "vx" + i;
    }

    private boolean shown(Player p) {
        return plugin.data().isLoaded(p.getUniqueId()) && plugin.toggles().isOn(p.getUniqueId(), "scoreboard")
                && !disabledWorlds.contains(p.getWorld().getName());
    }

    private void update(Player p) {
        Scoreboard board = boards.get(p.getUniqueId());
        if (!shown(p)) {
            if (board != null) {
                boards.remove(p.getUniqueId());
                if (p.getScoreboard() == board) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
            return;
        }
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            boards.put(p.getUniqueId(), board);
            shown.remove(p.getUniqueId());
        }
        if (p.getScoreboard() != board) p.setScoreboard(board);
        Objective objective = board.getObjective("vexcore");
        if (objective == null) {
            objective = board.registerNewObjective("vexcore", Criteria.DUMMY, Text.parse(" "));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            if (hideNumbers) objective.numberFormat(NumberFormat.blank());
        }
        String title = titles.isEmpty() ? this.title : titles.get(frame % titles.size());
        int online = onlineNow > 0 ? onlineNow : online(); // a single update (join, toggle) outside the round
        Map<String, Object> ph = Map.of("player", p.getName(), "online", online,
                "world", p.getWorld().getName(), "ping", p.getPing());
        int count = Math.min(15, lines.size());
        net.kyori.adventure.text.Component[] last = shown.computeIfAbsent(p.getUniqueId(), k -> new net.kyori.adventure.text.Component[17]);
        net.kyori.adventure.text.Component head = Text.parse(title, p, ph);
        if (!head.equals(last[16])) {
            objective.displayName(head);
            last[16] = head;
        }
        for (int i = 0; i < 15; i++) {
            String entry = ENTRIES[i];
            if (i >= count) {
                if (last[i] != null) board.resetScores(entry);
                last[i] = null;
                continue;
            }
            net.kyori.adventure.text.Component line = Text.parse(lines.get(i), p, ph);
            if (line.equals(last[i])) continue;
            var score = objective.getScore(entry);
            score.setScore(count - i);
            score.customName(line);
            last[i] = line;
        }
    }

    /** Vanished staff don't count, the same as everywhere else. */
    private int online() {
        return plugin.features().get("vanish") instanceof com.vexorstudios.vexcore.features.vanish.VanishFeature v
                ? v.visibleCount() : Bukkit.getOnlinePlayers().size();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Scheduler.entityLater(p, () -> update(p), Math.max(1, config().getLong("join-delay-ticks", 20)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
        shown.remove(event.getPlayer().getUniqueId());
    }
}
