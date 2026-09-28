package com.vexorstudios.vexcore.features.sign;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /sign stamps the held item with a signature line, once per item. Items signed by SetupCore
 * (a "Signed by" lore line) count as signed too.
 */
public final class SignFeature extends Feature {

    private NamespacedKey key;

    @Override
    protected void enable() {
        key = new NamespacedKey(plugin, "signed");
        command("sign", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) sign(player);
        }, (s, a) -> List.of());
    }

    private void sign(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemMeta meta = item.getType().isAir() ? null : item.getItemMeta();
        if (meta == null) {
            msg(player, "no-item");
            return;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        boolean signed = meta.getPersistentDataContainer().has(key);
        for (Component line : lore) signed |= Text.plain(line).startsWith("Signed by ");
        if (signed) {
            msg(player, "already-signed");
            return;
        }
        String date = com.vexorstudios.vexcore.core.Dates.format(System.currentTimeMillis(), config().getString("date-format", "dd.MM.yyyy"));
        Map<String, Object> ph = Map.of("player", player.getName(), "date", date);
        if (config().getBoolean("blank-line", true) && !lore.isEmpty()) lore.add(Component.empty());
        for (String line : Text.lines(config().get("lore"))) lore.add(Text.item(line, player, ph));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId() + ";" + date);
        item.setItemMeta(meta);
        msg(player, "signed", ph);
    }
}
