package com.vexorstudios.vexcore.features.commandroutes;

import com.vexorstudios.vexcore.core.Commands;
import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

public final class CommandRoutesFeature extends Feature implements Listener {
    @Override protected void enable() {
        listen(this);
        command("plugins", (s, l, a) -> msg(s, "plugins"), (s, a) -> java.util.List.of());
        for (String id : java.util.List.of("help", "tutorial")) command(id, (s, l, a) -> {
            if (!plugin.commands().isActive("guide")) { msg(s, "guide-unavailable"); return; }
            Bukkit.dispatchCommand(s, plugin.commands().name("guide"));
        }, (s, a) -> java.util.List.of());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String label = Commands.label(event.getMessage());
        if (pluginListLabel(label)) {
            event.setCancelled(true);
            msg(event.getPlayer(), "plugins");
        }
    }

    public static boolean pluginListLabel(String label) {
        int colon = label.lastIndexOf(':');
        String base = label.substring(colon + 1);
        return base.equals("pl") || base.equals("plugins");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTab(PlayerCommandSendEvent event) {
        event.getCommands().removeIf(c -> c.contains(":") && pluginListLabel(c.toLowerCase(java.util.Locale.ROOT)));
    }
}
