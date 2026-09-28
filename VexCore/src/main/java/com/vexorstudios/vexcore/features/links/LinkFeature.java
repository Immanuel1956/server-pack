package com.vexorstudios.vexcore.features.links;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.SoundSpec;
import com.vexorstudios.vexcore.core.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * /discord, /store, /apply: one message whose lines all open {@code url} when clicked.
 * The same class runs each of them, from its own folder.
 */
public final class LinkFeature extends Feature {

    @Override
    protected void enable() {
        command(id(), (sender, label, args) -> show(sender));
    }

    private void show(CommandSender sender) {
        Player viewer = sender instanceof Player p ? p : null;
        String url = config().getString("url", "").trim();
        String hover = config().getString("hover", "");
        String prefix = plugin.messages().prefix(this);
        Map<String, Object> ph = Map.of("player", sender.getName(), "url", url);
        for (String line : Text.lines(config().get("message"))) {
            Component c = Text.parse(line.replace("{prefix}", prefix), viewer, ph);
            if (!url.isEmpty() && !line.isBlank()) {
                c = c.clickEvent(ClickEvent.openUrl(url.startsWith("http") ? url : "https://" + url));
                if (!hover.isBlank()) c = c.hoverEvent(HoverEvent.showText(Text.parse(hover, viewer, ph)));
            }
            sender.sendMessage(c);
        }
        SoundSpec sound = SoundSpec.of(config().get("sound"));
        if (sound != null && viewer != null) sound.play(viewer);
    }
}
