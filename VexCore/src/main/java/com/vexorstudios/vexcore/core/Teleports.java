package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Countdown teleports shared by spawn, afk, homes, warps, player warps, team homes, rtp and tpa.
 *
 * <p>Each feature configures its own countdown under {@code teleport:} in its config.yml
 * ({@code delay-seconds}, {@code cancel-on-move}, {@code cancel-on-damage}) and may override the
 * teleport messages ({@code teleport-countdown}, {@code teleport-success}, ...) and their sounds.
 * The destination is read when the countdown ends, so a player who moved in the meantime is
 * followed and a deleted home fails cleanly. One teleport per player at a time.
 *
 * <p>Smoothing ({@code teleports:} in config.yml): the destination's chunk is loaded while the
 * countdown runs, so arriving is instant instead of a freeze or "Loading terrain"; starting a new
 * teleport replaces a pending one instead of refusing; a nudge smaller than
 * {@code move-tolerance} doesn't cancel; arriving clears fall damage built up before and gives a
 * moment of protection while the world appears.
 */
public final class Teleports implements Listener {

    private static final class Pending {
        final Feature feature;
        final Supplier<Location> destination;
        final boolean cancelOnMove;
        final boolean cancelOnDamage;
        final Map<String, Object> placeholders;
        final Runnable after;
        Scheduler.Task task = Scheduler.NOOP;
        int remaining;
        Location start;

        Pending(Feature feature, Supplier<Location> destination, Map<String, ?> placeholders, Runnable after) {
            ConfigurationSection cfg = feature.config().getConfigurationSection("teleport");
            this.feature = feature;
            this.destination = destination;
            // getX(path) without a fallback so the jar's defaults apply to missing keys.
            this.cancelOnMove = cfg == null || !cfg.contains("cancel-on-move") || cfg.getBoolean("cancel-on-move");
            this.cancelOnDamage = cfg == null || !cfg.contains("cancel-on-damage") || cfg.getBoolean("cancel-on-damage");
            this.placeholders = new HashMap<>(placeholders);
            this.after = after;
            this.remaining = cfg == null || !cfg.contains("delay-seconds") ? 3 : Math.max(0, cfg.getInt("delay-seconds"));
        }
    }

    private final VexCore plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public Teleports(VexCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts a teleport. {@code bypass} is a permission that skips the countdown (may be null).
     * {@code after} runs once the player arrived (may be null).
     */
    public void start(Feature feature, Player player, Supplier<Location> destination, String bypass,
                      Map<String, ?> placeholders, Runnable after) {
        if (!Bukkit.isOwnedByCurrentRegion(player)) { // Folia: another player's command moved them
            Scheduler.entity(player, () -> start(feature, player, destination, bypass, placeholders, after));
            return;
        }
        UUID id = player.getUniqueId();
        if (pending.containsKey(id)) {
            // Changed their mind (/spawn, then /home): the new one wins, unless configured not to.
            if (!settings().getBoolean("replace-pending", true)) {
                feature.msg(player, "already-teleporting", placeholders);
                return;
            }
            cancel(player, null);
        }
        if (plugin.restrictions().deny(player)) return;
        Pending p = new Pending(feature, destination, placeholders, after);
        if (p.remaining <= 0 || (bypass != null && !bypass.isEmpty() && player.hasPermission(bypass))) {
            arrive(player, p);
            return;
        }
        p.start = player.getLocation();
        pending.put(id, p);
        preload(p);
        countdown(player, p);
        p.task = Scheduler.entityTimer(player, () -> tick(player, p), 20, 20);
    }

    private org.bukkit.configuration.ConfigurationSection settings() {
        org.bukkit.configuration.ConfigurationSection s = plugin.settings() == null ? null : plugin.settings().getConfigurationSection("teleports");
        return s != null ? s : new org.bukkit.configuration.MemoryConfiguration();
    }

    /**
     * Starts loading where the countdown ends, so the chunk is there when it does: the teleport
     * then takes no time and nobody stands in "Loading terrain". A loaded chunk stays loaded for a
     * while after (Paper keeps unused chunks ~10 s), longer than most countdowns.
     */
    private void preload(Pending p) {
        if (!settings().getBoolean("preload", true)) return;
        Location target;
        try {
            target = p.destination.get();
        } catch (RuntimeException e) {
            return; // it fails again, and is reported, when the countdown ends
        }
        if (target == null || target.getWorld() == null) return;
        target.getWorld().getChunkAtAsync(target.getBlockX() >> 4, target.getBlockZ() >> 4, true, chunk -> {
        });
    }

    public boolean isPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    private void countdown(Player player, Pending p) {
        p.placeholders.put("seconds", p.remaining);
        p.feature.msg(player, "teleport-countdown", p.placeholders);
    }

    private void tick(Player player, Pending p) {
        if (pending.get(player.getUniqueId()) != p) {
            p.task.cancel();
            return;
        }
        if (plugin.restrictions().check(player) != null) {
            cancel(player, null);
            plugin.restrictions().deny(player);
            return;
        }
        if (--p.remaining > 0) {
            countdown(player, p);
            return;
        }
        pending.remove(player.getUniqueId(), p);
        p.task.cancel();
        arrive(player, p);
    }

    private void arrive(Player player, Pending p) {
        Location target;
        try {
            target = p.destination.get();
        } catch (RuntimeException e) {
            target = null;
        }
        if (target == null || target.getWorld() == null) {
            p.feature.msg(player, "teleport-failed", p.placeholders);
            return;
        }
        player.teleportAsync(target).thenAccept(ok -> {
            if (!ok) {
                p.feature.msg(player, "teleport-failed", p.placeholders);
                return;
            }
            arrived(player);
            p.feature.msg(player, "teleport-success", p.placeholders);
            if (p.after != null) p.after.run();
        });
    }

    /**
     * Just arrived: the fall they were in before doesn't hurt when they land here, and for a
     * moment (arrival-protection-ticks) nothing hurts while the world around them appears.
     */
    public void arrived(Player player) {
        org.bukkit.configuration.ConfigurationSection s = settings();
        if (s.getBoolean("reset-fall-distance", true)) player.setFallDistance(0);
        int protect = Math.max(0, Math.min(200, s.getInt("arrival-protection-ticks", 30)));
        // Damage is ignored while no-damage ticks are above half the maximum (vanilla's hit cooldown).
        if (protect > 0) player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), protect + player.getMaximumNoDamageTicks() / 2));
    }

    /** Stops a player's countdown; sends {@code messageKey} of the owning feature if not null. */
    public void cancel(Player player, String messageKey) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) return;
        p.task.cancel();
        if (messageKey != null) p.feature.msg(player, messageKey, p.placeholders);
    }

    /** Stops every countdown a feature started (it is being disabled). */
    public void cancelAll(Feature feature) {
        pending.entrySet().removeIf(e -> {
            if (feature != null && e.getValue().feature != feature) return false;
            e.getValue().task.cancel();
            return true;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending p = pending.get(event.getPlayer().getUniqueId());
        if (p == null || !p.cancelOnMove || !event.hasChangedPosition()) return;
        if (stayed(p, event.getTo())) return;
        cancel(event.getPlayer(), "teleport-cancelled");
    }

    /**
     * A nudge (bumped by a mob, a step to the side) or a jump in place is not walking away: within
     * move-tolerance blocks sideways and a jump's height up or down of where the countdown began.
     */
    private boolean stayed(Pending p, Location to) {
        return stayed(p.start, to, settings().getDouble("move-tolerance", 1.0));
    }

    static boolean stayed(Location start, Location to, double tolerance) {
        if (start == null || to == null || to.getWorld() != start.getWorld() || tolerance <= 0) return false;
        double dx = to.getX() - start.getX(), dz = to.getZ() - start.getZ();
        return dx * dx + dz * dz <= tolerance * tolerance && Math.abs(to.getY() - start.getY()) <= 1.5;
    }

    /** Riding doesn't fire the player's own move event: a horse or boat can't carry them off either. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRide(org.bukkit.event.vehicle.VehicleMoveEvent event) {
        if (pending.isEmpty()) return;
        org.bukkit.Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        for (org.bukkit.entity.Entity rider : event.getVehicle().getPassengers()) {
            if (!(rider instanceof Player player)) continue;
            Pending p = pending.get(player.getUniqueId());
            if (p == null || !p.cancelOnMove || stayed(p, to)) continue;
            cancel(player, "teleport-cancelled");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Pending p = pending.get(player.getUniqueId());
        if (p == null || !p.cancelOnDamage) return;
        cancel(player, "teleport-cancelled-damage");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), null);
    }
}
