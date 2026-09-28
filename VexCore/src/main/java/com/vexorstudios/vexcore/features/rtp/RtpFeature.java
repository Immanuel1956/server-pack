package com.vexorstudios.vexcore.features.rtp;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.SafeSpot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * /rtp opens the world menu, /rtp &lt;world&gt; goes straight there. Free.
 *
 * <p>Fast: every RTP world keeps a few safe spots ready (ready-spots), found in the background
 * and topped up after each use, so /rtp starts its countdown at once. The spot is checked
 * again right before use (terrain changes); that loads its chunk, which the server keeps loaded
 * for a while, so the teleport after the countdown finds it ready. With nothing ready, a live search runs several
 * tries at once.
 *
 * <p>Safe: solid ground (no leaves, magma, cactus...), two truly clear blocks above it, nothing
 * dangerous one block around, inside the world border, no blocked biomes (oceans). After landing:
 * slow falling and no damage at all for safe-landing-seconds (and they can't hit anyone meanwhile).
 */
public final class RtpFeature extends Feature implements Listener {

    private record Ready(Location spot, long found) {
    }

    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> landing = new ConcurrentHashMap<>();
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();
    private final Map<String, Queue<Ready>> ready = new ConcurrentHashMap<>();
    private final Set<String> filling = ConcurrentHashMap.newKeySet();

    /** /endlock: while on, RTP to the End is refused (and portals there too, see config). */
    private volatile boolean endLocked;
    private java.io.File lockFile;

    @Override
    protected void enable() {
        listen(this);
        lockFile = plugin.files().data("endlock.yml");
        endLocked = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(lockFile).getBoolean("locked", false);
        command("rtp", (sender, label, args) -> {
            Player p = player(sender);
            if (p == null) return;
            if (args.length > 0) go(p, args[0].toLowerCase(java.util.Locale.ROOT));
            else open(p, "rtp", menu -> {
                menu.with("end_status", config().getString(endLocked ? "endlock.locked-text" : "endlock.open-text", endLocked ? "&cLOCKED" : "&aOPEN"));
                for (String key : worlds()) menu.function("world-" + key, c -> {
                    p.closeInventory();
                    go(p, key);
                });
            });
        }, (s, a) -> a.length == 1 ? worlds() : List.of());
        command("endlock", (sender, label, args) -> {
            boolean lock = args.length == 0 ? !endLocked : args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("lock");
            endLocked = lock;
            org.bukkit.configuration.file.YamlConfiguration yml = new org.bukkit.configuration.file.YamlConfiguration();
            yml.set("locked", lock);
            try {
                yml.save(lockFile);
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Could not save " + lockFile + ": " + e.getMessage());
            }
            if (config().getBoolean("endlock.broadcast", true)) broadcast(org.bukkit.Bukkit.getOnlinePlayers(), lock ? "end-locked-broadcast" : "end-unlocked-broadcast", Map.of("player", sender.getName()));
            else msg(sender, lock ? "end-locked-broadcast" : "end-unlocked-broadcast", "player", sender.getName());
        }, (s, a) -> a.length == 1 ? List.of("on", "off") : List.of());
        placeholder("end_locked", (p, a) -> String.valueOf(endLocked));
        // Worlds aren't loaded yet while VexCore starts: the pools fill once they are, and are
        // topped up on this timer too (a failed search tries again later).
        every(Math.max(20, config().getLong("ready-refill-ticks", 200)), () -> {
            for (String key : worlds()) fill(key);
        });
    }

    private List<String> worlds() {
        ConfigurationSection w = config().getConfigurationSection("worlds");
        return w == null ? List.of() : new ArrayList<>(w.getKeys(false));
    }

    private SafeSpot.Area area(String key) {
        ConfigurationSection s = config().getConfigurationSection("worlds." + key);
        if (s == null) return null;
        World.Environment env = key.contains("end") ? World.Environment.THE_END : key.contains("nether") ? World.Environment.NETHER : World.Environment.NORMAL;
        return SafeSpot.Area.of(s, env, config().getStringList("blocked-biomes"), config().getInt("generated-attempts", 20), config().getConfigurationSection("search"));
    }

    private long maxAge() {
        return Math.max(10, config().getLong("ready-max-age-seconds", 300)) * 1000;
    }

    // ── Ready spots ───────────────────────────────────────────────────────

    /** Tops up a world's ready spots, one search at a time per world. */
    private void fill(String key) {
        int want = Math.max(0, config().getInt("ready-spots", 3));
        Queue<Ready> queue = ready.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        long now = System.currentTimeMillis();
        queue.removeIf(r -> now - r.found > maxAge()); // old spots: the land may have changed
        if (queue.size() >= want || !isEnabled() || !filling.add(key)) return;
        SafeSpot.Area area = area(key);
        if (area == null) {
            filling.remove(key);
            return;
        }
        SafeSpot.find(area).whenComplete((spot, error) -> {
            filling.remove(key);
            if (spot == null || !isEnabled()) return; // tried again on the timer
            queue.add(new Ready(spot, System.currentTimeMillis()));
            fill(key);
        });
    }

    /** A ready spot that is still safe, or a fresh search when none is. */
    private java.util.concurrent.CompletableFuture<Location> spot(String key, SafeSpot.Area area) {
        Queue<Ready> queue = ready.get(key);
        Ready r = queue == null ? null : queue.poll();
        fill(key);
        if (r == null) return SafeSpot.find(area);
        if (System.currentTimeMillis() - r.found > maxAge()) return spot(key, area);
        return SafeSpot.recheck(area, r.spot).thenCompose(ok -> ok
                ? java.util.concurrent.CompletableFuture.completedFuture(r.spot)
                : spot(key, area));
    }

    // ── Teleporting ───────────────────────────────────────────────────────

    private void go(Player p, String key) {
        ConfigurationSection s = config().getConfigurationSection("worlds." + key);
        if (s == null) {
            msg(p, "unknown-world", "world", key);
            return;
        }
        String permission = s.getString("permission", "");
        if (!permission.isEmpty() && !p.hasPermission(permission)) {
            msg(p, "no-permission", "permission", permission);
            return;
        }
        long left = cooldown.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        if (left > 0 && !p.hasPermission("vexcore.rtp.bypass")) {
            msg(p, "cooldown", "time", plugin.messages().time((left + 999) / 1000));
            return;
        }
        if (plugin.teleports().isPending(p)) {
            msg(p, "already-teleporting");
            return;
        }
        SafeSpot.Area area = area(key);
        if (area == null) {
            msg(p, "world-missing");
            return;
        }
        if (endLocked && area.world().getEnvironment() == World.Environment.THE_END && !p.hasPermission("vexcore.endlock.bypass")) {
            msg(p, "end-locked");
            return;
        }
        if (!searching.add(p.getUniqueId())) return;
        Queue<Ready> queue = ready.get(key);
        if (queue == null || queue.isEmpty()) msg(p, "searching"); // only when there's a real wait
        spot(key, area).whenComplete((spot, error) -> {
            searching.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (spot == null) {
                msg(p, "not-found");
                return;
            }
            Map<String, Object> ph = Map.of("world", s.getString("display", key));
            plugin.teleports().start(this, p, () -> spot, "vexcore.rtp.bypass", ph, () -> {
                cooldown.put(p.getUniqueId(), System.currentTimeMillis() + config().getLong("cooldown-seconds", 10) * 1000);
                int safe = config().getInt("safe-landing-seconds", 5);
                if (safe > 0) {
                    landing.put(p.getUniqueId(), System.currentTimeMillis() + safe * 1000L);
                    String name = config().getString("safe-landing-effect", "slow_falling").toLowerCase(java.util.Locale.ROOT);
                    PotionEffectType effect = name.isEmpty() ? null : org.bukkit.Registry.MOB_EFFECT.get(org.bukkit.NamespacedKey.minecraft(name));
                    if (effect != null) p.addPotionEffect(new PotionEffect(effect, safe * 20, 0, false, false, true));
                }
                msg(p, "teleported", ph);
            });
        });
    }

    /** Just landed: no damage at all (a mob at the spot, a fall, a burn) for a few seconds. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        // Protected players can't use it to hit others either.
        if (config().getBoolean("safe-landing-block-attacking", true)
                && event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent e && attacker(e.getDamager()) != null) {
            Player attacker = attacker(e.getDamager());
            Long until = landing.get(attacker.getUniqueId());
            if (until != null && until > System.currentTimeMillis()) {
                event.setCancelled(true);
                return;
            }
        }
        if (!(event.getEntity() instanceof Player p) || !config().getBoolean("safe-landing-block-damage", true)) return;
        Long until = landing.get(p.getUniqueId());
        if (until == null) return;
        if (until > System.currentTimeMillis()) event.setCancelled(true);
        else landing.remove(p.getUniqueId());
    }

    /** Who is really behind a hit: the player, the shooter of an arrow/trident/potion, or who lit the TNT. */
    private static Player attacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }

    /** While the End is locked, End portals don't take anyone there either (endlock.block-portals). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (!endLocked || event.getCause() != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_PORTAL
                || !config().getBoolean("endlock.block-portals", true) || event.getPlayer().hasPermission("vexcore.endlock.bypass")) return;
        if (event.getTo() == null || event.getTo().getWorld() == null || event.getTo().getWorld().getEnvironment() != World.Environment.THE_END) return;
        event.setCancelled(true);
        msg(event.getPlayer(), "end-locked");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        landing.remove(event.getPlayer().getUniqueId());
        searching.remove(event.getPlayer().getUniqueId());
    }
}
