package com.vexorstudios.vexcore.features.staffchat;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /staffchat &lt;message&gt;, /staffchat to toggle, or start a chat line with the trigger (#). */
public final class StaffChatFeature extends Feature implements Listener {

    private final Set<UUID> on = ConcurrentHashMap.newKeySet();

    @Override
    protected void enable() {
        listen(this);
        command("staffchat", (sender, label, args) -> {
            if (args.length > 0) {
                send(sender, String.join(" ", args));
                return;
            }
            Player p = player(sender);
            if (p == null) return;
            boolean now = on.add(p.getUniqueId());
            if (!now) on.remove(p.getUniqueId());
            msg(p, now ? "toggle-on" : "toggle-off");
        });
    }

    /** Sends a line to every member of staff and the console. */
    public void send(CommandSender from, String text) {
        List<CommandSender> to = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("vexcore.staffchat")) to.add(p);
        to.add(Bukkit.getConsoleSender());
        Component message = Component.text(text);
        if (from instanceof Player p && config().getBoolean("render", true)
                && plugin.features().get("chat") instanceof com.vexorstudios.vexcore.features.chat.ChatFeature chat) {
            message = chat.render(p, message, text);
        }
        broadcast(to, "format", Map.of("player", from.getName(), "message", (Object) message));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("vexcore.staffchat")) return;
        String text = Text.plain(event.message());
        String trigger = config().getString("trigger", "#");
        boolean triggered = !trigger.isEmpty() && text.startsWith(trigger) && text.length() > trigger.length();
        if (!triggered && !on.contains(player.getUniqueId())) return;
        event.setCancelled(true);
        send(player, triggered ? text.substring(trigger.length()).strip() : text);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        on.remove(event.getPlayer().getUniqueId());
    }
}
