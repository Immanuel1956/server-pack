package com.vexorstudios.vexcore.features.kits;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Time;
import com.vexorstudios.vexcore.gui.Actions;
import com.vexorstudios.vexcore.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kits: /kit opens the menu (left click claims, right click previews), /kit &lt;name&gt; claims.
 * Admin: /kit save|delete &lt;name&gt;, /kit give &lt;name&gt; &lt;player&gt;, /kit reset &lt;player&gt; [kit].
 *
 * <p>A kit's settings (name, icon, cooldown, permission, one-time, first-join, money, commands)
 * are in config.yml; its items are saved in game with /kit save and kept in data/kits.yml,
 * so any item (enchanted, custom, from other plugins) works exactly as it was. A claim is
 * written the moment it is made, before anything is handed out. A kit that doesn't fit is
 * refused (or dropped, if configured): items are never lost.
 */
public final class KitsFeature extends Feature implements PlayerData.Store, Listener {

    private static final Set<String> RESERVED = Set.of("save", "delete", "give", "reset", "preview");

    record Kit(String key, String name, String icon, List<String> description, String permission, long cooldown,
               boolean oneTime, boolean firstJoin, double money, List<String> commands) {
    }

    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<String, String> stored = new ConcurrentHashMap<>();
    private final Map<String, ItemStack[]> itemCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> claims = new ConcurrentHashMap<>();
    private final Set<UUID> newPlayers = ConcurrentHashMap.newKeySet();
    private File file;

    @Override
    protected void enable() {
        file = plugin.files().data("kits.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection saved = data.getConfigurationSection("kits");
        if (saved != null) for (String key : saved.getKeys(false)) stored.put(key.toLowerCase(Locale.ROOT), saved.getString(key, ""));

        // Every kit in config.yml, plus kits that only have saved items (admin-only until configured).
        Set<String> keys = new LinkedHashSet<>();
        ConfigurationSection section = config().getConfigurationSection("kits");
        if (section != null) for (String key : section.getKeys(false)) keys.add(key.toLowerCase(Locale.ROOT));
        keys.addAll(stored.keySet());
        for (String key : keys) {
            if (RESERVED.contains(key)) {
                problems().add("features/kits/config.yml: a kit can't be called '" + key + "'");
                continue;
            }
            ConfigurationSection s = section == null ? null : find(section, key);
            long cooldown = s == null ? 0 : Math.max(0, Time.seconds(s.getString("cooldown", "0")));
            if (s != null && Time.seconds(s.getString("cooldown", "0")) < 0) {
                problems().add("features/kits/config.yml: kits." + key + ".cooldown is not a duration");
            }
            if (!config().getBoolean("cooldowns-enabled", true) || (s != null && !s.getBoolean("cooldown-enabled", true))) cooldown = 0;
            kits.put(key, s == null
                    ? new Kit(key, key, "CHEST", List.of(), "vexcore.kit." + key, 0, false, false, 0, List.of())
                    : new Kit(key, s.getString("name", key), s.getString("icon", "CHEST"), ownList(s, "description"),
                    s.getString("permission", ""), cooldown, s.getBoolean("one-time", false), s.getBoolean("first-join", false),
                    Math.max(0, s.getDouble("money", 0)), ownList(s, "commands")));
            if (s != null && !stored.containsKey(key) && s.getDouble("money", 0) <= 0 && s.getStringList("commands").isEmpty()) {
                problems().add("features/kits: kit '" + key + "' has no items yet (hold them and use /kit save " + key + ")");
            }
            if (s == null) problems().add("features/kits: kit '" + key + "' has items but no settings in config.yml (only vexcore.kit." + key + " can use it)");
        }

        db().schema("kit_claims", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, kit VARCHAR(64) NOT NULL, "
                + "time BIGINT NOT NULL, PRIMARY KEY (uuid, kit))");
        store(this);
        listen(this);
        command("kit", this::command, this::complete);
        placeholder("kit", (p, kit) -> {
            Player player = p.getPlayer();
            Kit k = kits.get(kit.toLowerCase(Locale.ROOT));
            if (player == null || k == null) return "";
            return status(player, k);
        });
    }

    private static ConfigurationSection find(ConfigurationSection section, String key) {
        for (String k : section.getKeys(false)) if (k.equalsIgnoreCase(key)) return section.getConfigurationSection(k);
        return null;
    }

    // ── Items ─────────────────────────────────────────────────────────────

    /** Copies of a kit's items (empty if none are saved or they can't be read). */
    private List<ItemStack> items(Kit kit) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : saved(kit)) if (item != null && !item.isEmpty()) out.add(item.clone());
        return out;
    }

    private int itemCount(Kit kit) {
        int n = 0;
        for (ItemStack item : saved(kit)) if (item != null && !item.isEmpty()) n++;
        return n;
    }

    /** The saved items themselves (never hand these out: {@link #items} copies them). */
    private ItemStack[] saved(Kit kit) {
        String base64 = stored.get(kit.key);
        if (base64 == null || base64.isEmpty()) return new ItemStack[0];
        return itemCache.computeIfAbsent(kit.key, k -> {
            try {
                return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(base64));
            } catch (RuntimeException error) {
                plugin.getLogger().severe("data/kits.yml: the items of kit '" + k + "' can't be read: " + error.getMessage());
                return new ItemStack[0];
            }
        });
    }

    private synchronized void writeItems(String key, String base64) {
        if (base64 == null) stored.remove(key);
        else stored.put(key, base64);
        itemCache.remove(key);
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        data.set("kits." + key, base64);
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + file + ": " + e.getMessage());
        }
    }

    /** True when every item fits: armour into empty armour slots (if auto-equip), the rest into storage. */
    private boolean fits(Player player, List<ItemStack> items) {
        PlayerInventory inventory = player.getInventory();
        Inventory copy = Bukkit.createInventory(null, 36);
        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < Math.min(36, storage.length); i++) if (storage[i] != null) copy.setItem(i, storage[i].clone());
        Set<EquipmentSlot> taken = new java.util.HashSet<>();
        for (ItemStack item : items) {
            EquipmentSlot slot = armourSlot(inventory, item);
            if (slot != null && taken.add(slot)) continue;
            if (!copy.addItem(item.clone()).isEmpty()) return false;
        }
        return true;
    }

    /** The empty armour slot this item goes into, or null. */
    private EquipmentSlot armourSlot(PlayerInventory inventory, ItemStack item) {
        if (!config().getBoolean("auto-equip", true)) return null;
        EquipmentSlot slot = item.getType().getEquipmentSlot();
        // Only a player's own four armour slots (BODY is animal armour and would throw).
        if (slot != EquipmentSlot.HEAD && slot != EquipmentSlot.CHEST && slot != EquipmentSlot.LEGS && slot != EquipmentSlot.FEET) return null;
        ItemStack there = inventory.getItem(slot);
        return there == null || there.isEmpty() ? slot : null;
    }

    /** Hands the items over. Whatever doesn't fit drops at the player's feet, never lost. */
    private void give(Player player, List<ItemStack> items) {
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : items) {
            EquipmentSlot slot = armourSlot(inventory, item);
            if (slot != null) inventory.setItem(slot, item);
            else Menu.giveBack(player, item);
        }
    }

    // ── Claims ────────────────────────────────────────────────────────────

    private Map<String, Long> claimsOf(UUID player) {
        return claims.getOrDefault(player, Map.of());
    }

    /** Seconds until the kit can be claimed again (0 = now). */
    private long left(Player player, Kit kit) {
        Long last = claimsOf(player.getUniqueId()).get(kit.key);
        return remaining(last, kit.cooldown, System.currentTimeMillis());
    }

    static long remaining(Long last, long cooldown, long now) {
        if (last == null || cooldown <= 0) return 0;
        return Math.max(0, (last + cooldown * 1000 - now + 999) / 1000);
    }

    private boolean used(Player player, Kit kit) {
        return kit.oneTime && claimsOf(player.getUniqueId()).containsKey(kit.key);
    }

    private boolean allowed(Player player, Kit kit) {
        return kit.permission.isEmpty() || player.hasPermission(kit.permission);
    }

    private String status(Player player, Kit kit) {
        if (!allowed(player, kit)) return config().getString("status.locked", "Locked");
        if (used(player, kit)) return config().getString("status.claimed", "Claimed");
        long left = left(player, kit);
        return left > 0 ? plugin.messages().time(left) : config().getString("status.ready", "Ready");
    }

    private void record(UUID player, String kit, long time) {
        Map<String, Long> own = claims.get(player);
        if (own != null) own.put(kit, time);
        Database db = db();
        db.queue("kit claim", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("kit_claims", new String[]{"uuid", "kit"}, "time"))) {
                ps.setString(1, player.toString());
                ps.setString(2, kit);
                ps.setLong(3, time);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Claims a kit. {@code checks} false skips permission, cooldown and one-time (first-join
     * kits); the claim is still recorded. Returns true if the kit was handed out.
     */
    private boolean claim(Player player, Kit kit, boolean checks) {
        if (!ready(player)) return false;
        Map<String, Object> ph = placeholders(player, kit);
        if (checks) {
            if (!allowed(player, kit)) {
                msg(player, "locked", ph);
                return false;
            }
            if (used(player, kit)) {
                msg(player, "already-claimed", ph);
                return false;
            }
            long left = left(player, kit);
            if (left > 0 && !player.hasPermission("vexcore.kit.bypass")) {
                msg(player, "cooldown", ph);
                return false;
            }
        }
        List<ItemStack> items = items(kit);
        boolean drop = "DROP".equalsIgnoreCase(config().getString("full-inventory", "REFUSE"));
        if (!drop && checks && !fits(player, items)) {
            msg(player, "no-space", ph);
            return false;
        }
        // Alt accounts: each kit once per network (one-time and first-join kits ever, the others
        // once per cooldown), or fresh accounts could farm them and pass the items on.
        var network = network(player, kit);
        if (network != com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.ALLOWED) {
            com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.deny(player, network, "kits");
            return false;
        }
        record(player.getUniqueId(), kit.key, System.currentTimeMillis()); // before anything is handed out
        hand(player, kit, items, ph);
        msg(player, "claimed", ph);
        return true;
    }

    /** IP protection for a kit: ALLOWED for kits anyone may take again and again (no cooldown). */
    private com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result network(Player player, Kit kit) {
        if (!kit.oneTime && !kit.firstJoin && kit.cooldown <= 0) return com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.ALLOWED;
        long window = kit.oneTime || kit.firstJoin ? 0 : kit.cooldown * 1000;
        return com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.claim(player, "kits", kit.key, window);
    }

    private void hand(Player player, Kit kit, List<ItemStack> items, Map<String, Object> ph) {
        give(player, items);
        if (kit.money > 0 && !plugin.money().deposit(player, kit.money)) msg(player, "economy-missing");
        Actions.run(player, kit.commands, ph, null);
    }

    private Map<String, Object> placeholders(Player player, Kit kit) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("player", player.getName());
        ph.put("kit", kit.name);
        ph.put("key", kit.key);
        ph.put("icon", kit.icon);
        ph.put("items", itemCount(kit));
        ph.put("time", plugin.messages().time(left(player, kit)));
        ph.put("cooldown", kit.cooldown > 0 ? plugin.messages().time(kit.cooldown) : config().getString("no-cooldown", "none"));
        ph.put("money", plugin.money().format(kit.money));
        ph.put("status", status(player, kit));
        ph.put("description", String.join("\n", kit.description));
        return ph;
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("vexcore.kit.admin")) return true;
        msg(sender, "no-permission", "permission", "vexcore.kit.admin");
        return false;
    }

    private Kit kit(CommandSender sender, String name) {
        Kit kit = kits.get(name.toLowerCase(Locale.ROOT));
        if (kit == null) msg(sender, "unknown-kit", "kit", name);
        return kit;
    }

    private void command(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                Player player = player(sender);
                if (player != null && ready(player)) openKits(player);
            }
            case "save" -> save(sender, args);
            case "delete" -> delete(sender, args);
            case "give" -> giveCommand(sender, args);
            case "reset" -> reset(sender, args);
            case "preview" -> {
                Player player = player(sender);
                if (player == null) return;
                if (args.length < 2) usage(player, "kit");
                else {
                    Kit kit = kit(player, args[1]);
                    if (kit != null) openPreview(player, kit);
                }
            }
            default -> {
                Player player = player(sender);
                if (player == null) return;
                Kit kit = kit(player, args[0]);
                if (kit != null) claim(player, kit, true);
            }
        }
    }

    private void save(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        Player player = player(sender);
        if (player == null) return;
        if (args.length < 2 || !args[1].matches("[A-Za-z0-9_-]{1,64}") || RESERVED.contains(args[1].toLowerCase(Locale.ROOT))) {
            msg(player, "usage-save");
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : inventory.getStorageContents()) if (item != null && !item.isEmpty()) items.add(item.clone());
        for (ItemStack item : inventory.getArmorContents()) if (item != null && !item.isEmpty()) items.add(item.clone());
        ItemStack offhand = inventory.getItemInOffHand();
        if (!offhand.isEmpty()) items.add(offhand.clone());
        if (items.isEmpty()) {
            msg(player, "save-empty");
            return;
        }
        writeItems(key, Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items)));
        ConfigurationSection section = config().getConfigurationSection("kits");
        boolean configured = section != null && find(section, key) != null;
        // A new kit works at once, for vexcore.kit.<name> only, until it gets settings in config.yml.
        kits.putIfAbsent(key, new Kit(key, key, "CHEST", List.of(), "vexcore.kit." + key, 0, false, false, 0, List.of()));
        msg(player, configured ? "saved" : "saved-new", "kit", key, "items", items.size());
    }

    private void delete(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 2) {
            usage(sender, "kit");
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        if (!stored.containsKey(key)) {
            msg(sender, "unknown-kit", "kit", args[1]);
            return;
        }
        writeItems(key, null);
        // A kit made with /kit save (no settings in config.yml) goes away completely.
        ConfigurationSection section = config().getConfigurationSection("kits");
        if (section == null || find(section, key) == null) kits.remove(key);
        msg(sender, "deleted", "kit", key);
    }

    private void giveCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 3) {
            usage(sender, "kit");
            return;
        }
        Kit kit = kit(sender, args[1]);
        if (kit == null) return;
        Player target = target(sender, args[2]);
        if (target == null) return;
        // An admin give ignores cooldowns and isn't recorded; items that don't fit drop.
        onPlayerThread(target, () -> {
            hand(target, kit, items(kit), placeholders(target, kit));
            msg(target, "given", placeholders(target, kit));
        });
        msg(sender, "gave", "kit", kit.name, "player", target.getName());
    }

    private void reset(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 2) {
            usage(sender, "kit");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            msg(sender, "unknown-player", "player", args[1]);
            return;
        }
        String only = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
        if (only != null && !kits.containsKey(only)) {
            msg(sender, "unknown-kit", "kit", args[2]);
            return;
        }
        Map<String, Long> own = claims.get(target.getUniqueId());
        if (own != null) {
            if (only == null) own.clear();
            else own.remove(only);
        }
        Database db = db();
        String uuid = target.getUniqueId().toString();
        db.queue("kit reset", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("kit_claims") + " WHERE uuid = ?"
                    + (only == null ? "" : " AND kit = ?"))) {
                ps.setString(1, uuid);
                if (only != null) ps.setString(2, only);
                ps.executeUpdate();
            }
        });
        msg(sender, "reset", "player", target.getName(), "kit", only == null ? config().getString("all-kits", "all kits") : only);
    }

    private List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("vexcore.kit.admin");
        if (args.length == 1) {
            for (Kit kit : kits.values()) if (!(sender instanceof Player p) || allowed(p, kit)) out.add(kit.key);
            out.add("preview");
            if (admin) out.addAll(List.of("save", "delete", "give", "reset"));
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("preview", "delete", "give", "save").contains(sub)) return new ArrayList<>(kits.keySet());
        if (args.length == 2 && sub.equals("reset")) return playerNames(sender);
        if (args.length == 3 && sub.equals("give")) return playerNames(sender);
        if (args.length == 3 && sub.equals("reset")) return new ArrayList<>(kits.keySet());
        return out;
    }

    // ── Menus ─────────────────────────────────────────────────────────────

    private void openKits(Player player) {
        open(player, "kits", menu -> {
            List<Kit> list = new ArrayList<>(kits.values());
            if (config().getBoolean("hide-locked", false)) list.removeIf(k -> !allowed(player, k));
            if (list.isEmpty()) menu.function("empty", c -> {
            });
            menu.paginate(list, (kit, slot) -> {
                String state = !allowed(player, kit) ? "locked" : used(player, kit) ? "claimed" : left(player, kit) > 0 ? "cooldown" : "ready";
                menu.place(state, slot, placeholders(player, kit), c -> {
                    if (c.type().isRightClick()) {
                        openPreview(player, kit);
                        return;
                    }
                    if (claim(player, kit, true) && config().getBoolean("close-on-claim", true)) player.closeInventory();
                    else menu.refresh();
                });
            });
        });
    }

    private void openPreview(Player player, Kit kit) {
        if (!allowed(player, kit) && !config().getBoolean("preview-locked", true)) {
            msg(player, "locked", placeholders(player, kit));
            return;
        }
        List<ItemStack> items = items(kit);
        open(player, "preview", menu -> {
            menu.with("kit", kit.name).with("key", kit.key).with("money", plugin.money().format(kit.money))
                    .with("cooldown", placeholders(player, kit).get("cooldown")).with("status", status(player, kit));
            menu.paginate(items, (item, slot) -> menu.set(slot, item, null));
            menu.function("back", c -> openKits(player));
            menu.function("claim", c -> {
                if (claim(player, kit, true)) player.closeInventory();
            });
        });
    }

    // ── First join ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().hasPlayedBefore()) newPlayers.add(event.getPlayer().getUniqueId());
    }

    @Override
    protected void loaded(Player player) {
        if (!newPlayers.remove(player.getUniqueId())) return;
        firstJoin(player, 0);
    }

    /**
     * First-join kits. Their network's claims load at join too; while they are still loading the
     * kits wait (every 2 seconds, up to 20 seconds) instead of being skipped.
     */
    private void firstJoin(Player player, int tries) {
        if (!isEnabled() || !player.isOnline()) return;
        for (Kit kit : kits.values()) {
            if (!kit.firstJoin || claimsOf(player.getUniqueId()).containsKey(kit.key)) continue;
            if (network(player, kit) == com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.LOADING && tries < 10) {
                com.vexorstudios.vexcore.core.Scheduler.entityLater(player, () -> firstJoin(player, tries + 1), 40);
                return;
            }
            claim(player, kit, false);
        }
    }

    // ── Player data ───────────────────────────────────────────────────────

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Map<String, Long> own = new ConcurrentHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT kit, time FROM " + db().table("kit_claims") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) own.put(rs.getString(1), rs.getLong(2));
            }
        }
        claims.put(player, own);
    }

    @Override
    public void unload(UUID player) {
        claims.remove(player);
        newPlayers.remove(player);
    }
}
