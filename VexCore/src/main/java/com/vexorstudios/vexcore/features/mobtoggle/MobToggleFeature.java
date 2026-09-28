package com.vexorstudios.vexcore.features.mobtoggle;

import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.Locale;

/** /mobtoggle: no mobs spawn within {@code radius} blocks of a player who turned it on. */
public final class MobToggleFeature extends Feature implements Listener {

    private static final String TOGGLE = "mobtoggle";

    @Override
    protected void enable() {
        toggle(TOGGLE, config().getBoolean("default", false), this::toggle);
        command("mobtoggle", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) toggle(player);
        });
        // Read once: this runs for every mob that spawns.
        reasons.clear();
        for (CreatureSpawnEvent.SpawnReason r : CreatureSpawnEvent.SpawnReason.values()) {
            if (config().getBoolean("block-reasons." + r.name().toLowerCase(Locale.ROOT), r == CreatureSpawnEvent.SpawnReason.NATURAL)) reasons.add(r);
        }
        whitelist.clear();
        for (String type : config().getStringList("whitelist")) whitelist.add(type.toUpperCase(Locale.ROOT));
        monstersOnly = config().getBoolean("monsters-only", true);
        double radius = Math.max(0, Math.min(256, config().getDouble("radius", 50)));
        radiusSquared = radius * radius;
        listen(this);
        // After /vexcore reload: players already loaded won't fire loaded() again.
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.data().isLoaded(p.getUniqueId())) refresh(p);
    }

    @Override
    protected void disable() {
        on.clear();
    }

    @Override
    protected void loaded(Player player) {
        refresh(player);
    }

    /** Players with the toggle on: the spawn check looks at nobody else. */
    private final java.util.Set<java.util.UUID> on = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void refresh(Player player) {
        if (plugin.toggles().isOn(player.getUniqueId(), TOGGLE)) on.add(player.getUniqueId());
        else on.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        on.remove(event.getPlayer().getUniqueId());
    }

    private final java.util.Set<CreatureSpawnEvent.SpawnReason> reasons = java.util.EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
    private final java.util.Set<String> whitelist = new java.util.HashSet<>();
    private boolean monstersOnly;
    private double radiusSquared;

    private void toggle(Player player) {
        Boolean now = plugin.toggles().flip(player, TOGGLE);
        if (now == null) {
            msg(player, "data-loading");
            return;
        }
        refresh(player);
        msg(player, now ? "enabled" : "disabled", "radius", (int) Math.sqrt(radiusSquared));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (on.isEmpty() || !reasons.contains(event.getSpawnReason())) return;
        if (monstersOnly && !(event.getEntity() instanceof Enemy)) return;
        if (whitelist.contains(event.getEntityType().name())) return;
        Location at = event.getLocation();
        for (java.util.UUID id : on) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || player.getWorld() != at.getWorld()) continue;
            if (player.getLocation().distanceSquared(at) <= radiusSquared) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
