package com.vexorstudios.vexcore.gui;

import com.vexorstudios.vexcore.core.SoundSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

/**
 * One item block of a menu file, under {@code items:} or {@code templates:}.
 * <pre>
 *   slot: 13                       # or slots: "0-8, 45-53"
 *   material / name / lore / ...   # see ItemSpec
 *   permission: ""                 # hidden from players without it
 *   function: next-page            # built-in behaviour the menu offers (hidden when unavailable)
 *   commands: ["[player] spawn"]   # run on any click
 *   left-commands: [...]           # run on left click instead of commands
 *   right-commands: [...]          # run on right click instead of commands
 *   sound: {sound: ..., ...}       # replaces the menu's click sound for this item
 *   close-on-click: false
 * </pre>
 */
public record MenuItem(String key, ItemSpec spec, List<Integer> slots, String function, String permission,
                       List<String> commands, List<String> left, List<String> right, SoundSpec sound,
                       boolean silent, boolean close) {

    public static MenuItem of(String key, ConfigurationSection s) {
        List<Integer> slots = Slots.parse(s.contains("slots") ? s.get("slots") : s.get("slot"));
        String function = s.getString("function", "");
        Object sound = s.get("sound");
        // sound: "" or sound: {enabled: false} silences the item.
        boolean silent = (sound instanceof String str && str.isBlank())
                || (sound instanceof ConfigurationSection sec && !sec.getBoolean("enabled", true));
        return new MenuItem(key, ItemSpec.of(s), slots, function.isBlank() ? null : function.trim().toLowerCase(java.util.Locale.ROOT),
                s.getString("permission", "").trim(), s.getStringList("commands"),
                s.getStringList("left-commands"), s.getStringList("right-commands"),
                silent ? null : SoundSpec.of(sound), silent, s.getBoolean("close-on-click", false));
    }

    public List<String> commandsFor(ClickType click) {
        if (click.isLeftClick() && !left.isEmpty()) return left;
        if (click.isRightClick() && !right.isEmpty()) return right;
        return commands;
    }
}
