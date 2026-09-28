package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type an amount in chat": the next chat line of a player goes to a callback instead of the
 * chat. The callback runs on the player's thread. The cancel word (globalmessages.yml
 * {@code input-cancel-word}) or the timeout ends it without an answer.
 */
public final class ChatInput implements Listener {

    private record Waiting(Consumer<String> answer, Runnable cancelled, long until) {
    }

    private final VexCore plugin;
    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

    public ChatInput(VexCore plugin) {
        this.plugin = plugin;
    }

    /** Waits for the player's next chat line. {@code cancelled} may be null. */
    public void ask(Player player, int seconds, Consumer<String> answer, Runnable cancelled) {
        waiting.put(player.getUniqueId(), new Waiting(answer, cancelled, System.currentTimeMillis() + seconds * 1000L));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Waiting w = waiting.remove(player.getUniqueId());
        if (w == null) return;
        if (System.currentTimeMillis() > w.until) return; // too late: normal chat
        event.setCancelled(true);
        String text = Text.plain(event.message()).trim();
        boolean cancel = text.equalsIgnoreCase(plugin.messages().global().getString("input-cancel-word", "cancel"));
        Scheduler.entity(player, () -> {
            if (cancel) {
                plugin.messages().send(null, player, "input-cancelled", Map.of());
                if (w.cancelled != null) w.cancelled.run();
            } else {
                w.answer.accept(text);
            }
        });
    }

    /** Drops every open question (reload: the features that asked are gone). */
    public void clear() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Waiting> e : waiting.entrySet()) {
            if (e.getValue().until < now) continue;
            Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
            // Tell them, or their next chat line would go out as a normal message.
            if (p != null) Scheduler.entity(p, () -> plugin.messages().send(null, p, "input-cancelled", Map.of()));
        }
        waiting.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }
}
