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
 * Countdown teleports shared by spawn, afk, homes and tpa.
 *
 * <p>Each feature configures its own countdown under {@code teleport:} in its config.yml
 * ({@code delay-seconds}, {@code cancel-on-move}, {@code cancel-on-damage}) and may override the
 * teleport messages ({@code teleport-countdown}, {@code teleport-success}, ...) and their sounds.
 * The destination is read when the countdown ends, so a player who moved in the meantime is
 * followed and a deleted home fails cleanly. One teleport per player at a time.
 */
public final class Teleports implements Listener {

    private final class Pending {
        final Feature feature;
        final Supplier<Location> destination;
        final boolean cancelOnMove;
        final boolean cancelOnDamage;
        final Map<String, Object> placeholders;
        final Runnable after;
        Scheduler.Task task = Scheduler.NOOP;
        int remaining;

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
            feature.msg(player, "already-teleporting", placeholders);
            return;
        }
        if (plugin.restrictions().deny(player)) return;
        Pending p = new Pending(feature, destination, placeholders, after);
        if (p.remaining <= 0 || (bypass != null && !bypass.isEmpty() && player.hasPermission(bypass))) {
            arrive(player, p);
            return;
        }
        pending.put(id, p);
        countdown(player, p);
        p.task = Scheduler.entityTimer(player, () -> tick(player, p), 20, 20);
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
            p.feature.msg(player, "teleport-success", p.placeholders);
            if (p.after != null) p.after.run();
        });
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
        if (p == null || !p.cancelOnMove || !event.hasChangedBlock()) return;
        cancel(event.getPlayer(), "teleport-cancelled");
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
            if (p != null && p.cancelOnMove) cancel(player, "teleport-cancelled");
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
