package com.vexorstudios.vexcore.features.sell;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.gui.Menu;
import com.vexorstudios.vexcore.gui.Slots;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Selling items for money: /sell (a menu to put items in), /sell hand, /sell all, /worth.
 *
 * <p>Prices are per item and set in config.yml; anything without a price can't be sold. Items
 * are only taken once the money is paid: if the payment fails (no economy, frozen balance, data
 * still loading, maximum balance) every item stays or goes back where it was. Containers that
 * hold items (shulker boxes, bundles) are never sold, so their contents can't be lost.
 */
public final class SellFeature extends Feature {

    /** What one stack is worth, or why it can't be sold. */
    record Price(double each, double total, String refusal) {
        boolean sellable() {
            return refusal == null;
        }
    }

    /** A sale: what was taken, and the money. */
    private record Sale(int items, double money) {
    }

    private final Map<Material, Double> prices = new EnumMap<>(Material.class);

    @Override
    protected void enable() {
        prices.clear();
        ConfigurationSection section = config().getConfigurationSection("prices");
        if (section != null) for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            double price = section.getDouble(key, -1);
            if (material == null || !material.isItem()) {
                problems().add("features/sell/config.yml: prices." + key + ": unknown item");
            } else if (price <= 0 || !Double.isFinite(price)) {
                problems().add("features/sell/config.yml: prices." + key + ": the price must be above 0");
            } else {
                prices.put(material, price);
            }
        }
        if (prices.isEmpty()) problems().add("features/sell/config.yml: no prices, nothing can be sold");
        String damaged = config().getString("damaged", "SCALE").toUpperCase(Locale.ROOT);
        if (!List.of("FULL", "SCALE", "NONE").contains(damaged)) {
            problems().add("features/sell/config.yml: damaged must be FULL, SCALE or NONE (not '" + damaged + "')");
        }

        command("sell", this::sellCommand, (s, a) -> a.length == 1 ? List.of("hand", "all") : List.of());
        command("worth", this::worthCommand, (s, a) -> {
            if (a.length != 1) return List.of();
            List<String> names = new ArrayList<>();
            for (Material m : prices.keySet()) names.add(m.name().toLowerCase(Locale.ROOT));
            return names;
        });
        placeholder("sell_multiplier", (p, a) -> p.getPlayer() == null ? "1" : Numbers.full(multiplier(p.getPlayer()), 2, ""));
    }

    // ── Prices ────────────────────────────────────────────────────────────

    private int decimals() {
        return Math.max(0, Math.min(8, config().getInt("decimals", 2)));
    }

    /** The highest multiplier the player has a permission for (1 when none). */
    double multiplier(Player player) {
        double best = 1;
        ConfigurationSection section = config().getConfigurationSection("multipliers");
        if (section != null) for (String key : section.getKeys(false)) {
            String permission = section.getString(key + ".permission", "");
            double value = section.getDouble(key + ".value", 1);
            if (value > best && Double.isFinite(value) && !permission.isEmpty() && player.hasPermission(permission)) best = value;
        }
        return best;
    }

    /** The worth of a stack for this player, multiplier included. */
    Price price(Player player, ItemStack item) {
        if (item == null || item.isEmpty()) return new Price(0, 0, "empty");
        Double base = prices.get(item.getType());
        if (base == null) return new Price(0, 0, "no-price");
        double each = base;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (hasContents(meta)) return new Price(0, 0, "has-contents");
            boolean custom = meta.hasCustomName() || meta.hasItemName() || meta.hasLore()
                    || meta.hasCustomModelDataComponent() || meta.hasItemModel();
            if (custom && !config().getBoolean("sell-custom-items", false)) return new Price(0, 0, "custom");
            if (meta.hasEnchants() && !config().getBoolean("sell-enchanted", true)) return new Price(0, 0, "enchanted");
            if (meta instanceof Damageable d && d.hasDamage()) {
                int max = d.hasMaxDamage() ? d.getMaxDamage() : item.getType().getMaxDurability();
                switch (config().getString("damaged", "SCALE").toUpperCase(Locale.ROOT)) {
                    case "NONE" -> {
                        return new Price(0, 0, "damaged");
                    }
                    case "FULL" -> {
                    }
                    default -> {
                        if (max > 0) each *= Math.max(0, max - d.getDamage()) / (double) max;
                    }
                }
            }
        }
        each *= multiplier(player);
        double total = Numbers.round(each * item.getAmount(), decimals());
        if (total <= 0) return new Price(0, 0, "worthless");
        return new Price(each, total, null);
    }

    /** Shulker boxes, chests, bundles... that still hold something: never sold. */
    private static boolean hasContents(ItemMeta meta) {
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) return true;
        if (meta instanceof BlockStateMeta block && block.hasBlockState() && block.getBlockState() instanceof Container container) {
            for (ItemStack inside : container.getSnapshotInventory().getContents()) {
                if (inside != null && !inside.isEmpty()) return true;
            }
        }
        return false;
    }

    // ── Selling ───────────────────────────────────────────────────────────

    private boolean usable(Player player) {
        if (!ready(player)) return false;
        if (!plugin.money().available()) {
            msg(player, "no-economy");
            return false;
        }
        return true;
    }

    /**
     * Sells every sellable stack in {@code slots} of {@code inventory}. The stacks are taken
     * out, then paid for; if the payment fails they are all put back. Returns null if the
     * payment failed (after telling the player).
     */
    private Sale sell(Player player, Inventory inventory, List<Integer> slots) {
        Map<Integer, ItemStack> taken = new java.util.LinkedHashMap<>();
        double money = 0;
        int items = 0;
        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            Price p = price(player, item);
            if (!p.sellable()) continue;
            taken.put(slot, item.clone());
            money += p.total;
            items += item.getAmount();
        }
        money = Numbers.round(money, decimals());
        if (taken.isEmpty() || money <= 0) return new Sale(0, 0);
        for (int slot : taken.keySet()) inventory.setItem(slot, null);
        if (!plugin.money().deposit(player, money)) {
            for (Map.Entry<Integer, ItemStack> e : taken.entrySet()) inventory.setItem(e.getKey(), e.getValue());
            msg(player, "payment-failed");
            return null;
        }
        return new Sale(items, money);
    }

    private void report(Player player, Sale sale) {
        if (sale == null) return;
        if (sale.items == 0) {
            msg(player, "nothing");
            return;
        }
        if (plugin.features().get("quests") instanceof com.vexorstudios.vexcore.features.quests.QuestsFeature quests) {
            quests.add(player, "SELL_ITEMS", null, sale.items);
        }
        msg(player, "sold", "amount", sale.items, "money", plugin.money().format(sale.money),
                "multiplier", Numbers.full(multiplier(player), 2, ""));
    }

    private void sellCommand(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> openMenu(player);
            case "hand" -> {
                if (!usable(player)) return;
                PlayerInventory inventory = player.getInventory();
                int slot = inventory.getHeldItemSlot();
                Price p = price(player, inventory.getItem(slot));
                if (!p.sellable()) {
                    refuse(player, inventory.getItem(slot), p);
                    return;
                }
                report(player, sell(player, inventory, List.of(slot)));
            }
            case "all" -> {
                if (!usable(player)) return;
                boolean hotbar = config().getBoolean("sell-all.hotbar", true);
                List<Integer> slots = new ArrayList<>();
                for (int i = hotbar ? 0 : 9; i < 36; i++) slots.add(i);
                report(player, sell(player, player.getInventory(), slots));
            }
            default -> usage(player, "sell");
        }
    }

    /** Tells the player why an item can't be sold. */
    private void refuse(Player player, ItemStack item, Price p) {
        if (item == null || item.isEmpty()) {
            msg(player, "hand-empty");
            return;
        }
        msg(player, "not-sellable", "item", name(item), "reason", config().getString("reasons." + p.refusal, p.refusal));
    }

    private static String name(ItemStack item) {
        String raw = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder(raw.length());
        boolean upper = true;
        for (char c : raw.toCharArray()) {
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = c == ' ';
        }
        return out.toString();
    }

    // ── Worth ─────────────────────────────────────────────────────────────

    private void worthCommand(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        ItemStack item;
        if (args.length > 0) {
            Material material = Material.matchMaterial(args[0]);
            if (material == null || !material.isItem()) {
                msg(player, "unknown-item", "item", args[0]);
                return;
            }
            item = new ItemStack(material, Math.max(1, material.getMaxStackSize()));
        } else {
            item = player.getInventory().getItemInMainHand();
        }
        Price p = price(player, item);
        if (!p.sellable()) {
            refuse(player, item, p);
            return;
        }
        msg(player, "worth", "item", name(item), "count", item.getAmount(),
                "each", plugin.money().format(p.each), "total", plugin.money().format(p.total),
                "multiplier", Numbers.full(multiplier(player), 2, ""));
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    private void openMenu(Player player) {
        if (!usable(player)) return;
        open(player, "sell", menu -> {
            List<Integer> slots = Slots.parse(menu.file().yml().get("sell-slots"));
            menu.editable(slots);
            menu.with("multiplier", Numbers.full(multiplier(player), 2, ""));
            menu.function("sell", click -> report(player, sell(player, menu.getInventory(), slots)));
            menu.onClose(this::closed);
        });
    }

    /**
     * The sell menu closed (by the player, a disconnect, death, or a reload). Sells what can be
     * sold if {@code sell-on-close} is on; everything else goes back to the player.
     */
    private void closed(Menu menu) {
        Player player = menu.viewer();
        Inventory inventory = menu.getInventory();
        List<Integer> slots = menu.editableSlots();
        // During a reload or shutdown the player's data is already saved and unloaded: just give
        // everything back instead of trying to pay.
        if (config().getBoolean("sell-on-close", true) && plugin.money().available()
                && plugin.data().isLoaded(player.getUniqueId())) {
            Sale sale = sell(player, inventory, slots);
            if (sale != null && sale.items > 0) report(player, sale);
        }
        boolean returned = false;
        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            inventory.setItem(slot, null);
            Menu.giveBack(player, item);
            returned = true;
        }
        if (returned) msg(player, "returned");
    }
}
