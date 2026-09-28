package com.vexorstudios.vexcore.features.links;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.SoundSpec;
import com.vexorstudios.vexcore.core.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /discord, /store, /apply: one message whose lines all open {@code url} when clicked.
 * The same class runs each of them, from its own file in features/social/. The link commands of
 * links.yml and the social broadcasts show their messages the same way.
 */
public final class LinkFeature extends Feature {

    @Override
    protected void enable() {
        command(id(), (sender, label, args) -> show(sender, config(), plugin.messages().prefix(this)));
    }

    /**
     * Sends a link message to one player (or the console): {@code message} (lines), {@code url}
     * (every line opens it), {@code hover}, {@code actionbar} (a note above the hotbar, like "the
     * link was sent in the chat") and {@code sound}, all read from {@code s}.
     */
    public static void show(CommandSender sender, ConfigurationSection s, String prefix) {
        Player viewer = sender instanceof Player p ? p : null;
        for (Component c : render(s, viewer, prefix, sender.getName())) sender.sendMessage(c);
        if (viewer == null) return;
        String bar = s.getString("actionbar", "");
        if (bar != null && !bar.isBlank()) {
            viewer.sendActionBar(Text.parse(bar.replace("{prefix}", prefix), viewer, placeholders(s, sender.getName())));
        }
        SoundSpec sound = SoundSpec.of(s.get("sound"));
        if (sound != null) sound.play(viewer);
    }

    /** The lines of a link message as the viewer sees them. */
    public static List<Component> render(ConfigurationSection s, Player viewer, String prefix, String name) {
        String url = link(s);
        String hover = s.getString("hover", "");
        Map<String, Object> ph = placeholders(s, name);
        List<Component> out = new ArrayList<>();
        for (String line : Text.lines(s.get("message"))) {
            Component c = Text.parse(line.replace("{prefix}", prefix), viewer, ph);
            if (!url.isEmpty() && !line.isBlank()) {
                c = c.clickEvent(ClickEvent.openUrl(url));
                if (hover != null && !hover.isBlank()) c = c.hoverEvent(HoverEvent.showText(Text.parse(hover, viewer, ph)));
            }
            out.add(c);
        }
        return out;
    }

    private static String link(ConfigurationSection s) {
        String url = s.getString("url", "");
        url = url == null ? "" : url.trim();
        return url.isEmpty() || url.startsWith("http") ? url : "https://" + url;
    }

    private static Map<String, Object> placeholders(ConfigurationSection s, String name) {
        String url = s.getString("url", "");
        return Map.of("player", name, "url", url == null ? "" : url.trim());
    }
}
