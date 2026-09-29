package com.vexorstudios.vexcore.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Another player's live inventory or ender chest opened by staff (/invsee, /echest, the staff mode
 * inspect tool). The game saves a player the moment they leave; anything taken out of their
 * inventory after that would stay with the staff member and come back with the player: a dupe.
 */
public final class OpenInventories {

    private OpenInventories() {
    }

    /** Closes every view of the leaving player's inventory and ender chest (call on quit, before the save). */
    public static void closeViewers(Player leaving) {
        Inventory inventory = leaving.getInventory();
        Inventory chest = leaving.getEnderChest();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(leaving)) continue;
            Inventory top = viewer.getOpenInventory().getTopInventory();
            if (top != inventory && top != chest && !leaving.equals(top.getHolder(false))) continue;
            if (Bukkit.isOwnedByCurrentRegion(viewer)) viewer.closeInventory();
            else Scheduler.entity(viewer, viewer::closeInventory);
        }
    }
}
