package com.vexorstudios.vexcore.features.screenshare;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Pos;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.gui.Actions;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ss &lt;player&gt; freezes a player for a screenshare (and takes them to the screenshare room
 * if one is set with /ss setroom). While frozen they can't move, fight, take damage or use
 * commands (except the allowed ones), and their chat only reaches staff. Leaving runs the
 * logout commands (a ban, usually). /ss end &lt;player&gt; lets them go and sends them back.
 */
public final class ScreenshareFeature extends Feature implements Listener {

    record Frozen(UUID staff, String staffName, Location back) {
    }

    private final Map<UUID, Frozen> frozen = new ConcurrentHashMap<>();
    private File file;

    @Override
    protected void enable() {
        file = plugin.files().data("screenshare.yml");
        frozen.putAll(CARRIED);
        CARRIED.clear();
        listen(this);
        restriction(p -> frozen.containsKey(p.getUniqueId()), "restricted");
        command("screenshare", this::command, (s, a) -> {
            if (a.length == 1) {
                List<String> out = new ArrayList<>(List.of("end", "setroom"));
                out.addAll(playerNames(s));
                return out;
            }
            return a.length == 2 && a[0].equalsIgnoreCase("end") ? null : List.of();
        });
        every(Math.max(1, config().getInt("reminder-seconds", 5)) * 20L, () -> {
            for (UUID id : frozen.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) Scheduler.entity(p, () -> msg(p, "reminder"));
            }
        });
    }

    /** Screenshares running during /vexcore reload: handed to the new instance, not ended. */
    private static final Map<UUID, Frozen> CARRIED = new ConcurrentHashMap<>();

    @Override
    protected void disable() {
        if (plugin.isEnabled()) { // a reload: the screenshare goes on
            CARRIED.putAll(frozen);
            frozen.clear();
            return;
        }
        for (UUID id : new ArrayList<>(frozen.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) release(p, null);
        }
    }

    public boolean isFrozen(UUID player) {
        return frozen.containsKey(player);
    }

    private void command(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, "screenshare");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("setroom")) {
            Player p = player(sender);
            if (p == null) return;
            YamlConfiguration yml = new YamlConfiguration();
            Pos.of(p.getLocation()).write(yml.createSection("room"));
            try {
                yml.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save " + file + ": " + e.getMessage());
            }
            msg(p, "room-set");
            return;
        }
        if (sub.equals("end")) {
            if (args.length < 2) {
                usage(sender, "screenshare");
                return;
            }
            Player target = target(sender, args[1]);
            if (target == null) return;
            if (!frozen.containsKey(target.getUniqueId())) {
                msg(sender, "not-frozen", "player", target.getName());
                return;
            }
            release(target, sender);
            return;
        }
        Player target = target(sender, args[0]);
        if (target == null) return;
        if (target.hasPermission("vexcore.screenshare.exempt")) {
            msg(sender, "exempt", "player", target.getName());
            return;
        }
        if (frozen.containsKey(target.getUniqueId())) {
            msg(sender, "already-frozen", "player", target.getName());
            return;
        }
        UUID staff = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        frozen.put(target.getUniqueId(), new Frozen(staff, sender.getName(), target.getLocation()));
        target.leaveVehicle(); // a boat or horse would carry them off
        Pos room = Pos.read(YamlConfiguration.loadConfiguration(file).getConfigurationSection("room"));
        Location to = room == null ? null : room.location();
        if (to != null) {
            target.teleportAsync(to);
            if (sender instanceof Player p && config().getBoolean("teleport-staff", true)) p.teleportAsync(to);
        }
        msg(target, "frozen", "staff", sender.getName());
        alert("started", Map.of("player", target.getName(), "staff", sender.getName()));
    }

    private void release(Player target, CommandSender by) {
        Frozen f = frozen.remove(target.getUniqueId());
        if (f == null) return;
        if (f.back != null && config().getBoolean("return-after", true)) target.teleportAsync(f.back);
        msg(target, "released");
        alert("ended", Map.of("player", target.getName(), "staff", by == null ? f.staffName : by.getName()));
    }

    private void alert(String key, Map<String, ?> ph) {
        List<CommandSender> to = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("vexcore.screenshare")) to.add(p);
        to.add(Bukkit.getConsoleSender());
        broadcast(to, key, ph);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen.containsKey(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getX() != to.getX() || from.getY() < to.getY() || from.getZ() != to.getZ()) {
            Location keep = from.clone();
            keep.setYaw(to.getYaw());
            keep.setPitch(to.getPitch());
            event.setTo(keep);
        }
    }

    /** Pearls, chorus fruit and portals don't take a frozen player anywhere (staff teleports still do). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (frozen.containsKey(event.getPlayer().getUniqueId()) && blockedCauses().contains(event.getCause().name())) event.setCancelled(true);
    }

    private Set<String> blockedCauses() {
        Set<String> causes = blocked;
        if (causes == null) {
            causes = new HashSet<>();
            for (String c : config().getStringList("blocked-teleport-causes")) causes.add(c.toUpperCase(Locale.ROOT));
            blocked = causes;
        }
        return causes;
    }

    private volatile Set<String> blocked;

    /** No getting on a boat, horse or minecart while frozen. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMount(org.bukkit.event.entity.EntityMountEvent event) {
        if (event.getEntity() instanceof Player p && frozen.containsKey(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!frozen.containsKey(event.getPlayer().getUniqueId())) return;
        String label = com.vexorstudios.vexcore.core.Commands.label(event.getMessage());
        if (config().getStringList("allowed-commands").contains(label)) return;
        event.setCancelled(true);
        msg(event.getPlayer(), "no-commands");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!frozen.containsKey(player.getUniqueId()) || !config().getBoolean("private-chat", true)) return;
        event.setCancelled(true);
        List<CommandSender> to = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("vexcore.screenshare")) to.add(p);
        to.add(player);
        to.add(Bukkit.getConsoleSender());
        broadcast(to, "chat", Map.of("player", player.getName(), "message", (Object) Component.text(Text.plain(event.message()))));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && frozen.containsKey(p.getUniqueId())) event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent e && e.getDamager() instanceof Player p && frozen.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Frozen f = frozen.remove(player.getUniqueId());
        if (f == null) return;
        // Kicked or banned by staff: that was the verdict, don't add the logout punishment to it.
        if (event.getReason() == PlayerQuitEvent.QuitReason.KICKED && !config().getBoolean("kick-counts-as-logout", false)) return;
        Map<String, Object> ph = Map.of("player", player.getName(), "staff", f.staffName);
        alert("logged-out", ph);
        List<String> commands = config().getStringList("logout-commands");
        if (!commands.isEmpty()) Scheduler.global(() -> {
            for (String c : commands) {
                String line = Text.fill(c.replaceFirst("^\\[console]\\s*", ""), ph);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
            }
        });
    }
}
