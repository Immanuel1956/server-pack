package com.vexorstudios.vexcore.gui;

import com.vexorstudios.vexcore.VexCore;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SoundSpec;
import com.vexorstudios.vexcore.core.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command attachments on menu items. Each line may start with a type:
 * <pre>
 *   [player] spawn              the player runs /spawn (default when no type is given)
 *   [console] give %player% dirt 1
 *   [message] &aHello %player%
 *   [broadcast] &e%player% opened the menu
 *   [actionbar] &aDone!
 *   [title] &aTitle|&7Subtitle
 *   [sound] entity.player.levelup;1;1
 *   [chat] hello everyone       the player says it in chat
 *   [refresh]                   redraws the menu
 *   [close]                     closes the menu
 * </pre>
 * %player% and the menu's own placeholders work everywhere, PlaceholderAPI too.
 */
public final class Actions {

    private Actions() {
    }

    public static void run(Player player, List<String> lines, Map<String, ?> placeholders, Menu menu) {
        if (lines == null || lines.isEmpty()) return;
        Map<String, Object> ph = new HashMap<>(placeholders == null ? Map.of() : placeholders);
        ph.putIfAbsent("player", player.getName());
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) continue;
            String line = raw.strip();
            String type = "player";
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                if (end > 0) {
                    type = line.substring(1, end).trim().toLowerCase(Locale.ROOT);
                    line = line.substring(end + 1).strip();
                }
            }
            line = line.replace("{prefix}", VexCore.get().messages().prefix(menu == null ? null : menu.feature()));
            try {
                run(player, type, line, ph, menu);
            } catch (RuntimeException error) {
                VexCore.get().getLogger().warning("Menu action '" + raw + "' failed: " + error.getMessage());
            }
        }
    }

    private static void run(Player player, String type, String line, Map<String, Object> ph, Menu menu) {
        switch (type) {
            // chat("/...") instead of performCommand so command blockers (combat tag) still apply.
            case "player", "command", "cmd" -> player.chat("/" + command(player, line, ph));
            case "console" -> {
                String command = command(player, line, ph);
                Scheduler.global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            }
            case "message", "msg" -> player.sendMessage(Text.links(Text.parse(line, player, ph), line));
            case "broadcast" -> {
                for (Player other : Bukkit.getOnlinePlayers()) other.sendMessage(Text.links(Text.parse(line, player, ph), line));
                Bukkit.getConsoleSender().sendMessage(Text.parse(line, player, ph));
            }
            case "actionbar" -> player.sendActionBar(Text.parse(line, player, ph));
            case "title" -> {
                String[] parts = line.split("\\|", 2);
                player.showTitle(Title.title(Text.parse(parts[0], player, ph),
                        parts.length > 1 ? Text.parse(parts[1], player, ph) : Component.empty(), com.vexorstudios.vexcore.core.Messages.titleTimes()));
            }
            case "sound" -> {
                SoundSpec sound = SoundSpec.of(Text.fill(line, ph));
                if (sound != null) sound.play(player);
            }
            case "chat" -> player.chat(Text.fill(line, ph));
            case "refresh" -> {
                if (menu != null) menu.refresh();
            }
            case "close" -> player.closeInventory();
            default -> VexCore.get().getLogger().warning("Unknown menu action type [" + type + "] in: " + line);
        }
    }

    private static String command(Player player, String line, Map<String, Object> ph) {
        String command = Text.papi(player, Text.fill(line, ph));
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
