package com.vexorstudios.vexcore.features.menus;

import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.entity.Player;

/**
 * /rules, /guide, /media, /ranks: menus made entirely in their gui file. Every item can run
 * commands; nothing is hard-coded. The same class runs each of them, from its own folder.
 */
public final class MenuFeature extends Feature {

    @Override
    protected void enable() {
        command(id(), (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) open(player, id(), menu -> {
            });
        });
    }
}
