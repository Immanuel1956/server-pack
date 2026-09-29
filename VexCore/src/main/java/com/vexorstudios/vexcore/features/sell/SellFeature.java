package com.vexorstudios.vexcore.features.sell;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.gui.Menu;
import com.vexorstudios.vexcore.gui.Slots;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Selling items for money: /sell (a menu to put items in), /sell hand, /sell all, /worth, and
 * /sell price for staff to set or take out prices.
 *
 * <p>Prices are per item, in features/sell/prices.yml (every item a survival player can get has
 * one). Items are only taken once the money is paid: if the payment fails (no economy, frozen
 * balance, data still loading, maximum balance) every item stays or goes back where it was.
 * Containers that hold items (shulker boxes, bundles) are never sold, so their contents can't be
 * lost. The sell menu refuses what can't be sold (with a sound and a message) and shows the total
 * of what is in it on the middle item.
 *
 * <p>Worth in tooltips ({@code worth-lore}) is added by {@link WorthLore} to what the player's
 * game is sent, never to the item itself: items stay exactly as they are and keep stacking.
 */
public final class SellFeature extends Feature implements Listener {

    static final String PRICES = "features/sell/prices.yml";

    /** What one stack is worth, or why it can't be sold. */
    record Price(double each, double total, String refusal) {
        boolean sellable() {
            return refusal == null;
        }
    }

    /** What decides an item's price; read from a real item or from the copy a packet carries. */
    record Facts(Material type, int amount, boolean custom, boolean enchanted, int damage, int maxDamage, boolean contents) {
    }

    /** A sale: what was taken, and the money. */
    private record Sale(int items, double money) {
    }

    /** Replaced as a whole (never changed in place): WorthLore reads it from network threads. */
    private volatile Map<Material, Double> prices = Map.of();
    private volatile Set<Material> unobtainable = Set.of();
    private final Object filing = new Object();
    private volatile Object worthHook;
    /** worth-lore settings, read once (tooltips are built for every item in every inventory packet). */
    private volatile Set<String> worthHiddenModes = Set.of();
    private volatile Set<String> worthPlaces = Set.of();

    @Override
    protected void enable() {
        loadPrices();
        String damaged = config().getString("damaged", "SCALE").toUpperCase(Locale.ROOT);
        if (!List.of("FULL", "SCALE", "NONE").contains(damaged)) {
            problems().add("features/sell/config.yml: damaged must be FULL, SCALE or NONE (not '" + damaged + "')");
        }
        listen(this);
        command("sell", this::sellCommand, (s, a) -> {
            if (a.length == 1) return s.hasPermission(adminPermission()) ? List.of("hand", "all", "price") : List.of("hand", "all");
            if (a.length == 2 && a[0].equalsIgnoreCase("price") && s.hasPermission(adminPermission())) return List.of("remove", "10", "100");
            if (a.length == 3 && a[0].equalsIgnoreCase("price") && s.hasPermission(adminPermission())) return itemNames(a[2]);
            return List.of();
        });
        command("worth", this::worthCommand, (s, a) -> {
            if (a.length != 1) return List.of();
            List<String> names = itemNames(a[0]);
            if ("toggle".startsWith(a[0].toLowerCase(Locale.ROOT))) names.add(0, "toggle");
            return names;
        });
        toggle("worth", true, p -> {
            flip(p, "worth", "worth-on", "worth-off");
            refreshTooltips(p);
        });
        placeholder("sell_multiplier", (p, a) -> p.getPlayer() == null ? "1" : Numbers.full(multiplier(p.getPlayer()), 2, ""));
        hookWorth();
    }

    @Override
    protected void disable() {
        Object hook = worthHook;
        worthHook = null;
        if (hook != null) {
            WorthLore.unregister(hook);
            // Tooltips go back to plain (only while the server runs; not at shutdown).
            if (plugin.isEnabled()) for (Player p : Bukkit.getOnlinePlayers()) refreshTooltips(p);
        }
    }

    private String adminPermission() {
        return config().getString("admin-permission", "vexcore.sell.admin");
    }

    private List<String> itemNames(String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Material m : prices.keySet()) {
            String n = m.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(t)) names.add(n);
            if (names.size() >= 50) break;
        }
        Collections.sort(names);
        return names;
    }

    // ── Prices ────────────────────────────────────────────────────────────

    /**
     * prices.yml as written (a line someone deleted is gone, not put back from the jar), then any
     * prices: still in config.yml from before prices.yml existed.
     */
    private void loadPrices() {
        Map<Material, Double> map = new EnumMap<>(Material.class);
        Set<Material> listed = EnumSet.noneOf(Material.class);
        read(plugin.files().menu(PRICES), PRICES, map, listed);
        // prices: in config.yml is from before prices.yml: it only fills in what prices.yml doesn't
        // list, so prices.yml decides (an old price there could make crafting pay).
        ConfigurationSection legacy = config().getConfigurationSection("prices");
        if (legacy != null && !legacy.getKeys(false).isEmpty()) {
            Map<Material, Double> old = new EnumMap<>(Material.class);
            read(legacy, "features/sell/config.yml: prices", old, EnumSet.noneOf(Material.class));
            old.keySet().removeAll(listed);
            map.putAll(old);
            problems().add("features/sell/config.yml: prices: is replaced by prices.yml (it only fills in items prices.yml "
                    + "doesn't list); delete it from config.yml");
        }
        Set<Material> never = EnumSet.noneOf(Material.class);
        for (String pattern : config().getStringList("unobtainable")) {
            String p = pattern.trim().toUpperCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            boolean found = false;
            for (Material m : Material.values()) {
                if (!m.isLegacy() && matches(m.name(), p)) {
                    never.add(m);
                    found = true;
                }
            }
            if (!found) problems().add("features/sell/config.yml: unobtainable: '" + pattern + "' matches no item");
        }
        unobtainable = Collections.unmodifiableSet(never);
        prices = Collections.unmodifiableMap(map);
        if (map.isEmpty()) problems().add(PRICES + ": no prices, nothing can be sold");
    }

    private void read(ConfigurationSection section, String where, Map<Material, Double> into, Set<Material> listed) {
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            double price = section.getDouble(key, -1);
            if (material != null) listed.add(material);
            if (material == null || !material.isItem()) {
                problems().add(where + ": " + key + ": unknown item");
            } else if (price == 0) {
                into.remove(material); // 0: taken out
            } else if (price < 0 || !Double.isFinite(price)) {
                problems().add(where + ": " + key + ": the price must be above 0 (0 takes the item out)");
            } else {
                into.put(material, price);
            }
        }
    }

    /** "*_SPAWN_EGG" style patterns; * matches anything. */
    static boolean matches(String name, String pattern) {
        if (!pattern.contains("*")) return name.equals(pattern);
        String regex = java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return name.matches(regex);
    }

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

    /** The facts of a real item. */
    static Facts facts(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return new Facts(item.getType(), item.getAmount(), false, false, 0, 0, false);
        boolean custom = meta.hasCustomName() || meta.hasItemName() || meta.hasLore()
                || meta.hasCustomModelDataComponent() || meta.hasItemModel();
        int damage = 0, max = 0;
        if (meta instanceof Damageable d && d.hasDamage()) {
            damage = d.getDamage();
            max = d.hasMaxDamage() ? d.getMaxDamage() : item.getType().getMaxDurability();
        }
        return new Facts(item.getType(), item.getAmount(), custom, meta.hasEnchants(), damage, max, hasContents(meta));
    }

    /** The worth of a stack for this player, multiplier included. */
    Price price(Player player, ItemStack item) {
        if (item == null || item.isEmpty()) return new Price(0, 0, "empty");
        return price(player, facts(item));
    }

    Price price(Player player, Facts f) {
        if (f == null || f.type() == null || f.type().isAir() || f.amount() <= 0) return new Price(0, 0, "empty");
        if (unobtainable.contains(f.type())) return new Price(0, 0, "unobtainable");
        Double base = prices.get(f.type());
        if (base == null) return new Price(0, 0, "no-price");
        double each = base;
        if (f.contents()) return new Price(0, 0, "has-contents");
        if (f.custom() && !config().getBoolean("sell-custom-items", false)) return new Price(0, 0, "custom");
        if (f.enchanted() && !config().getBoolean("sell-enchanted", true)) return new Price(0, 0, "enchanted");
        if (f.damage() > 0) {
            switch (config().getString("damaged", "SCALE").toUpperCase(Locale.ROOT)) {
                case "NONE" -> {
                    return new Price(0, 0, "damaged");
                }
                case "FULL" -> {
                }
                default -> {
                    if (f.maxDamage() > 0) each *= Math.max(0, f.maxDamage() - f.damage()) / (double) f.maxDamage();
                }
            }
        }
        each *= multiplier(player);
        // Rounded down: rounding to the nearest cent would pay more for items sold one by one
        // than for the same items in one stack (0.006 each: 0.01 alone, 0.38 for 64).
        double total = Numbers.round(each * f.amount(), decimals(), java.math.RoundingMode.FLOOR);
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

    private String reason(Price p) {
        return config().getString("reasons." + p.refusal, p.refusal);
    }

    // ── Staff: /sell price ────────────────────────────────────────────────

    /** /sell price &lt;amount|remove&gt; [item]: the held item (or the one named) gets a price or loses it. */
    private void priceCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(adminPermission())) {
            msg(sender, "no-permission", "permission", adminPermission());
            return;
        }
        if (args.length < 2) {
            msg(sender, "price-usage");
            return;
        }
        Material material;
        if (args.length > 2) {
            material = Material.matchMaterial(args[2]);
        } else if (sender instanceof Player p && !p.getInventory().getItemInMainHand().isEmpty()) {
            material = p.getInventory().getItemInMainHand().getType();
        } else {
            msg(sender, "price-usage");
            return;
        }
        if (material == null || !material.isItem() || material.isAir()) {
            msg(sender, "unknown-item", "item", args.length > 2 ? args[2] : "?");
            return;
        }
        String item = name(new ItemStack(material));
        Double price;
        if (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("0")) {
            price = null;
        } else {
            double v = Numbers.parse(args[1]);
            if (!Double.isFinite(v) || v <= 0) {
                msg(sender, "price-usage");
                return;
            }
            price = Numbers.round(v, decimals());
        }
        Map<Material, Double> map = new EnumMap<>(Material.class);
        map.putAll(prices);
        if (price == null) map.remove(material);
        else map.put(material, price);
        prices = Collections.unmodifiableMap(map);
        writePrice(material, price);
        if (price == null) msg(sender, "price-removed", "item", item);
        else msg(sender, "price-set", "item", item, "price", plugin.money().format(price),
                "unobtainable", unobtainable.contains(material) ? config().getString("words.unobtainable-note", "") : "");
        for (Player p : Bukkit.getOnlinePlayers()) refreshTooltips(p);
    }

    /** Writes one price into prices.yml (comments stay), off the main thread. */
    private void writePrice(Material material, Double price) {
        File file = new File(plugin.files().dir(), PRICES);
        Scheduler.async(() -> {
            synchronized (filing) {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
                yml.set(material.name(), price);
                try {
                    yml.save(file);
                } catch (java.io.IOException e) {
                    plugin.getLogger().warning("Could not save " + file + ": " + e.getMessage());
                }
            }
        });
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

    private boolean blockedMode(Player player) {
        return config().getStringList("blocked-gamemodes").stream().anyMatch(m -> m.equalsIgnoreCase(player.getGameMode().name()));
    }

    /**
     * Sells every sellable stack in {@code slots} of {@code inventory}. The stacks are taken
     * out, then paid for; if the payment fails they are all put back. Returns null if the
     * payment failed (after telling the player).
     */
    private Sale sell(Player player, Inventory inventory, List<Integer> slots) {
        // Creative items cost nothing to make: selling them would be unlimited money.
        if (blockedMode(player)) {
            msg(player, "gamemode-blocked", "gamemode", player.getGameMode().name().toLowerCase(Locale.ROOT));
            return null;
        }
        Map<Integer, ItemStack> taken = new LinkedHashMap<>();
        double money = 0;
        int items = 0;
        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            Price p = price(player, item);
            if (!p.sellable()) continue;
            taken.put(slot, item.clone());
            money += p.each * item.getAmount(); // exact; rounded down once for the whole sale
            items += item.getAmount();
        }
        money = Numbers.round(money, decimals(), java.math.RoundingMode.FLOOR);
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
        msg(player, "sold", "amount", Numbers.format(sale.items), "money", plugin.money().format(sale.money),
                "multiplier", Numbers.full(multiplier(player), 2, ""));
    }

    private void sellCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("price")) {
            priceCommand(sender, args);
            return;
        }
        Player player = player(sender);
        if (player == null) return;
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
        msg(player, "not-sellable", "item", name(item), "reason", reason(p));
    }

    static String name(ItemStack item) {
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
        if (args.length > 0 && args[0].equalsIgnoreCase("toggle")) {
            flip(player, "worth", "worth-on", "worth-off");
            refreshTooltips(player);
            return;
        }
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

    // ── Worth in tooltips (only what the player's game is shown) ──────────

    private void hookWorth() {
        if (!config().getBoolean("worth-lore.enabled", true)) return;
        Set<String> modes = new java.util.HashSet<>();
        for (String m : config().getStringList("worth-lore.hidden-gamemodes")) modes.add(m.trim().toUpperCase(Locale.ROOT));
        worthHiddenModes = Set.copyOf(modes);
        Set<String> places = new java.util.HashSet<>();
        for (String place : List.of("inventory", "containers", "sell-menu")) {
            if (config().getBoolean("worth-lore.show-in." + place, true)) places.add(place);
        }
        worthPlaces = Set.copyOf(places);
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            problems().add("features/sell/config.yml: worth-lore needs the PacketEvents plugin; without it no worth is shown in tooltips");
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            worthHook = WorthLore.register(this);
            for (Player p : Bukkit.getOnlinePlayers()) refreshTooltips(p);
        } else {
            // Still starting: hook in once the server has finished loading plugins.
            Scheduler.globalLater(() -> {
                if (isEnabled() && worthHook == null && Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
                    worthHook = WorthLore.register(this);
                }
            }, 1);
        }
    }

    /** Whether this player's tooltips get the worth lines (read from network threads). */
    boolean worthShownTo(Player player) {
        if (!isEnabled() || worthHook == null) return false;
        if (worthHiddenModes.contains(player.getGameMode().name())) return false;
        return plugin.toggles().isOn(player.getUniqueId(), "worth");
    }

    boolean worthIn(String place) {
        return worthPlaces.contains(place);
    }

    /** The lines added under an item's tooltip, or null for none. */
    List<Component> worthLines(Player player, Facts f) {
        Price p = price(player, f);
        List<String> lines;
        Map<String, Object> ph = new HashMap<>();
        if (p.sellable()) {
            List<String> single = config().getStringList("worth-lore.single-lines");
            lines = f.amount() == 1 && !single.isEmpty() ? single : config().getStringList("worth-lore.lines");
            ph.put("worth", plugin.money().format(p.total));
            ph.put("each", plugin.money().format(Numbers.round(p.each, decimals(), java.math.RoundingMode.FLOOR)));
            ph.put("amount", f.amount());
            ph.put("multiplier", Numbers.full(multiplier(player), 2, ""));
        } else {
            if (p.refusal.equals("empty")) return null;
            lines = config().getStringList("worth-lore.unsellable-lines");
            ph.put("reason", reason(p));
        }
        if (lines.isEmpty()) return null;
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(Text.parse(line, null, ph).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        return out;
    }

    /** Sends the player's items again, so tooltips follow a change (toggle, game mode, prices). */
    private void refreshTooltips(Player player) {
        onPlayerThread(player, player::updateInventory);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        // Into creative the lines must be gone: creative sends items back as the game shows them.
        Player p = event.getPlayer();
        if (worthHook != null) Scheduler.entityLater(p, p::updateInventory, 1);
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    private void openMenu(Player player) {
        if (!usable(player)) return;
        open(player, "sell", menu -> {
            List<Integer> slots = Slots.parse(menu.file().yml().get("sell-slots"));
            menu.editable(slots);
            menu.with("multiplier", Numbers.full(multiplier(player), 2, ""));
            summary(menu, player, slots);
            // What can't be sold doesn't go in: it stays where it was, with an anvil sound.
            menu.accepts(item -> price(player, item).sellable(), item -> msg(player, "refused", "item", name(item), "reason", reason(price(player, item))));
            menu.onChange(m -> {
                summary(m, player, slots);
                m.redraw("summary");
                m.redraw("sell");
            });
            menu.function("sell", click -> {
                report(player, sell(player, menu.getInventory(), slots));
                summary(menu, player, slots);
                menu.redraw("summary");
                menu.redraw("sell");
            });
            menu.onClose(this::closed);
        });
    }

    /** The middle item's placeholders: %total% (Money Receive), %items%, %lines% (item by item). */
    private void summary(Menu menu, Player player, List<Integer> slots) {
        Inventory inv = menu.getInventory();
        Map<Material, int[]> counts = new LinkedHashMap<>();
        Map<Material, Double> money = new LinkedHashMap<>();
        double total = 0;
        int items = 0, refused = 0;
        if (inv != null) for (int slot : slots) {
            ItemStack item = slot < inv.getSize() ? inv.getItem(slot) : null;
            if (item == null || item.isEmpty()) continue;
            Price p = price(player, item);
            if (!p.sellable()) {
                refused += item.getAmount();
                continue;
            }
            counts.computeIfAbsent(item.getType(), k -> new int[1])[0] += item.getAmount();
            money.merge(item.getType(), p.each * item.getAmount(), Double::sum);
            total += p.each * item.getAmount();
            items += item.getAmount();
        }
        ConfigurationSection s = menu.file().yml().getConfigurationSection("summary");
        String lineFormat = s == null ? "&7• &f%amount%x %item% &7▸ &#80EE0B%money%" : s.getString("line", "&7• &f%amount%x %item% &7▸ &#80EE0B%money%");
        int max = s == null ? 8 : Math.max(0, s.getInt("max-lines", 8));
        StringBuilder lines = new StringBuilder();
        int shown = 0;
        for (Map.Entry<Material, int[]> e : counts.entrySet()) {
            if (shown++ >= max) break;
            if (!lines.isEmpty()) lines.append('\n');
            lines.append(Text.fill(lineFormat, Map.of("amount", Numbers.format(e.getValue()[0]), "item", name(new ItemStack(e.getKey())),
                    "money", plugin.money().format(Numbers.round(money.get(e.getKey()), decimals(), java.math.RoundingMode.FLOOR)))));
        }
        if (counts.size() > max) {
            lines.append('\n').append(Text.fill(s == null ? "&7...and %more% more" : s.getString("more", "&7...and %more% more"),
                    Map.of("more", counts.size() - max)));
        }
        if (counts.isEmpty()) lines.append(s == null ? "&7Nothing yet: put items above." : s.getString("empty", "&7Nothing yet: put items above."));
        menu.with("total", plugin.money().format(Numbers.round(total, decimals(), java.math.RoundingMode.FLOOR)))
                .with("items", Numbers.format(items))
                .with("kinds", counts.size())
                .with("refused", refused)
                .with("lines", lines.toString());
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
