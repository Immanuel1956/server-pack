package com.vexorstudios.vexcore.features.dropfix;

import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Death drops land in one pile instead of flying apart. Only the event's own drop list is used,
 * so keep-inventory, soulbound and grave plugins that edit it keep working.
 */
public final class DropFixFeature extends Feature implements Listener {

    @Override
    protected void enable() {
        listen(this);
    }

    /** MONITOR: every other plugin (graves, arenas, keep-inventory) has had its say first. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        boolean player = event.getEntity() instanceof Player;
        if (event instanceof PlayerDeathEvent death && death.getKeepInventory()) return;
        if (!config().getBoolean(player ? "players" : "mobs", true) || event.getDrops().isEmpty()) return;
        List<ItemStack> drops = config().getBoolean("merge-stacks", true) ? merge(event.getDrops()) : new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        Location at = event.getEntity().getLocation();
        if (config().getBoolean("center-on-block", true)) {
            at = at.getBlock().getLocation().add(0.5, 0.25, 0.5);
        }
        for (ItemStack item : drops) {
            if (item == null || item.getType().isAir()) continue;
            at.getWorld().dropItem(at, item, drop -> drop.setVelocity(new Vector()));
        }
    }

    private static List<ItemStack> merge(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            int left = item.getAmount();
            for (ItemStack stack : out) {
                if (left <= 0) break;
                if (!stack.isSimilar(item)) continue;
                int move = Math.min(left, stack.getMaxStackSize() - stack.getAmount());
                if (move <= 0) continue;
                stack.setAmount(stack.getAmount() + move);
                left -= move;
            }
            if (left > 0) {
                ItemStack rest = item.clone();
                rest.setAmount(left);
                out.add(rest);
            }
        }
        return out;
    }
}
