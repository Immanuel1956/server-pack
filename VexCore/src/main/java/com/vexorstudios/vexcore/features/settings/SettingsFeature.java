package com.vexorstudios.vexcore.features.settings;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Toggles;
import com.vexorstudios.vexcore.gui.MenuFile;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /settings: every toggle in one paged menu. Each template in gui/settings.yml is one button,
 * in file order. A button with {@code toggle: <id>} shows and flips that VexCore toggle and is
 * hidden while the feature that owns it is off; a button without one only runs its commands
 * (handy for other plugins' toggles).
 */
public final class SettingsFeature extends Feature {

    @Override
    protected void enable() {
        command("settings", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) openSettings(player);
        });
    }

    private void openSettings(Player player) {
        open(player, "settings", menu -> {
            MenuFile file = menu.file();
            List<String> shown = new ArrayList<>();
            for (String key : file.templateKeys()) {
                String toggle = file.yml().getString("templates." + key + ".toggle", "");
                String permission = file.yml().getString("templates." + key + ".permission", "");
                if (!permission.isEmpty() && !player.hasPermission(permission)) continue; // e.g. a rank perk
                if (toggle.isEmpty() || plugin.toggles().definition(toggle) != null) shown.add(key);
            }
            menu.paginate(shown, (key, slot) -> {
                String toggle = file.yml().getString("templates." + key + ".toggle", "");
                String status = "";
                if (!toggle.isEmpty()) {
                    boolean on = plugin.toggles().isOn(player.getUniqueId(), toggle);
                    status = file.yml().getString(on ? "status-on" : "status-off", on ? "&aON" : "&cOFF");
                }
                menu.place(key, slot, Map.of("status", status), toggle.isEmpty() ? null : click -> {
                    Toggles.Definition definition = plugin.toggles().definition(toggle);
                    if (definition == null) return;
                    definition.action().accept(player);
                    menu.refresh();
                });
            });
        });
    }
}
