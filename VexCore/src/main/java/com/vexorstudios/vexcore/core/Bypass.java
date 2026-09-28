package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;

/**
 * {@code op-bypass:} in config.yml. Operators skip the listed restrictions (the chat filter, chat
 * cooldowns, ...) without needing the bypass permissions, most of which are not given to ops by
 * default so that a server owner who wants ops filtered can have that.
 */
public final class Bypass {

    private Bypass() {
    }

    /** Whether {@code who} is an operator and {@code op-bypass.<what>} is on. */
    public static boolean op(Permissible who, String what) {
        if (!(who instanceof Player p) || !p.isOp()) return false;
        VexCore core = VexCore.get();
        if (core == null) return false;
        YamlConfiguration settings = core.settings();
        return settings.getBoolean("op-bypass.enabled", true) && settings.getBoolean("op-bypass." + what, true);
    }

    /** The permission, or an operator with {@code op-bypass.<what>} on. */
    public static boolean has(Permissible who, String permission, String what) {
        return who != null && (who.hasPermission(permission) || op(who, what));
    }
}
