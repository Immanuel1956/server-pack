package com.vexorstudios.vexcore.features.links;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SoundSpec;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * features/social/broadcast.yml: every {@code interval-minutes} one social message goes to
 * everyone online, like SetupCore's broadcasts. An entry is the name of a link command (discord,
 * store, apply or one of links.yml) or its own lines with a url. Silent unless it sets a sound.
 */
public final class BroadcastFeature extends Feature {

    private int next;

    @Override
    protected void enable() {
        long minutes = Math.max(1, config().getLong("interval-minutes", 5));
        every(minutes * 60 * 20, this::broadcast);
    }

    private void broadcast() {
        List<?> entries = config().getList("messages", List.of());
        if (entries == null || entries.isEmpty()) return;
        List<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.size() < Math.max(1, config().getInt("min-players", 1))) return;
        // A link whose feature is off is skipped: try each entry once at most.
        for (int tries = 0; tries < entries.size(); tries++) {
            int index = config().getBoolean("random", false) ? ThreadLocalRandom.current().nextInt(entries.size()) : next++ % entries.size();
            ConfigurationSection message = resolve(entries.get(index));
            if (message == null) continue;
            String prefix = plugin.messages().prefix(this);
            SoundSpec sound = SoundSpec.of(config().get("sound"));
            for (Player p : online) Scheduler.entity(p, () -> {
                for (Component line : LinkFeature.render(message, p, prefix, p.getName())) p.sendMessage(line);
                if (sound != null) sound.play(p);
            });
            return;
        }
    }

    /** A link's settings from its name, or an entry's own lines; null when there is nothing to send. */
    private ConfigurationSection resolve(Object entry) {
        if (entry instanceof String name) {
            if (plugin.features().get(name) instanceof LinkFeature link) return link.config();
            if (plugin.features().get("links") instanceof LinksFeature links) return links.link(name);
            return null;
        }
        if (entry instanceof Map<?, ?> map) {
            MemoryConfiguration own = new MemoryConfiguration();
            for (Map.Entry<?, ?> e : map.entrySet()) own.set(String.valueOf(e.getKey()), e.getValue());
            if (!own.contains("message") && own.contains("lines")) own.set("message", own.get("lines"));
            return own.contains("message") ? own : null;
        }
        if (entry instanceof ConfigurationSection section) return section;
        return null;
    }
}
