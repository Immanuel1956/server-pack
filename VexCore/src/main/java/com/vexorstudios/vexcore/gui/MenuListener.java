package com.vexorstudios.vexcore.gui;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards every VexCore menu. Menus are recognised by their holder, never by title, so a renamed
 * menu or a chest that happens to share its title can't be confused with one. Items can't be
 * taken out, put in or dragged, except in a menu's editable slots (the trash).
 */
public final class MenuListener implements Listener {

    /** Clicks closer together than this are ignored (double clicks, auto clickers). */

    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) return;
        int raw = event.getRawSlot();
        boolean inMenu = raw >= 0 && raw < top.getSize();
        if (!inMenu && menu.hasEditable() && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            // Shift-click from the player's own inventory: vanilla would drop it into ANY empty
            // menu slot, including ones that aren't editable (and would never give it back).
            event.setCancelled(true);
            Inventory from = event.getClickedInventory();
            int slot = event.getSlot();
            if (!(event.getWhoClicked() instanceof Player) || from == null || !menu.live()) return;
            // Moved in this same click (the event is cancelled, so the game sends the result right
            // after): no tick where the item has left one inventory but not reached the other.
            // Only opening or closing inventories must wait for the next tick, not moving items.
            ItemStack item = from.getItem(slot);
            if (item == null || item.isEmpty()) return;
            if (!menu.allows(item)) {
                menu.refuse(item);
                return;
            }
            from.setItem(slot, menu.insert(item));
            menu.changedLater();
            return;
        }
        if (menu.hasEditable() && event.getAction() != InventoryAction.COLLECT_TO_CURSOR
                && (!inMenu || menu.isEditable(raw))) {
            // The player's own inventory or a free slot of an editable menu. What goes into the
            // menu has to pass its filter (the sell menu refuses what can't be sold).
            if (inMenu) {
                ItemStack incoming = incoming(event);
                if (!menu.allows(incoming)) {
                    event.setCancelled(true);
                    menu.refuse(incoming);
                    return;
                }
                menu.changedLater();
            }
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!menu.live()) {
            Scheduler.entity(player, player::closeInventory);
            return;
        }
        if (!inMenu) return;
        Menu.Button button = menu.button(raw);
        if (button == null) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.put(player.getUniqueId(), now);
        if (last != null && now - last < com.vexorstudios.vexcore.VexCore.get().settings().getLong("menu-click-gap-ms", 120)) return;
        // Next tick: opening or closing inventories inside a click event is unsafe.
        org.bukkit.event.inventory.ClickType click = event.getClick();
        Scheduler.entity(player, () -> menu.click(raw, button, click));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) return;
        boolean intoMenu = false;
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize() && !menu.isEditable(raw)) {
                event.setCancelled(true);
                return;
            }
            if (raw < top.getSize()) intoMenu = true;
        }
        if (!intoMenu) return;
        if (!menu.allows(event.getOldCursor())) {
            event.setCancelled(true);
            menu.refuse(event.getOldCursor());
            return;
        }
        menu.changedLater();
    }

    /** The item a click in a menu slot puts there, or null when it only takes out. */
    private static ItemStack incoming(InventoryClickEvent event) {
        return switch (event.getAction()) {
            // PLACE_FROM_BUNDLE: an item out of the bundle on the cursor. The bundle holds items,
            // so a filter that refuses full bundles (the sell menu) refuses this too.
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR, PLACE_FROM_BUNDLE -> event.getCursor();
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (!(event.getWhoClicked() instanceof Player p)) yield null;
                int button = event.getHotbarButton();
                yield button >= 0 ? p.getInventory().getItem(button) : p.getInventory().getItemInOffHand();
            }
            default -> null;
        };
    }

    /** Also fires on disconnect, before the quit event and the final save. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory closing = event.getInventory();
        if (closing.getHolder(false) instanceof Menu menu && closing == menu.getInventory()) menu.closed();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Closes the menus of one feature (or all when null). {@code now} closes on this thread,
     * for shutdown when nothing can be scheduled any more.
     */
    public static void closeAll(Feature feature, boolean now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu menu)) continue;
            if (feature != null && menu.feature() != feature) continue;
            if (now) {
                try {
                    player.closeInventory();
                } catch (RuntimeException ignored) {
                }
            } else {
                Scheduler.entity(player, player::closeInventory);
            }
        }
    }
}
