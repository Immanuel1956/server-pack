package com.vexorstudios.vexcore.features.commandwhitelist;

import com.vexorstudios.vexcore.core.Commands;
import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Only whitelisted commands can be used and are shown in tab completion; everything else
 * answers like an unknown command. Groups add commands for players with their permission.
 * "plugin:command" forms are always blocked (they would get around the list).
 */
public final class CommandWhitelistFeature extends Feature implements Listener {

    @Override
    protected void enable() {
        listen(this);
        cache = null;
        for (Player p : Bukkit.getOnlinePlayers()) onPlayerThread(p, p::updateCommands);
    }

    @Override
    protected void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) onPlayerThread(p, p::updateCommands);
    }

    /** The lists by permission ("" = everyone), read once per enable: this runs on every command. */
    private volatile Map<String, Set<String>> cache;
    private volatile int cachedFor = -1; // how many VexCore commands there were (more register during startup)

    private Map<String, Set<String>> lists() {
        Map<String, Set<String>> lists = cache;
        int registered = plugin.commands().active().size();
        if (lists != null && cachedFor == registered) return lists;
        lists = new java.util.LinkedHashMap<>();
        Set<String> everyone = lists.computeIfAbsent("", k -> new HashSet<>());
        ConfigurationSection groups = config().getConfigurationSection("groups");
        if (groups != null) for (String key : groups.getKeys(false)) {
            Set<String> set = lists.computeIfAbsent(groups.getString(key + ".permission", ""), k -> new HashSet<>());
            for (String c : groups.getStringList(key + ".commands")) set.add(c.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        }
        if (config().getBoolean("allow-vexcore-commands", true)) {
            for (Commands.Registered r : plugin.commands().active()) {
                if (r.owner() == this) continue;
                everyone.add(r.getName().toLowerCase(Locale.ROOT));
                for (String alias : r.getAliases()) everyone.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        cache = lists;
        cachedFor = registered;
        return lists;
    }

    /** One command: looked up list by list, no set built (this runs for every command typed). */
    private boolean allowed(Player player, String label) {
        for (Map.Entry<String, Set<String>> e : lists().entrySet()) {
            if (e.getValue().contains(label) && (e.getKey().isEmpty() || player.hasPermission(e.getKey()))) return true;
        }
        return false;
    }

    private Set<String> allowed(Player player) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : lists().entrySet()) {
            if (e.getKey().isEmpty() || player.hasPermission(e.getKey())) out.addAll(e.getValue());
        }
        return out;
    }

    private boolean bypass(Player player) {
        return player.hasPermission("vexcore.commandwhitelist.bypass");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (bypass(player)) return;
        String label = Commands.label(event.getMessage());
        if (label.indexOf(':') >= 0 || !allowed(player, label)) {
            event.setCancelled(true);
            msg(player, "blocked", "command", label);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTab(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        if (bypass(player)) return;
        Set<String> allowed = allowed(player);
        event.getCommands().removeIf(c -> c.contains(":") || !allowed.contains(c.toLowerCase(Locale.ROOT)));
    }
}
