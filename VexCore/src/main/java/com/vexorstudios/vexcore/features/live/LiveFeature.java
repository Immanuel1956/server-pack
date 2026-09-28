package com.vexorstudios.vexcore.features.live;

import com.vexorstudios.vexcore.core.Feature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * /live &lt;link&gt; tells everyone you are streaming; /live toggle hides these for you.
 * Only links to the listed platforms, once per {@code cooldown-seconds}.
 */
public final class LiveFeature extends Feature {

    private static final String TOGGLE = "live";
    private static final Pattern SAFE_URL = Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+");

    private final Map<UUID, Long> lastPost = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        toggle(TOGGLE, config().getBoolean("default", true), p -> flip(p, TOGGLE, "toggle-on", "toggle-off"));
        command("live", this::live, (s, a) -> a.length == 1 ? List.of("toggle") : List.of());
    }

    private void live(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        if (args.length == 0) {
            usage(player, "live");
            return;
        }
        if (args[0].equalsIgnoreCase("toggle")) {
            flip(player, TOGGLE, "toggle-on", "toggle-off");
            return;
        }
        if (!player.hasPermission("vexcore.live")) {
            msg(player, "no-permission", "permission", "vexcore.live");
            return;
        }
        String link = args[0].toLowerCase(Locale.ROOT).startsWith("http") ? args[0] : "https://" + args[0];
        if (!SAFE_URL.matcher(link).matches() || !allowed(link)) {
            msg(player, "invalid-platform");
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = config().getInt("cooldown-seconds", 600) * 1000L;
        Long last = lastPost.get(player.getUniqueId());
        if (last != null && now - last < cooldown && !player.hasPermission("vexcore.live.bypass")) {
            msg(player, "cooldown", "seconds", (cooldown - (now - last) + 999) / 1000);
            return;
        }
        lastPost.put(player.getUniqueId(), now);
        Map<String, Object> ph = Map.of("player", player.getName(),
                "link", Component.text(link).clickEvent(ClickEvent.openUrl(link)));
        List<CommandSender> to = new ArrayList<>();
        to.add(Bukkit.getConsoleSender());
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.toggles().isOn(p.getUniqueId(), TOGGLE)) to.add(p);
        plugin.messages().broadcast(to, config().get("message"), ph, plugin.messages().prefix(this));
        msg(player, "posted");
    }

    private boolean allowed(String link) {
        String host;
        try {
            host = URI.create(link).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String domain : config().getStringList("platforms")) {
            domain = domain.toLowerCase(Locale.ROOT).trim();
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }
}
