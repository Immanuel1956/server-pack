package com.vexorstudios.vexcore.features.rename;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.chatfilter.ChatFilterFeature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;

/**
 * /rename &lt;name&gt; renames the held item, /rename reset takes the name off. The name goes
 * through the chat filter; colours need vexcore.rename.color. Optional money cost.
 */
public final class RenameFeature extends Feature {

    @Override
    protected void enable() {
        command("rename", (sender, label, args) -> {
            Player player = player(sender);
            if (player == null) return;
            if (args.length == 0) {
                usage(player, "rename");
                return;
            }
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                msg(player, "hand-empty");
                return;
            }
            if (config().getStringList("blocked-items").stream().anyMatch(m -> Material.matchMaterial(m) == item.getType())) {
                msg(player, "blocked-item");
                return;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
                meta.customName(null);
                item.setItemMeta(meta);
                msg(player, "reset");
                return;
            }
            String name = String.join(" ", args);
            boolean colour = player.hasPermission("vexcore.rename.color");
            // Checked on exactly what the item will show: colour codes for those allowed them, the
            // rest (tags, and codes for everyone else) as the plain text it will be.
            String visible = colour ? Text.plain(Text.parse(MiniMessage.miniMessage().escapeTags(name))) : name;
            int max = config().getInt("max-length", 32);
            if (visible.isBlank() || visible.length() > max) {
                msg(player, "too-long", "max", max);
                return;
            }
            ChatFilterFeature filter = ChatFilterFeature.of(plugin);
            if (filter != null && !player.hasPermission("vexcore.chatfilter.bypass") && !filter.cleanName(visible)) {
                msg(player, "blocked-name");
                return;
            }
            double cost = config().getDouble("cost", 0);
            if (cost > 0 && !player.hasPermission("vexcore.rename.free") && !plugin.money().withdraw(player, cost)) {
                msg(player, "cannot-afford", "amount", plugin.money().format(cost));
                return;
            }
            // Colour codes only: tags players type are shown as text, never run.
            Component component = colour ? Text.parse(MiniMessage.miniMessage().escapeTags(name)) : Component.text(name);
            meta.customName(component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            item.setItemMeta(meta);
            msg(player, "renamed", "name", component, "cost", plugin.money().format(cost));
        }, (s, a) -> a.length == 1 ? List.of("reset") : List.of());
    }
}
