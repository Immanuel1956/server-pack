package com.vexorstudios.vexcore.features.keyall;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Time;
import com.vexorstudios.vexcore.gui.Actions;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Key-all: every {@code interval} everyone online gets a reward rolled from a weighted table.
 * Rewards are command lines, so any crate plugin works. The next time is kept in
 * data/keyall.yml, so a restart doesn't reset the timer.
 */
public final class KeyallFeature extends Feature {

    record Reward(String key, String name, double weight, List<String> commands) {
    }

    private List<Reward> rewards = List.of();
    private volatile long next;
    private File file;

    @Override
    protected void enable() {
        // Parsed once: the timer checks them every second.
        warnings = config().getStringList("warnings").stream().mapToLong(Time::seconds).filter(at -> at > 0).toArray();
        List<Reward> list = new ArrayList<>();
        ConfigurationSection root = config().getConfigurationSection("rewards");
        if (root != null) for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null || s.getDouble("chance", 0) <= 0) continue;
            list.add(new Reward(key, s.getString("name", key), s.getDouble("chance", 0), ownList(s, "commands")));
        }
        rewards = List.copyOf(list);
        if (interval() <= 0) problems().add(com.vexorstudios.vexcore.core.Files.configPath(id()) + ": interval '" + config().getString("interval") + "' is not a duration");
        if (rewards.isEmpty()) problems().add(com.vexorstudios.vexcore.core.Files.configPath(id()) + ": no rewards with a chance above 0");
        file = plugin.files().data("keyall.yml");
        next = YamlConfiguration.loadConfiguration(file).getLong("next", 0);
        if (next <= 0) schedule();
        command("keyall", this::command, (s, a) -> a.length == 1 && s.hasPermission("vexcore.keyall.admin") ? List.of("force", "set") : List.of());
        every(20, this::tick);
        placeholder("keyall", (p, a) -> placeholder(a));
    }

    /**
     * %vexcore_keyall% (time left, "42m 10s") and its forms: _countdown ("42:10"), _seconds,
     * _minutes (rounded up), _at (the clock time of the next one, placeholder.time-format) and
     * _interval. Anything else after keyall_ answers nothing, so a typo shows up as-is.
     */
    private String placeholder(String form) {
        long left = left();
        return switch (form.toLowerCase(java.util.Locale.ROOT)) {
            case "" -> plugin.messages().time(left);
            case "countdown", "clock" -> countdown(left);
            case "seconds" -> String.valueOf(left);
            case "minutes" -> String.valueOf((left + 59) / 60);
            case "at", "time" -> com.vexorstudios.vexcore.core.Dates.format(next,
                    config().getString("placeholder.time-format", "HH:mm"), config().getString("placeholder.time-zone", ""));
            case "interval" -> plugin.messages().time(Math.max(0, interval()));
            default -> null;
        };
    }

    /** 42:10, or 1:02:03 from an hour up. */
    public static String countdown(long seconds) {
        long s = Math.max(0, seconds);
        long h = s / 3600, m = s % 3600 / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }

    @Override
    protected void disable() {
        hideBar();
    }

    private long interval() {
        return Time.seconds(config().getString("interval", "1h"));
    }

    private long left() {
        return Math.max(0, (next - System.currentTimeMillis()) / 1000);
    }

    private void schedule() {
        long step = Math.max(60, interval()) * 1000;
        long now = System.currentTimeMillis();
        if (config().getBoolean("align-to-clock", true)) {
            // On the clock: an hourly key-all at xx:00, every 30m at xx:00 and xx:30 (server time zone).
            long offset = java.util.TimeZone.getDefault().getOffset(now);
            long local = now + offset;
            setNext(local - Math.floorMod(local, step) + step - offset);
        } else {
            setNext(now + step);
        }
    }

    // ── Countdown boss bar ────────────────────────────────────────────────

    private net.kyori.adventure.bossbar.BossBar bar;
    private String barTitle;

    /** Shown to everyone for the last bossbar.show-minutes before a key-all. */
    private void updateBar(long left) {
        long show = Math.max(0, config().getLong("bossbar.show-minutes", 5)) * 60;
        boolean wanted = config().getBoolean("bossbar.enabled", true) && show > 0 && left > 0 && left <= show;
        if (!wanted) {
            hideBar();
            return;
        }
        if (bar == null) {
            net.kyori.adventure.bossbar.BossBar.Color color;
            try {
                color = net.kyori.adventure.bossbar.BossBar.Color.valueOf(config().getString("bossbar.color", "YELLOW").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                color = net.kyori.adventure.bossbar.BossBar.Color.YELLOW;
            }
            bar = net.kyori.adventure.bossbar.BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f, color,
                    net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
            barTitle = null;
        }
        String title = com.vexorstudios.vexcore.core.Text.fill(config().getString("bossbar.title", "&#FFD900&lKEYALL &7▷ &f%time%"),
                Map.of("time", plugin.messages().time(left)));
        if (!title.equals(barTitle)) {
            barTitle = title;
            bar.name(com.vexorstudios.vexcore.core.Text.parse(title));
        }
        bar.progress((float) Math.max(0, Math.min(1, (double) left / show)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar); // cheap when already shown; covers joins
    }

    private void hideBar() {
        if (bar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        bar = null;
    }

    private void setNext(long when) {
        next = when;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next", when);
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + file + ": " + e.getMessage());
        }
    }

    /** Seconds left at the previous tick: a warning fires when its time is crossed, even if a lag spike skipped the exact second. */
    private long lastLeft = -1;

    private long[] warnings = new long[0];

    private void tick() {
        long left = left();
        if (lastLeft >= 0 && left < lastLeft) {
            // Of the warnings just crossed, the closest one (a lag spike or /keyall set can cross several).
            long crossed = -1;
            for (long at : warnings) {
                if (lastLeft > at && left <= at && (crossed < 0 || at < crossed)) crossed = at;
            }
            if (crossed > 0) broadcast(Bukkit.getOnlinePlayers(), "warning", Map.of("time", plugin.messages().time(crossed)));
        }
        lastLeft = left;
        updateBar(left);
        if (System.currentTimeMillis() >= next) {
            schedule();
            giveAll();
        }
    }

    /** False when nobody got anything (no rewards set up, or too few players). */
    private boolean giveAll() {
        if (rewards.isEmpty()) return false;
        int min = config().getInt("minimum-players", 0);
        if (Bukkit.getOnlinePlayers().size() < min) {
            broadcast(Bukkit.getOnlinePlayers(), "too-few", Map.of("minimum", min, "time", plugin.messages().time(left())));
            return false;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.earns(player, "keyall")) {
                com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.notEarning(player, "keyall");
                continue;
            }
            Reward r = roll();
            Scheduler.entity(player, () -> {
                Map<String, Object> ph = Map.of("player", player.getName(), "reward", r.name, "time", plugin.messages().time(left()));
                Actions.run(player, r.commands, ph, null);
                msg(player, "received", ph);
            });
        }
        broadcast(com.vexorstudios.vexcore.core.Messages.everyone(), "broadcast", Map.of("time", plugin.messages().time(left())));
        return true;
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
        if (args.length > 0 && sender.hasPermission("vexcore.keyall.admin")) {
            if (args[0].equalsIgnoreCase("force")) {
                schedule();
                if (giveAll()) msg(sender, "forced");
                else if (rewards.isEmpty()) msg(sender, "no-rewards");
                return;
            }
            if (args[0].equalsIgnoreCase("set")) {
                long seconds = args.length > 1 ? Time.seconds(args[1]) : -1;
                if (seconds <= 0) {
                    usage(sender, "keyall");
                    return;
                }
                setNext(System.currentTimeMillis() + seconds * 1000);
                lastLeft = -1; // a new countdown: nothing was "crossed" by jumping to it
                msg(sender, "set", "time", plugin.messages().time(seconds));
                return;
            }
        }
        msg(sender, "next", "time", plugin.messages().time(left()));
    }
}
