package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import com.vexorstudios.vexcore.features.playerhide.PlayerHideFeature;
import com.vexorstudios.vexcore.features.vanish.VanishFeature;
import org.bukkit.entity.Player;

/**
 * Who sees whom. Bukkit keeps one hidden/shown state per plugin, so vanish and /playerhide share
 * it: both decide through here, and neither shows a player the other one hides.
 */
public final class Visibility {

    private Visibility() {
    }

    public static boolean hidden(Player viewer, Player target) {
        if (viewer.equals(target)) return false;
        FeatureManager features = VexCore.get().features();
        if (features.get("vanish") instanceof VanishFeature v && v.isVanished(target.getUniqueId())
                && !viewer.hasPermission("vexcore.vanish.see")) return true;
        return features.get("playerhide") instanceof PlayerHideFeature h && h.hiding(viewer)
                && !target.hasPermission("vexcore.playerhide.exempt");
    }

    /**
     * Whether {@code viewer} may know {@code target} is online (commands, tab completion). Only
     * vanish hides that: /playerhide only takes players out of sight, they are still there.
     */
    public static boolean knows(org.bukkit.command.CommandSender viewer, Player target) {
        if (!(viewer instanceof Player v) || v.equals(target)) return true;
        return !(VexCore.get().features().get("vanish") instanceof VanishFeature vanish && vanish.isVanished(target.getUniqueId())
                && !v.hasPermission("vexcore.vanish.see"));
    }

    /** Whether tab completion offers {@code target}'s name: known, and not behind /hide's shared name. */
    public static boolean listed(org.bukkit.command.CommandSender viewer, Player target) {
        if (!knows(viewer, target)) return false;
        return !(viewer instanceof Player v) || v.equals(target)
                || !(VexCore.get().features().get("hide") instanceof com.vexorstudios.vexcore.features.hide.HideFeature h && h.isHidden(target.getUniqueId()));
    }

    /** The name others see: a /hide player's shared name, everyone else's own. */
    public static String name(Player player) {
        return VexCore.get().features().get("hide") instanceof com.vexorstudios.vexcore.features.hide.HideFeature h
                ? h.shownName(player) : player.getName();
    }

    /** Viewer's thread: hides or shows {@code target} to {@code viewer}, whichever is right now. */
    public static void update(Player viewer, Player target) {
        if (hidden(viewer, target)) viewer.hidePlayer(VexCore.get(), target);
        else viewer.showPlayer(VexCore.get(), target);
    }
}
