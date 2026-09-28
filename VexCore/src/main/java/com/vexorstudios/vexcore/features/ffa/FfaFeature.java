package com.vexorstudios.vexcore.features.ffa;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FFA events: /ffa opens an event and broadcasts it; everyone who clicks it (or types /ffaaccept)
 * within request-seconds is teleported to the host. One event at a time, a cooldown per host.
 */
public final class FfaFeature extends Feature implements org.bukkit.event.Listener {

    private volatile UUID host;
    private volatile long until;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /** Who is in the current FFA (the host and everyone who joined); kills between them count as FFA kills. */
    private final java.util.Set<UUID> fighters = ConcurrentHashMap.newKeySet();
    /** Everyone who joined this FFA, dead or alive: dying doesn't allow another teleport to the host. */
    private final java.util.Set<UUID> joined = ConcurrentHashMap.newKeySet();

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player dead = event.getEntity(), killer = dead.getKiller();
        boolean was = fighters.remove(dead.getUniqueId()); // out of this FFA once dead
        if (!was || killer == null || !fighters.contains(killer.getUniqueId())) return;
        if (plugin.features().get("stats") instanceof com.vexorstudios.vexcore.features.stats.StatsFeature stats) {
            stats.add(killer.getUniqueId(), com.vexorstudios.vexcore.features.stats.StatsFeature.Stat.FFA_KILLS, 1);
        }
    }

    @Override
    protected void enable() {
        listen(this);
        command("ffa", (sender, label, args) -> {
            Player p = player(sender);
            if (p == null) return;
            // A host who logged out doesn't keep the event going.
            if (host != null && until > System.currentTimeMillis() && Bukkit.getPlayer(host) != null) {
                msg(p, "already-active");
                return;
            }
            long left = cooldowns.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
            if (left > 0 && !p.hasPermission("vexcore.ffa.bypass")) {
                msg(p, "cooldown", "seconds", (left + 999) / 1000);
                return;
            }
            if (plugin.restrictions().check(p) != null) {
                msg(p, "restricted");
                return;
            }
            host = p.getUniqueId();
            fighters.clear();
            joined.clear();
            fighters.add(host);
            until = System.currentTimeMillis() + config().getLong("request-seconds", 60) * 1000;
            cooldowns.put(p.getUniqueId(), System.currentTimeMillis() + config().getLong("cooldown-seconds", 300) * 1000);
            announce(p);
        });
        command("ffaaccept", (sender, label, args) -> {
            Player p = player(sender);
            if (p == null) return;
            Player h = host == null || until < System.currentTimeMillis() ? null : Bukkit.getPlayer(host);
            if (h == null) {
                msg(p, "no-request");
                return;
            }
            if (h.equals(p)) {
                msg(p, "self");
                return;
            }
            if (plugin.restrictions().check(p) != null) {
                msg(p, "restricted");
                return;
            }
            if (!joined.add(p.getUniqueId())) { // one join per event, not a free teleport to the host
                msg(p, "already-joined");
                return;
            }
            fighters.add(p.getUniqueId());
            p.teleportAsync(h.getLocation()).thenRun(() -> msg(p, "joined"));
        });
    }

    /** The broadcast; its last non-empty line runs /ffaaccept when clicked. */
    private void announce(Player p) {
        List<String> lines = config().getStringList("broadcast");
        int last = lines.size() - 1;
        while (last >= 0 && lines.get(last).isBlank()) last--;
        String accept = "/" + plugin.commands().name("ffaaccept");
        Map<String, Object> ph = Map.of("player", p.getName());
        for (Player to : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < lines.size(); i++) {
                Component c = Text.parse(lines.get(i), to, ph);
                if (i == last) c = c.clickEvent(ClickEvent.runCommand(accept));
                to.sendMessage(c);
            }
            // A sound, not a message: msg() found no "broadcast-sound" message and played nothing.
            com.vexorstudios.vexcore.core.SoundSpec sound = plugin.messages().sound(this, "broadcast-sound");
            if (sound != null) Scheduler.entity(to, () -> sound.play(to));
        }
    }
}
