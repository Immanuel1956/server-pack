package com.vexorstudios.vexcore.features.warps;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Pos;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Server warps, set by staff and kept in data/warps.yml (server-local, like spawns).
 * /warp &lt;name&gt; teleports, /warp or /warps opens the menu, /setwarp &lt;name&gt; [icon from hand],
 * /delwarp &lt;name&gt;. With {@code per-warp-permission} a warp needs vexcore.warp.&lt;name&gt;.
 */
public final class WarpsFeature extends Feature {

    record Warp(String name, Pos pos, String icon, String description) {
    }

    private final Map<String, Warp> warps = new ConcurrentSkipListMap<>();
    private File file;
    private boolean perWarpPermission;
    private String defaultIcon;

    @Override
    protected void enable() {
        perWarpPermission = config().getBoolean("per-warp-permission", false);
        defaultIcon = config().getString("default-icon", "ENDER_PEARL");
        file = plugin.files().data("warps.yml");
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("warps");
        if (root != null) for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            Pos pos = Pos.read(s);
            if (pos == null) continue;
            String key = name.toLowerCase(Locale.ROOT);
            warps.put(key, new Warp(key, pos, s.getString("icon", defaultIcon), s.getString("description", "")));
        }
        command("warp", this::warp, (s, a) -> a.length == 1 ? usable(s) : List.of());
        command("warps", (sender, label, args) -> {
            Player p = player(sender);
            if (p != null) openMenu(p);
        });
        command("setwarp", this::setWarp, (s, a) -> a.length == 1 ? new ArrayList<>(warps.keySet()) : List.of());
        command("delwarp", this::delWarp, (s, a) -> a.length == 1 ? new ArrayList<>(warps.keySet()) : List.of());
        placeholder("warps", (p, arg) -> String.valueOf(warps.size()));
    }

    private boolean allowed(CommandSender sender, String name) {
        return !perWarpPermission || sender.hasPermission("vexcore.warp." + name) || sender.hasPermission("vexcore.warp.*");
    }

    private List<String> usable(CommandSender sender) {
        List<String> out = new ArrayList<>();
        for (String name : warps.keySet()) if (allowed(sender, name)) out.add(name);
        return out;
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Warp w : warps.values()) {
            ConfigurationSection s = yml.createSection("warps." + w.name);
            w.pos.write(s);
            s.set("icon", w.icon);
            if (!w.description.isEmpty()) s.set("description", w.description);
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + file + ": " + e.getMessage());
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void warp(CommandSender sender, String label, String[] args) {
        Player p = player(sender);
        if (p == null) return;
        if (args.length == 0) {
            openMenu(p);
            return;
        }
        go(p, args[0].toLowerCase(Locale.ROOT));
    }

    private void go(Player p, String name) {
        Warp w = warps.get(name);
        if (w == null) {
            msg(p, "not-found", "name", name);
            return;
        }
        if (!allowed(p, name)) {
            msg(p, "locked", "name", name);
            return;
        }
        plugin.teleports().start(this, p, w.pos::location, "vexcore.warp.bypass", Map.of("name", name), null);
    }

    private void setWarp(CommandSender sender, String label, String[] args) {
        Player p = player(sender);
        if (p == null) return;
        if (args.length == 0) {
            usage(p, "setwarp");
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,32}") || name.equals("admin") || name.equals("bypass")) {
            // Dots would nest in the YAML file; admin/bypass are permission nodes (vexcore.warp.<name>).
            msg(p, "invalid-name", "name", name);
            return;
        }
        Warp old = warps.get(name);
        ItemStack hand = p.getInventory().getItemInMainHand();
        String icon = !hand.isEmpty() ? hand.getType().name() : old != null ? old.icon : defaultIcon;
        String description = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : old != null ? old.description : "";
        warps.put(name, new Warp(name, Pos.of(p.getLocation()), icon, description));
        save();
        msg(p, old == null ? "set" : "moved", "name", name, "icon", icon);
    }

    private void delWarp(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, "delwarp");
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        if (warps.remove(name) == null) {
            msg(sender, "not-found", "name", name);
            return;
        }
        save();
        msg(sender, "deleted", "name", name);
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    private void openMenu(Player p) {
        open(p, "warps", menu -> {
            List<Warp> all = new ArrayList<>(warps.values());
            menu.with("warps", all.size());
            if (all.isEmpty()) menu.function("empty", c -> {
            });
            menu.paginate(all, (w, slot) -> {
                boolean ok = allowed(p, w.name);
                Map<String, Object> ph = new HashMap<>();
                ph.put("name", w.name);
                ph.put("icon", Material.matchMaterial(w.icon) == null ? defaultIcon : w.icon);
                ph.put("description", w.description.isEmpty() ? config().getString("default-description", "A server warp.") : w.description);
                ph.put("world", w.pos.world());
                ph.put("x", (int) Math.floor(w.pos.x()));
                ph.put("y", (int) Math.floor(w.pos.y()));
                ph.put("z", (int) Math.floor(w.pos.z()));
                menu.place(ok ? "warp" : "warp-locked", slot, ph, c -> {
                    if (!ok) {
                        msg(p, "locked", "name", w.name);
                        return;
                    }
                    p.closeInventory();
                    go(p, w.name);
                });
            });
        });
    }
}
