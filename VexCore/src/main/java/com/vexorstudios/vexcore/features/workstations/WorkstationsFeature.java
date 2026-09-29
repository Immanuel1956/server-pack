package com.vexorstudios.vexcore.features.workstations;

import org.bukkit.Bukkit;

import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;

/**
 * Portable workstations: /craft, /anvil, /grindstone, /smithingtable, /loom,
 * /cartographytable, /stonecutter, /echest [player]. Turn single ones off in commands.yml.
 */
public final class WorkstationsFeature extends Feature implements org.bukkit.event.Listener {

    @Override
    protected void enable() {
        station("craft", MenuType.CRAFTING);
        station("anvil", MenuType.ANVIL);
        station("grindstone", MenuType.GRINDSTONE);
        station("smithingtable", MenuType.SMITHING);
        station("loom", MenuType.LOOM);
        station("cartographytable", MenuType.CARTOGRAPHY_TABLE);
        station("stonecutter", MenuType.STONECUTTER);
        command("echest", this::echest);
        listen(this);
    }

    /** A workstation without the block: it stays open wherever the player goes (checkReachable off). */
    private void station(String id, MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>> type) {
        command(id, (sender, label, args) -> {
            Player player = player(sender);
            if (player == null) return;
            player.openInventory(type.builder().checkReachable(false).build(player));
            msg(player, "opened", "station", id);
        }, (s, a) -> java.util.List.of());
    }

    private void echest(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        Player owner = player;
        if (args.length > 0) {
            if (!player.hasPermission("vexcore.echest.others")) {
                msg(player, "no-permission", "permission", "vexcore.echest.others");
                return;
            }
            owner = target(player, args[0]);
            if (owner == null) return;
            // Folia: another region's player's items can't be edited safely from here.
            if (owner != player && !Bukkit.isOwnedByCurrentRegion(owner)) {
                msg(player, "too-far", "player", owner.getName());
                return;
            }
        }
        player.openInventory(owner.getEnderChest());
        msg(player, owner == player ? "opened" : "opened-other", "station", "echest", "player", owner.getName());
    }

    /** The owner is leaving (and being saved): anyone editing their ender chest stops, or items dupe or vanish. */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        org.bukkit.inventory.Inventory chest = event.getPlayer().getEnderChest();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer == event.getPlayer() || viewer.getOpenInventory().getTopInventory() != chest) continue;
            if (Bukkit.isOwnedByCurrentRegion(viewer)) viewer.closeInventory(); // before the save, not after
            else onPlayerThread(viewer, viewer::closeInventory);
        }
    }
}
