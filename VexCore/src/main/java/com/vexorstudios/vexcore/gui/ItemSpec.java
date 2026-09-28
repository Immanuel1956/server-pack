package com.vexorstudios.vexcore.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.vexorstudios.vexcore.core.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An item written in YAML:
 * <pre>
 *   material: DIAMOND_SWORD        # placeholders allowed
 *   amount: 1
 *   name: "&b&lSWORD"              # "display-name" works too
 *   lore: ["line", "line"]
 *   glow: true
 *   custom-model-data: 1001
 *   item-model: "mypack:sword"     # 1.21.4+ item models
 *   skull: "%player%"              # a player name or a base64 texture, for PLAYER_HEAD
 *   hide-flags: true               # hide enchant/attribute/... lines (default true)
 * </pre>
 */
public final class ItemSpec {

    private final String material;
    private final int amount;
    private final String name;
    private final List<String> lore;
    private final boolean glow;
    private final int modelData;
    private final String itemModel;
    private final String skull;
    private final boolean hideFlags;

    private ItemSpec(ConfigurationSection s) {
        material = s.getString("material", "STONE");
        amount = Math.max(1, Math.min(99, s.getInt("amount", 1)));
        name = s.contains("name") ? s.getString("name") : s.getString("display-name");
        lore = s.getStringList("lore");
        glow = s.getBoolean("glow", false);
        modelData = s.getInt("custom-model-data", 0);
        itemModel = s.getString("item-model", "");
        skull = s.getString("skull", "");
        hideFlags = s.getBoolean("hide-flags", true);
    }

    public static ItemSpec of(ConfigurationSection section) {
        return new ItemSpec(section);
    }

    /** A problem worth reporting on reload, or null. */
    public String problem() {
        if (material.indexOf('%') < 0 && Material.matchMaterial(material) == null) return "unknown material '" + material + "'";
        return null;
    }

    public ItemStack build(Player viewer, Map<String, ?> placeholders) {
        Material type = Material.matchMaterial(Text.fill(material, placeholders).trim());
        if (type == null || !type.isItem() || type.isAir()) {
            // A material a placeholder filled in wrong: shown as invalid-material (config.yml).
            type = Material.matchMaterial(com.vexorstudios.vexcore.VexCore.get().settings().getString("invalid-material", "BARRIER"));
            if (type == null || !type.isItem() || type.isAir()) type = Material.BARRIER;
        }
        ItemStack item = new ItemStack(type, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (name != null) meta.displayName(Text.item(name, viewer, placeholders));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                // A placeholder may stand for several lines ("\n" inside its value).
                for (String part : strings(line, placeholders).split("\n", -1)) {
                    lines.add(Text.item(part, viewer, placeholders));
                }
            }
            meta.lore(lines);
        }
        if (glow) meta.setEnchantmentGlintOverride(true);
        if (modelData != 0) meta.setCustomModelData(modelData);
        if (!itemModel.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(itemModel.trim().toLowerCase(java.util.Locale.ROOT));
            if (key != null) meta.setItemModel(key);
        }
        if (hideFlags) meta.addItemFlags(ItemFlag.values());
        if (meta instanceof SkullMeta head && !skull.isBlank()) skin(head, Text.fill(skull, placeholders).trim());
        item.setItemMeta(meta);
        return item;
    }

    /** Replaces only the text placeholders, leaving component ones for the parser. */
    private static String strings(String line, Map<String, ?> placeholders) {
        if (placeholders == null || line.indexOf('%') < 0) return line;
        for (Map.Entry<String, ?> e : placeholders.entrySet()) {
            if (e.getValue() instanceof String value) line = line.replace("%" + e.getKey() + "%", value);
        }
        return line;
    }

    private static void skin(SkullMeta head, String value) {
        if (value.isEmpty()) return;
        if (value.length() > 32) {
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
            profile.setProperty(new ProfileProperty("textures", value));
            head.setPlayerProfile(profile);
            return;
        }
        Player online = Bukkit.getPlayerExact(value);
        if (online != null) {
            head.setPlayerProfile(online.getPlayerProfile());
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(value);
        if (cached != null) head.setOwningPlayer(cached);
    }
}
