package com.vexorstudios.vexcore.features.staffessentials;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff essentials: /fly /heal /feed /god [player], /speed &lt;1-10&gt; [walk|fly] [player],
 * /gmc /gms /gma /gmsp [player], /invsee &lt;player&gt;, /clearinventory [player]. Using one on
 * someone else needs the command's permission plus ".others".
 */
public final class StaffEssentialsFeature extends Feature implements Listener {

    private final Set<UUID> god = ConcurrentHashMap.newKeySet();

    @Override
    protected void enable() {
        listen(this);
        self("fly", p -> {
            boolean on = !p.getAllowFlight();
            p.setAllowFlight(on);
            if (!on) p.setFlying(false);
            return on ? "fly-on" : "fly-off";
        });
        self("heal", p -> {
            var max = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(max == null ? 20 : max.getValue());
            p.setFoodLevel(20);
            p.setSaturation(20);
            p.setFireTicks(0);
            if (config().getBoolean("heal-clears-effects", true)) for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            return "healed";
        });
        self("feed", p -> {
            p.setFoodLevel(20);
            p.setSaturation(20);
            return "fed";
        });
        self("god", p -> {
            if (god.add(p.getUniqueId())) return "god-on";
            god.remove(p.getUniqueId());
            return "god-off";
        });
        self("gmc", p -> mode(p, GameMode.CREATIVE));
        self("gms", p -> mode(p, GameMode.SURVIVAL));
        self("gma", p -> mode(p, GameMode.ADVENTURE));
        self("gmsp", p -> mode(p, GameMode.SPECTATOR));
        self("clearinventory", p -> {
            p.getInventory().clear();
            return "cleared";
        });
        command("speed", this::speed, (s, a) -> a.length == 1 ? List.of("1", "2", "3", "5", "10")
                : a.length == 2 ? List.of("walk", "fly") : a.length == 3 ? null : List.of());
        command("invsee", (sender, label, args) -> {
            Player p = player(sender);
            if (p == null) return;
            if (args.length == 0) {
                usage(p, "invsee");
                return;
            }
            Player target = target(p, args[0]);
            if (target == null) return;
            // Folia: another region's player's items can't be edited safely from here.
            if (target != p && !Bukkit.isOwnedByCurrentRegion(target)) {
                msg(p, "too-far", "player", target.getName());
                return;
            }
            p.openInventory(target.getInventory());
            msg(p, "invsee", "player", target.getName());
        }, (s, a) -> a.length == 1 ? null : List.of());
    }

    @Override
    protected void disable() {
        god.clear();
    }

    private String mode(Player p, GameMode mode) {
        p.setGameMode(mode);
        return "gamemode";
    }

    /**
     * A command that acts on the sender, or on [player] with "&lt;permission&gt;.others". The action
     * runs on the target's thread and returns the message key; %player% and %gamemode% are filled.
     */
    private void self(String id, java.util.function.Function<Player, String> action) {
        command(id, (sender, label, args) -> {
            Player target;
            if (args.length > 0) {
                if (!sender.hasPermission("vexcore." + id + ".others")) {
                    msg(sender, "no-permission", "permission", "vexcore." + id + ".others");
                    return;
                }
                target = target(sender, args[0]);
            } else {
                target = player(sender);
            }
            if (target == null) return;
            Player t = target;
            run(t, () -> {
                String key = action.apply(t);
                java.util.Map<String, Object> ph = java.util.Map.of("player", t.getName(),
                        "gamemode", t.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
                msg(t, key, ph);
                if (!t.equals(sender)) msg(sender, key + "-other", ph);
            });
        }, (s, a) -> a.length == 1 && s.hasPermission("vexcore." + id + ".others") ? null : List.of());
    }

    /** On the target's own thread (Folia). */
    private void run(Player target, Runnable action) {
        Scheduler.entity(target, action);
    }

    private void speed(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, "speed");
            return;
        }
        int level;
        try {
            level = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            usage(sender, "speed");
            return;
        }
        if (level < 1 || level > 10) {
            msg(sender, "speed-range");
            return;
        }
        Player target;
        if (args.length > 2) {
            if (!sender.hasPermission("vexcore.speed.others")) {
                msg(sender, "no-permission", "permission", "vexcore.speed.others");
                return;
            }
            target = target(sender, args[2]);
        } else {
            target = player(sender);
        }
        if (target == null) return;
        Boolean fly = args.length > 1 ? (args[1].equalsIgnoreCase("fly") ? Boolean.TRUE : args[1].equalsIgnoreCase("walk") ? Boolean.FALSE : null) : null;
        Player t = target;
        run(t, () -> {
            boolean flying = fly != null ? fly : t.isFlying();
            // Vanilla: walk 0.2, fly 0.1. Level 1 is normal, 10 is the fastest allowed (1.0).
            float base = flying ? 0.1f : 0.2f;
            float value = level == 1 ? base : Math.min(1f, base + (1f - base) * (level - 1) / 9f);
            if (flying) t.setFlySpeed(value);
            else t.setWalkSpeed(value);
            java.util.Map<String, Object> ph = java.util.Map.of("player", t.getName(), "speed", level, "kind",
                    config().getString(flying ? "words.fly" : "words.walk", flying ? "fly" : "walk"));
            msg(t, "speed", ph);
            if (!t.equals(sender)) msg(sender, "speed-other", ph);
        });
    }

    // ── God mode ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && god.contains(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p && god.contains(p.getUniqueId()) && event.getFoodLevel() < p.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        // Staff looking into their inventory stop now, or what they take after the save is duped.
        com.vexorstudios.vexcore.core.OpenInventories.closeViewers(event.getPlayer());
        if (!config().getBoolean("god-survives-relog", false)) god.remove(event.getPlayer().getUniqueId());
    }
}
