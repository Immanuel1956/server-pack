package com.vexorstudios.vexcore.features.trash;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.gui.Slots;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;

/**
 * /trash: a menu to throw items into. Whatever is left in its bin slots is gone when it closes.
 * Buttons: {@code function: empty} empties the bin, {@code function: clear-inventory} wipes the
 * player's own inventory (what exactly is set under {@code clear:} in config.yml).
 */
public final class TrashFeature extends Feature {

    @Override
    protected void enable() {
        command("trash", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) openTrash(player);
        });
    }

    private void openTrash(Player player) {
        open(player, "trash", menu -> {
            List<Integer> bin = Slots.parse(menu.file().yml().get("bin-slots"));
            menu.editable(bin);
            menu.function("empty", click -> {
                for (int slot : bin) menu.getInventory().setItem(slot, null);
                msg(player, "emptied");
            });
            menu.function("clear-inventory", click -> {
                if (!click.type().isRightClick()) {
                    msg(player, "clear-needs-right-click");
                    return;
                }
                clear(player.getInventory());
                msg(player, "inventory-cleared");
            });
        });
    }

    private void clear(PlayerInventory inventory) {
        if (config().getBoolean("clear.storage", true)) {
            for (int i = 0; i < 36; i++) inventory.setItem(i, null);
        }
        if (config().getBoolean("clear.armor", false)) inventory.setArmorContents(new ItemStack[4]);
        if (config().getBoolean("clear.offhand", false)) inventory.setItemInOffHand(null);
    }
}
