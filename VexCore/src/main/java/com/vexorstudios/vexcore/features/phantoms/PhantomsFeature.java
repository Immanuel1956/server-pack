package com.vexorstudios.vexcore.features.phantoms;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /phantoms (also in /settings): a player who turns it on has no phantoms within {@code radius}
 * blocks. New phantoms don't spawn near them, and ones that fly in are despawned.
 */
public final class PhantomsFeature extends Feature implements Listener {

    private static final String TOGGLE = "phantoms";

    /** Players with the toggle on, so the spawn check and the sweep never look at anyone else. */
    private final Set<UUID> on = ConcurrentHashMap.newKeySet();
    private double radius;
    private double radiusSquared;
    private boolean particles;

    @Override
    protected void enable() {
        radius = Math.max(1, Math.min(128, config().getDouble("radius", 50)));
        radiusSquared = radius * radius;
        particles = config().getBoolean("particles", true);
        toggle(TOGGLE, config().getBoolean("default", false), this::toggle);
        command("phantoms", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) toggle(player);
        });
        listen(this);
        // After /vexcore reload: players already online are loaded and won't fire loaded() again.
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) if (plugin.data().isLoaded(p.getUniqueId())) refresh(p);
        long ticks = Math.max(1, config().getLong("check-seconds", 2)) * 20;
        every(ticks, this::sweep);
    }

    @Override
    protected void disable() {
        on.clear();
    }

    @Override
    protected void loaded(Player player) {
        refresh(player);
    }

    private void refresh(Player player) {
        if (plugin.toggles().isOn(player.getUniqueId(), TOGGLE)) on.add(player.getUniqueId());
        else on.remove(player.getUniqueId());
    }

    private void toggle(Player player) {
        Boolean now = plugin.toggles().flip(player, TOGGLE);
        if (now == null) {
            msg(player, "data-loading");
            return;
        }
        refresh(player);
        msg(player, now ? "enabled" : "disabled", "radius", (int) radius);
        if (now) Scheduler.entity(player, () -> clear(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        on.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.PHANTOM || on.isEmpty()) return;
        Location at = event.getLocation();
        for (UUID id : on) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null && p.getWorld() == at.getWorld() && p.getLocation().distanceSquared(at) <= radiusSquared) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Every check-seconds: despawn phantoms around each player who has it on. */
    private void sweep() {
        for (UUID id : on) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p == null) {
                on.remove(id);
                continue;
            }
            Scheduler.entity(p, () -> clear(p));
        }
    }

    /** Player's thread. Each phantom is removed on its own thread (Folia). */
    private void clear(Player p) {
        if (!p.isOnline()) return;
        World world = p.getWorld();
        // Phantoms only spawn in the overworld: no area scan every few seconds in the nether or end.
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        for (Phantom phantom : world.getNearbyEntitiesByType(Phantom.class, p.getLocation(), radius)) {
            phantom.getScheduler().run(plugin, task -> {
                if (!phantom.isValid()) return;
                if (particles) phantom.getWorld().spawnParticle(Particle.POOF, phantom.getLocation(), 12, 0.4, 0.3, 0.4, 0.02);
                phantom.remove();
            }, null);
        }
    }
}
