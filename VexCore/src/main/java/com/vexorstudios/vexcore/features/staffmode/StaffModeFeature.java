package com.vexorstudios.vexcore.features.staffmode;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Visibility;
import com.vexorstudios.vexcore.features.vanish.VanishFeature;
import com.vexorstudios.vexcore.gui.ItemSpec;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /staff: staff mode. The player's inventory, game mode, flight, level and invulnerability are
 * saved (in the database too, so a crash can't lose them), then they get the tools from
 * config.yml: vanish, random teleport, freeze (screenshare), inspect inventory, punish, rollback,
 * reports, the online players menu and leave. Tools can't be dropped or moved; staff in staff
 * mode can't pick items up, build or hit (all configurable). Leaving, quitting and reloads put
 * everything back; a crash is repaired at the next join.
 */
public final class StaffModeFeature extends Feature implements Listener {

    /** What staff mode replaced, to put back. */
    private record Saved(String items, String gameMode, boolean allowFlight, boolean flying, int level, float exp,
                         boolean invulnerable, boolean wasVanished) {
    }

    private final Map<UUID, Saved> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private NamespacedKey toolKey;

    @Override
    protected void enable() {
        toolKey = new NamespacedKey(plugin, "staff_tool");
        boolean mysql = db().type() == Database.Type.MYSQL;
        db().schema("staffmode", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, items "
                + (mysql ? "MEDIUMTEXT" : "TEXT") + " NOT NULL, gamemode VARCHAR(16) NOT NULL, allow_flight INT NOT NULL, "
                + "flying INT NOT NULL, level INT NOT NULL, exp DOUBLE NOT NULL, invulnerable INT NOT NULL, vanished INT NOT NULL)");
        listen(this);
        command("staff", (sender, label, args) -> {
            Player p = player(sender);
            if (p == null) return;
            if (active.containsKey(p.getUniqueId())) leave(p, true);
            else enter(p);
        });
        placeholder("staffmode", (p, a) -> String.valueOf(active.containsKey(p.getUniqueId())));
        for (Player p : Bukkit.getOnlinePlayers()) recover(p); // after a reload
    }

    @Override
    protected void disable() {
        // Reload or shutdown: everyone gets their things back now.
        for (UUID id : List.copyOf(active.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                if (plugin.isEnabled()) Scheduler.entity(p, () -> leave(p, false));
                else leave(p, false);
            }
        }
    }

    public boolean inStaffMode(UUID player) {
        return active.containsKey(player);
    }

    // ── Entering and leaving ──────────────────────────────────────────────

    private void enter(Player p) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : p.getInventory().getContents()) items.add(item == null ? ItemStack.empty() : item.clone());
        VanishFeature vanish = plugin.features().get("vanish") instanceof VanishFeature v ? v : null;
        Saved saved = new Saved(Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items)), p.getGameMode().name(),
                p.getAllowFlight(), p.isFlying(), p.getLevel(), p.getExp(), p.isInvulnerable(),
                vanish != null && vanish.isVanished(p.getUniqueId()));
        active.put(p.getUniqueId(), saved);
        write(p.getUniqueId(), saved);

        p.getInventory().clear();
        GameMode mode;
        try {
            mode = GameMode.valueOf(config().getString("gamemode", "SURVIVAL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mode = GameMode.SURVIVAL;
        }
        p.setGameMode(mode);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setInvulnerable(true);
        if (vanish != null && config().getBoolean("vanish-on-enter", true)) vanish.setVanished(p, true);
        giveTools(p);
        msg(p, "entered");
        staffAlert("staff-entered", p);
    }

    private void leave(Player p, boolean tell) {
        Saved saved = active.remove(p.getUniqueId());
        if (saved == null) return;
        restore(p, saved);
        delete(p.getUniqueId());
        if (tell) {
            msg(p, "left");
            staffAlert("staff-left", p);
        }
    }

    private void restore(Player p, Saved saved) {
        p.getInventory().clear();
        try {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(saved.items));
            ItemStack[] contents = new ItemStack[p.getInventory().getSize()];
            for (int i = 0; i < contents.length && i < items.length; i++) contents[i] = items[i] == null || items[i].isEmpty() ? null : items[i];
            p.getInventory().setContents(contents);
        } catch (RuntimeException bad) {
            plugin.getLogger().severe("Staff mode: could not restore the inventory of " + p.getName() + ": " + bad.getMessage());
        }
        try {
            p.setGameMode(GameMode.valueOf(saved.gameMode));
        } catch (IllegalArgumentException ignored) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        p.setAllowFlight(saved.allowFlight || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR);
        p.setFlying(saved.flying && p.getAllowFlight());
        p.setLevel(saved.level);
        p.setExp(Math.max(0, Math.min(0.9999f, saved.exp)));
        p.setInvulnerable(saved.invulnerable);
        if (plugin.features().get("vanish") instanceof VanishFeature vanish && config().getBoolean("vanish-on-enter", true)) {
            vanish.setVanished(p, saved.wasVanished);
        }
        p.updateInventory();
    }

    private void staffAlert(String key, Player who) {
        List<Player> staff = new ArrayList<>();
        for (Player s : Bukkit.getOnlinePlayers()) if (!s.equals(who) && s.hasPermission("vexcore.staffmode")) staff.add(s);
        broadcast(staff, key, Map.of("player", who.getName()));
    }

    // ── Crash safety ──────────────────────────────────────────────────────

    private void write(UUID id, Saved s) {
        Database db = db();
        db.queue("staffmode save", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("staffmode", new String[]{"uuid"},
                    "items", "gamemode", "allow_flight", "flying", "level", "exp", "invulnerable", "vanished"))) {
                ps.setString(1, id.toString());
                ps.setString(2, s.items);
                ps.setString(3, s.gameMode);
                ps.setInt(4, s.allowFlight ? 1 : 0);
                ps.setInt(5, s.flying ? 1 : 0);
                ps.setInt(6, s.level);
                ps.setDouble(7, s.exp);
                ps.setInt(8, s.invulnerable ? 1 : 0);
                ps.setInt(9, s.wasVanished ? 1 : 0);
                ps.executeUpdate();
            }
        });
    }

    private void delete(UUID id) {
        Database db = db();
        db.queue("staffmode clear", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("staffmode") + " WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
        });
    }

    /** A saved row for someone not in staff mode means the server stopped without restoring. */
    private void recover(Player p) {
        if (active.containsKey(p.getUniqueId())) return;
        Database db = db();
        UUID id = p.getUniqueId();
        db.query("staffmode recover", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT items, gamemode, allow_flight, flying, level, exp, invulnerable, vanished FROM "
                    + db.table("staffmode") + " WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Saved(rs.getString(1), rs.getString(2), rs.getInt(3) == 1, rs.getInt(4) == 1,
                            rs.getInt(5), (float) rs.getDouble(6), rs.getInt(7) == 1, rs.getInt(8) == 1) : null;
                }
            }
        }).whenComplete((saved, error) -> {
            if (saved == null || !plugin.isEnabled()) return;
            Scheduler.entity(p, () -> {
                if (!isEnabled() || active.containsKey(id) || !p.isOnline()) return;
                restore(p, saved);
                delete(id);
                msg(p, "recovered");
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        recover(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        leave(event.getPlayer(), false); // before the game saves the player's inventory
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    // ── Tools ─────────────────────────────────────────────────────────────

    private void giveTools(Player p) {
        ConfigurationSection tools = config().getConfigurationSection("tools");
        if (tools == null) return;
        for (String key : tools.getKeys(false)) {
            ConfigurationSection t = tools.getConfigurationSection(key);
            if (t == null) continue;
            int slot = t.getInt("slot", -1);
            if (slot < 0 || slot > 35) continue;
            if (!t.getString("permission", "").isBlank() && !p.hasPermission(t.getString("permission", ""))) continue;
            p.getInventory().setItem(slot, tool(p, key, t));
        }
    }

    private ItemStack tool(Player p, String key, ConfigurationSection t) {
        ConfigurationSection look = t;
        // The vanish tool looks different while visible.
        if (t.getString("action", "").equalsIgnoreCase("vanish") && t.isConfigurationSection("visible")
                && !(plugin.features().get("vanish") instanceof VanishFeature v && v.isVanished(p.getUniqueId()))) {
            look = t.getConfigurationSection("visible");
        }
        ItemStack item = ItemSpec.of(look).build(p, Map.of("player", p.getName()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, key);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ConfigurationSection toolOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        String key = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        return key == null ? null : config().getConfigurationSection("tools." + key);
    }

    /** Stops one click from firing twice (both hands, block and air). */
    private boolean debounce(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastUse.put(p.getUniqueId(), now);
        return last != null && now - last < 250;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!active.containsKey(p.getUniqueId()) || event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ConfigurationSection t = toolOf(event.getItem());
        if (t == null) return;
        event.setCancelled(true);
        if (debounce(p)) return;
        String action = t.getString("action", "").toLowerCase(Locale.ROOT);
        switch (action) {
            case "vanish" -> {
                if (plugin.features().get("vanish") instanceof VanishFeature v) {
                    v.setVanished(p, !v.isVanished(p.getUniqueId()));
                    giveTools(p); // swaps the vanish tool's look
                }
            }
            case "random-tp" -> randomTeleport(p);
            case "online" -> openPlayers(p);
            case "leave" -> leave(p, true);
            case "freeze", "inspect", "punish", "rollback", "history" -> msg(p, "use-on-player");
            default -> {
                if (action.startsWith("command:")) p.performCommand(action.substring(8).strip());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseOnPlayer(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        if (!active.containsKey(p.getUniqueId()) || event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Player target)) return;
        ConfigurationSection t = toolOf(p.getInventory().getItemInMainHand());
        if (t == null) return;
        event.setCancelled(true);
        if (debounce(p)) return;
        onTarget(p, target, t.getString("action", "").toLowerCase(Locale.ROOT));
    }

    private void onTarget(Player p, Player target, String action) {
        switch (action) {
            case "freeze" -> p.performCommand(plugin.commands().name("screenshare") + " " + target.getName());
            case "inspect" -> p.openInventory(target.getInventory());
            case "punish" -> p.performCommand(plugin.commands().name("punish") + " " + target.getName());
            case "history" -> p.performCommand(plugin.commands().name("history") + " " + target.getName());
            case "rollback" -> p.performCommand(plugin.commands().name("invrollback") + " " + target.getName());
            default -> {
                if (action.startsWith("command:")) p.performCommand(action.substring(8).strip().replace("%target%", target.getName()));
            }
        }
    }

    private void randomTeleport(Player p) {
        List<Player> choices = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p) || active.containsKey(other.getUniqueId()) || !Visibility.knows(p, other)) continue;
            if (config().getBoolean("random-tp-skips-staff", true) && other.hasPermission("vexcore.staffmode")) continue;
            choices.add(other);
        }
        if (choices.isEmpty()) {
            msg(p, "nobody");
            return;
        }
        Player to = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        p.teleportAsync(to.getLocation()).thenAccept(ok -> {
            if (Boolean.TRUE.equals(ok)) msg(p, "teleported", "player", to.getName());
        });
    }

    // ── Online players menu ───────────────────────────────────────────────

    private void openPlayers(Player p) {
        open(p, "players", menu -> {
            List<Player> list = new ArrayList<>();
            for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p) && Visibility.knows(p, other)) list.add(other);
            list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            menu.with("online", list.size());
            if (list.isEmpty()) menu.function("empty", c -> {
            });
            menu.paginate(list, (other, slot) -> {
                Map<String, Object> ph = Map.of("target", other.getName(), "health", (int) Math.ceil(other.getHealth()),
                        "world", other.getWorld().getName(), "gamemode", other.getGameMode().name().toLowerCase(Locale.ROOT),
                        "ping", other.getPing(), "staff", active.containsKey(other.getUniqueId())
                                ? config().getString("words.yes", "&#97F900Yes") : config().getString("words.no", "&7No"));
                menu.place("player", slot, ph, c -> {
                    if (!other.isOnline()) return;
                    if (c.type().isShiftClick() && c.type().isRightClick()) onTarget(c.player(), other, "inspect");
                    else if (c.type().isRightClick()) onTarget(c.player(), other, "punish");
                    else {
                        c.player().closeInventory();
                        c.player().teleportAsync(other.getLocation());
                        msg(c.player(), "teleported", "player", other.getName());
                    }
                });
            });
        });
    }

    // ── Protection while in staff mode ────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || !active.containsKey(p.getUniqueId())) return;
        // Their own inventory holds only the tools. An inspected player's inventory stays editable
        // unless the config says otherwise.
        boolean own = event.getClickedInventory() == p.getInventory() || event.getView().getTopInventory() == p.getInventory();
        boolean inspecting = event.getView().getTopInventory().getHolder(false) instanceof Player other && !other.equals(p);
        if (own || (inspecting && !config().getBoolean("inspect-can-edit", false)) || toolOf(event.getCurrentItem()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && active.containsKey(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (active.containsKey(event.getPlayer().getUniqueId()) && !config().getBoolean("can-build", false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (active.containsKey(event.getPlayer().getUniqueId()) && !config().getBoolean("can-build", false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && active.containsKey(p.getUniqueId()) && !config().getBoolean("can-hit", false)) {
            event.setCancelled(true);
        }
    }
}
