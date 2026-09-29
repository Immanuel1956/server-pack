package com.vexorstudios.vexcore.features.sell;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.BundleContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemContainerContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemEnchantments;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.vexorstudios.vexcore.gui.Menu;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worth in tooltips without touching items. Every item the player's game is sent (their
 * inventory, chests, the sell menu) gets the worth lines added to the copy in the packet; the
 * item on the server never changes, so nothing is written into its data and stacks keep
 * stacking. The lines follow the stack: 3 carrots at $2 show "Worth: $6", and when a 4th joins
 * the game is sent the slot again and it shows $8.
 *
 * <p>Creative mode sends items back as the game shows them, so creative players get no lines
 * (hidden-gamemodes), and anything that comes back with the lines anyway loses them again: the
 * added lines are counted in a mark only the copy carries.
 *
 * <p>Only loaded when PacketEvents is installed.
 */
final class WorthLore implements PacketListener {

    /** In the copy's custom data: how many lines were added at the end of the lore. */
    static final String MARK = "vexcore_worth_lines";

    private final SellFeature feature;
    private final Map<ItemType, Optional<Material>> materials = new ConcurrentHashMap<>();

    private WorthLore(SellFeature feature) {
        this.feature = feature;
    }

    static Object register(SellFeature feature) {
        return PacketEvents.getAPI().getEventManager().registerListener(new WorthLore(feature), PacketListenerPriority.LOW);
    }

    static void unregister(Object listener) {
        PacketEvents.getAPI().getEventManager().unregisterListener((PacketListenerCommon) listener);
    }

    // ── Out: add the lines ────────────────────────────────────────────────

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.WINDOW_ITEMS && type != PacketType.Play.Server.SET_SLOT
                && type != PacketType.Play.Server.SET_CURSOR_ITEM && type != PacketType.Play.Server.SET_PLAYER_INVENTORY) return;
        if (!(event.getPlayer() instanceof Player player) || !feature.worthShownTo(player)) return;
        try {
            if (type == PacketType.Play.Server.WINDOW_ITEMS) windowItems(event, player);
            else if (type == PacketType.Play.Server.SET_SLOT) setSlot(event, player);
            else if (type == PacketType.Play.Server.SET_CURSOR_ITEM) cursor(event, player);
            else playerInventory(event, player);
        } catch (RuntimeException error) {
            // A tooltip is never worth a broken packet: send it as it was.
            event.markForReEncode(false);
        }
    }

    private void windowItems(PacketSendEvent event, Player player) {
        WrapperPlayServerWindowItems w = new WrapperPlayServerWindowItems(event);
        List<ItemStack> items = new ArrayList<>(w.getItems());
        // Window 0 is the player's own inventory; other windows are the container, then the
        // player's 36 slots.
        int top = w.getWindowId() == 0 ? 0 : Math.max(0, items.size() - 36);
        Inventory topInventory = top > 0 ? player.getOpenInventory().getTopInventory() : null;
        boolean topMatches = topInventory != null && topInventory.getSize() == top;
        boolean inventory = feature.worthIn("inventory");
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            boolean ok = i < top ? topMatches && topAllows(topInventory, i) : inventory;
            if (!ok) continue;
            ItemStack decorated = decorate(player, items.get(i));
            if (decorated != null) {
                items.set(i, decorated);
                changed = true;
            }
        }
        if (inventory) {
            Optional<ItemStack> carried = w.getCarriedItem();
            ItemStack decorated = carried.isPresent() ? decorate(player, carried.get()) : null;
            if (decorated != null) {
                w.setCarriedItem(decorated);
                changed = true;
            }
        }
        if (changed) {
            w.setItems(items);
            event.markForReEncode(true);
        }
    }

    private void setSlot(PacketSendEvent event, Player player) {
        WrapperPlayServerSetSlot w = new WrapperPlayServerSetSlot(event);
        int window = w.getWindowId();
        boolean ok;
        if (window <= 0) {
            ok = feature.worthIn("inventory"); // the player's inventory, or the cursor
        } else {
            Inventory top = player.getOpenInventory().getTopInventory();
            ok = w.getSlot() >= top.getSize() ? feature.worthIn("inventory") : topAllows(top, w.getSlot());
        }
        if (!ok) return;
        ItemStack decorated = decorate(player, w.getItem());
        if (decorated == null) return;
        w.setItem(decorated);
        event.markForReEncode(true);
    }

    private void cursor(PacketSendEvent event, Player player) {
        if (!feature.worthIn("inventory")) return;
        WrapperPlayServerSetCursorItem w = new WrapperPlayServerSetCursorItem(event);
        ItemStack decorated = decorate(player, w.getStack());
        if (decorated == null) return;
        w.setStack(decorated);
        event.markForReEncode(true);
    }

    private void playerInventory(PacketSendEvent event, Player player) {
        if (!feature.worthIn("inventory")) return;
        WrapperPlayServerSetPlayerInventory w = new WrapperPlayServerSetPlayerInventory(event);
        ItemStack decorated = decorate(player, w.getStack());
        if (decorated == null) return;
        w.setStack(decorated);
        event.markForReEncode(true);
    }

    /**
     * Chests, barrels, shulker boxes, hoppers, minecarts, donkeys, ender chests: yes. The sell
     * menu: its item slots. Any other menu (VexCore's or another plugin's): never, their buttons
     * are not items for sale.
     */
    private boolean topAllows(Inventory top, int slot) {
        InventoryHolder holder = top.getHolder(false);
        if (holder instanceof Menu menu) return menu.feature() == feature && menu.isEditableSlot(slot) && feature.worthIn("sell-menu");
        boolean container = holder instanceof org.bukkit.block.Container || holder instanceof DoubleChest || holder instanceof Entity;
        return container && feature.worthIn("containers");
    }

    /** A copy of the stack with the worth lines, or null when it gets none. */
    private ItemStack decorate(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Material material = materials.computeIfAbsent(stack.getType(),
                t -> Optional.ofNullable(Material.matchMaterial(t.getName().toString()))).orElse(null);
        if (material == null || !material.isItem()) return null;
        List<Component> lines = feature.worthLines(player, facts(material, stack));
        if (lines == null || lines.isEmpty()) return null;
        ItemStack copy = stack.copy();
        ItemLore lore = copy.getComponentOr(ComponentTypes.LORE, null);
        List<Component> all = new ArrayList<>(lore == null ? List.of() : lore.getLines());
        all.addAll(lines);
        copy.setComponent(ComponentTypes.LORE, new ItemLore(all));
        NBTCompound data = copy.getComponentOr(ComponentTypes.CUSTOM_DATA, null);
        NBTCompound marked = data == null ? new NBTCompound() : data.copy();
        marked.setTag(MARK, new NBTInt(lines.size()));
        copy.setComponent(ComponentTypes.CUSTOM_DATA, marked);
        return copy;
    }

    /** What the sell rules look at, read from the packet's copy of the item. */
    private static SellFeature.Facts facts(Material material, ItemStack s) {
        Map<ComponentType<?>, Optional<?>> patches = s.getComponents().getPatches();
        boolean custom = present(patches, ComponentTypes.CUSTOM_NAME) || present(patches, ComponentTypes.ITEM_NAME)
                || present(patches, ComponentTypes.CUSTOM_MODEL_DATA_LISTS) || present(patches, ComponentTypes.CUSTOM_MODEL_DATA)
                || present(patches, ComponentTypes.ITEM_MODEL);
        Optional<?> lore = patches.get(ComponentTypes.LORE);
        if (!custom && lore != null && lore.orElse(null) instanceof ItemLore l) custom = !l.getLines().isEmpty();
        ItemEnchantments enchantments = s.getComponentOr(ComponentTypes.ENCHANTMENTS, null);
        int damage = s.getComponentOr(ComponentTypes.DAMAGE, 0);
        int max = s.getComponentOr(ComponentTypes.MAX_DAMAGE, 0);
        if (damage > 0 && max <= 0) max = material.getMaxDurability();
        BundleContents bundle = s.getComponentOr(ComponentTypes.BUNDLE_CONTENTS, null);
        ItemContainerContents container = s.getComponentOr(ComponentTypes.CONTAINER, null);
        boolean contents = (bundle != null && !bundle.getItems().isEmpty())
                || (container != null && container.getItems().stream().anyMatch(i -> i != null && !i.isEmpty()));
        return new SellFeature.Facts(material, s.getAmount(), custom, enchantments != null && !enchantments.isEmpty(), damage, max, contents);
    }

    private static boolean present(Map<ComponentType<?>, Optional<?>> patches, ComponentType<?> type) {
        Optional<?> v = patches.get(type);
        return v != null && v.isPresent();
    }

    // ── In: creative mode sends items back as shown; take the lines off ────

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) return;
        try {
            WrapperPlayClientCreativeInventoryAction w = new WrapperPlayClientCreativeInventoryAction(event);
            ItemStack clean = strip(w.getItemStack());
            if (clean == null) return;
            w.setItemStack(clean);
            event.markForReEncode(true);
        } catch (RuntimeException ignored) {
            event.markForReEncode(false);
        }
    }

    /** The stack without the added lines and mark, or null when it has none. */
    static ItemStack strip(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        NBTCompound data = stack.getComponentOr(ComponentTypes.CUSTOM_DATA, null);
        NBTNumber mark = data == null ? null : data.getNumberTagOrNull(MARK);
        if (mark == null) return null;
        ItemStack copy = stack.copy();
        NBTCompound rest = data.copy();
        rest.removeTag(MARK);
        if (rest.isEmpty()) copy.getComponents().getPatches().remove(ComponentTypes.CUSTOM_DATA);
        else copy.setComponent(ComponentTypes.CUSTOM_DATA, rest);
        ItemLore lore = copy.getComponentOr(ComponentTypes.LORE, null);
        if (lore != null) {
            List<Component> lines = new ArrayList<>(lore.getLines());
            int keep = Math.max(0, lines.size() - Math.max(0, mark.getAsInt()));
            if (keep == 0) copy.getComponents().getPatches().remove(ComponentTypes.LORE);
            else copy.setComponent(ComponentTypes.LORE, new ItemLore(new ArrayList<>(lines.subList(0, keep))));
        }
        return copy;
    }
}
