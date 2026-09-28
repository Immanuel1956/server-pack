package com.vexorstudios.vexcore.features.combat;

import com.vexorstudios.vexcore.core.Commands;
import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat tag: hitting or being hit by a player tags both for {@code duration-seconds}. Tagged
 * players can't use the listed commands or teleport, see a countdown, and are killed if they
 * log out (optional).
 */
public final class CombatFeature extends Feature implements Listener {

    private final Map<UUID, Long> taggedUntil = new ConcurrentHashMap<>();
    /** Tags running during /vexcore reload: kept, or a reload would be a free combat log. */
    private static final Map<UUID, Long> CARRIED = new ConcurrentHashMap<>();

    @Override
    protected void disable() {
        if (plugin.isEnabled()) CARRIED.putAll(taggedUntil);
    }
    private Set<String> blocked = Set.of();

    @Override
    protected void enable() {
        taggedUntil.putAll(CARRIED);
        CARRIED.clear();
        Set<String> list = new HashSet<>();
        for (String s : config().getStringList("blocked-commands")) list.add(s.toLowerCase(Locale.ROOT).replace("/", ""));
        blocked = list;
        restriction(p -> isTagged(p.getUniqueId()), "teleport-blocked");
        placeholder("combat", (p, arg) -> config().getString(isTagged(p.getUniqueId()) ? "placeholder.tagged" : "placeholder.untagged", ""));
        placeholder("combat_seconds", (p, arg) -> String.valueOf(seconds(p.getUniqueId())));
        every(20, this::tick);
        listen(this);
    }

    public boolean isTagged(UUID player) {
        Long until = taggedUntil.get(player);
        return until != null && until > System.currentTimeMillis();
    }

    private long seconds(UUID player) {
        Long until = taggedUntil.get(player);
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    private void tag(Player player, Player other) {
        if (com.vexorstudios.vexcore.core.Bypass.has(player, "vexcore.combat.bypass", "combat-tag")) return;
        for (String world : config().getStringList("disabled-worlds")) {
            if (world.equalsIgnoreCase(player.getWorld().getName())) return;
        }
        boolean was = isTagged(player.getUniqueId());
        taggedUntil.put(player.getUniqueId(), System.currentTimeMillis() + Math.max(1, config().getInt("duration-seconds", 20)) * 1000L);
        if (!was) {
            msg(player, "tagged", "player", other.getName());
            plugin.teleports().cancel(player, null);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        taggedUntil.entrySet().removeIf(e -> {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) return true;
            if (e.getValue() <= now) {
                msg(player, "untagged");
                return true;
            }
            msg(player, "status", "seconds", (e.getValue() - now + 999) / 1000);
            return false;
        });
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        tag(victim, attacker);
        tag(attacker, victim);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isTagged(player.getUniqueId()) || com.vexorstudios.vexcore.core.Bypass.has(player, "vexcore.combat.bypass", "combat-tag")) return;
        String label = com.vexorstudios.vexcore.core.Commands.label(event.getMessage());
        if (blocks(label)) {
            event.setCancelled(true);
            msg(player, "command-blocked", "command", label);
        }
    }

    /** Blocks by the typed label, the command's real name and aliases, or its VexCore id. */
    private boolean blocks(String label) {
        String bare = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;
        if (blocked.contains(label) || blocked.contains(bare)) return true;
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (command == null) return false;
        if (command instanceof Commands.Registered r && blocked.contains(r.id())) return true;
        if (blocked.contains(command.getName().toLowerCase(Locale.ROOT))) return true;
        for (String alias : command.getAliases()) if (blocked.contains(alias.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        taggedUntil.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!isTagged(player.getUniqueId())) return;
        taggedUntil.remove(player.getUniqueId());
        if (!config().getBoolean("logout-kill", true)) return;
        player.setHealth(0);
        broadcast(Bukkit.getOnlinePlayers(), "logged-out", Map.of("player", player.getName()));
    }
}
