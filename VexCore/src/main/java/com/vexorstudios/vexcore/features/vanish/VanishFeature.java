package com.vexorstudios.vexcore.features.vanish;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Visibility;
import com.vexorstudios.vexcore.core.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /vanish [player]: hidden from everyone without vexcore.vanish.see (also from the tab list and
 * the player count), joins and leaves silently, stays vanished across relogs and restarts.
 * Optionally no item pickup, no mob targeting, no pressure plates, no damage. Sets the common
 * "vanished" metadata so other plugins recognise it.
 */
public final class VanishFeature extends Feature implements Listener {

    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private File file;

    @Override
    protected void enable() {
        file = plugin.files().data("vanish.yml");
        for (String s : YamlConfiguration.loadConfiguration(file).getStringList("vanished")) {
            try {
                vanished.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        listen(this);
        command("vanish", (sender, label, args) -> {
            Player target;
            if (args.length > 0) {
                if (!sender.hasPermission("vexcore.vanish.others")) {
                    msg(sender, "no-permission", "permission", "vexcore.vanish.others");
                    return;
                }
                target = target(sender, args[0]);
            } else target = player(sender);
            if (target != null) set(sender, target, !vanished.contains(target.getUniqueId()));
        });
        placeholder("vanished", (p, a) -> String.valueOf(vanished.contains(p.getUniqueId())));
        placeholder("online", (p, a) -> String.valueOf(visibleCount()));
        every(Math.max(1, config().getLong("actionbar-interval-ticks", 40)), () -> {
            for (UUID id : vanished) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) Scheduler.entity(p, () -> msg(p, "actionbar"));
            }
        });
        for (Player p : Bukkit.getOnlinePlayers()) if (vanished.contains(p.getUniqueId())) apply(p);
    }

    @Override
    protected void disable() {
        // Turning the feature off shows everyone again (the list stays for when it comes back).
        // A reload that keeps vanish on doesn't: showing them for a moment would give them away.
        if (plugin.isEnabled() && plugin.files().settings("config.yml").getBoolean("features.vanish", true)) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (UUID id : vanished) {
                Player hidden = Bukkit.getPlayer(id);
                boolean playerhide = plugin.features().get("playerhide") instanceof com.vexorstudios.vexcore.features.playerhide.PlayerHideFeature h
                        && h.hiding(viewer) && hidden != null && !hidden.hasPermission("vexcore.playerhide.exempt");
                if (hidden != null && !hidden.equals(viewer) && !playerhide) onPlayerThread(viewer, () -> viewer.showPlayer(plugin, hidden));
            }
        }
    }

    public boolean isVanished(UUID player) {
        return vanished.contains(player);
    }

    /** Vanish on or off (staff mode). Same messages as /vanish. */
    public void setVanished(Player player, boolean on) {
        if (isVanished(player.getUniqueId()) != on) set(player, player, on);
    }

    /** Online players minus vanished ones. */
    public int visibleCount() {
        // Online minus the (few) vanished who are online: scoreboards ask for this per player per second.
        int n = Bukkit.getOnlinePlayers().size();
        for (UUID id : vanished) if (Bukkit.getPlayer(id) != null) n--;
        return n;
    }

    private void set(CommandSender by, Player target, boolean on) {
        if (on) vanished.add(target.getUniqueId());
        else vanished.remove(target.getUniqueId());
        save();
        apply(target);
        msg(target, on ? "vanished" : "visible");
        if (!by.equals(target)) msg(by, on ? "on-other" : "off-other", "player", target.getName());
        if (config().getBoolean("fake-messages", true)) {
            List<Player> others = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (!p.hasPermission("vexcore.vanish.see")) others.add(p);
            broadcast(others, on ? "fake-leave" : "fake-join", Map.of("player", com.vexorstudios.vexcore.core.Visibility.name(target)));
        }
        List<Player> staff = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("vexcore.vanish.see") && !p.equals(target)) staff.add(p);
        broadcast(staff, on ? "staff-on" : "staff-off", Map.of("player", target.getName()));
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (UUID id : vanished) list.add(id.toString());
        yml.set("vanished", list);
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + file + ": " + e.getMessage());
        }
    }

    /** Shows or hides one player to everyone, on each viewer's own thread. */
    private void apply(Player target) {
        // The name tag goes at once (and comes back): it would show where a vanished player is.
        if (plugin.features().get("nametags") instanceof com.vexorstudios.vexcore.features.nametags.NametagsFeature tags) {
            onPlayerThread(target, () -> tags.rebuild(target));
        }
        boolean hidden = vanished.contains(target.getUniqueId());
        setVanishedMetadata(target, hidden);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            // Through Visibility: un-vanishing must not show someone a viewer's /playerhide hides.
            if (!viewer.equals(target)) onPlayerThread(viewer, () -> Visibility.update(viewer, target));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joiner = event.getPlayer();
        if (!joiner.hasPermission("vexcore.vanish.see")) {
            for (UUID id : vanished) {
                Player hidden = Bukkit.getPlayer(id);
                if (hidden != null && !hidden.equals(joiner)) joiner.hidePlayer(plugin, hidden);
            }
        }
        if (vanished.contains(joiner.getUniqueId())) {
            if (!joiner.hasPermission("vexcore.vanish")) {
                vanished.remove(joiner.getUniqueId());
                save();
            }
            apply(joiner);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && vanished.contains(p.getUniqueId()) && !config().getBoolean("pickup-items", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && vanished.contains(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlate(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && vanished.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && vanished.contains(p.getUniqueId()) && config().getBoolean("invulnerable", true)) {
            event.setCancelled(true);
        }
    }

    /**
     * The "vanished" metadata other plugins (tab lists, chat, Essentials) read to hide vanished
     * players. Bukkit calls metadata deprecated, but there is no other shared way to say it.
     */
    @SuppressWarnings("deprecation")
    private void setVanishedMetadata(Player target, boolean hidden) {
        target.setMetadata("vanished", new FixedMetadataValue(plugin, hidden));
    }
}
