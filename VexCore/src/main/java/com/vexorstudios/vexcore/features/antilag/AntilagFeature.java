package com.vexorstudios.vexcore.features.antilag;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.core.Time;
import org.bukkit.Bukkit;
import java.util.Locale;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Clears ground items every clear-interval, with action bar countdowns before it. /antilag clears
 * now. Paper only: Folia can't list a world's entities from one thread.
 */
public final class AntilagFeature extends Feature {

    private long next;

    @Override
    protected void enable() {
        // Parsed once: the timer looks them up every second.
        ConfigurationSection cd = config().getConfigurationSection("countdowns");
        if (cd != null) for (String key : cd.getKeys(false)) {
            long at = Time.seconds(key);
            if (at > 0) countdowns.put(at, cd.getString(key + ".actionbar", ""));
        }
        if (Scheduler.FOLIA) {
            problems().add("features/antilag: Folia can't list entities world-wide, so item clearing is off on this server");
            return;
        }
        schedule();
        command("antilag", (sender, label, args) -> clear());
        placeholder("antilag", (p, a) -> plugin.messages().time(Math.max(0, (next - System.currentTimeMillis()) / 1000)));
        every(20, this::tick);
    }

    private void schedule() {
        next = System.currentTimeMillis() + Math.max(30, Time.seconds(config().getString("clear-interval", "10m"))) * 1000;
    }

    private final Map<Long, String> countdowns = new java.util.HashMap<>();

    private void tick() {
        long left = (next - System.currentTimeMillis() + 500) / 1000;
        if (left <= 0) {
            clear();
            return;
        }
        String bar = countdowns.get(left);
        if (bar != null) bar(bar, Map.of());
    }

    private void clear() {
        schedule();
        Set<String> types = new HashSet<>();
        for (String t : config().getStringList("entity-types")) types.add(t.toUpperCase(Locale.ROOT));
        if (types.isEmpty()) types.add("ITEM");
        List<String> ignored = config().getStringList("worlds-ignored");
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            if (ignored.contains(world.getName())) continue;
            for (Entity e : world.getEntities()) {
                if (e instanceof Player || !types.contains(e.getType().name())) continue;
                // Folia: each entity is removed on its own region's thread.
                if (Bukkit.isOwnedByCurrentRegion(e)) e.remove();
                else Scheduler.entity(e, e::remove);
                count++;
            }
        }
        bar(config().getString("cleared-actionbar", "Cleared %count% items"), Map.of("count", count));
    }

    private void bar(String line, Map<String, ?> ph) {
        if (line.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendActionBar(Text.parse(line, p, ph));
            msg(p, "sound");
        }
    }
}
