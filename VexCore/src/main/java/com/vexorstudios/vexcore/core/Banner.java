package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The console block printed once VexCore has started: a gradient logo, then one line each for
 * the server, database, features, commands and plugin hooks, and how long it all took. Problems
 * and failed features are listed underneath (only when there are any), so a clean start is short.
 *
 * <p>{@code startup-banner} in config.yml: FANCY (block letters and symbols), SIMPLE (plain ASCII
 * for consoles that show ? or boxes instead of symbols) or OFF (only the ready line and problems).
 */
public final class Banner {

    public enum Style {FANCY, SIMPLE, OFF}

    private static final String[] BLOCK_LOGO = {
            "██╗   ██╗███████╗██╗  ██╗ ██████╗ ██████╗ ██████╗ ███████╗",
            "██║   ██║██╔════╝╚██╗██╔╝██╔════╝██╔═══██╗██╔══██╗██╔════╝",
            "██║   ██║█████╗   ╚███╔╝ ██║     ██║   ██║██████╔╝█████╗  ",
            "╚██╗ ██╔╝██╔══╝   ██╔██╗ ██║     ██║   ██║██╔══██╗██╔══╝  ",
            " ╚████╔╝ ███████╗██╔╝ ██╗╚██████╗╚██████╔╝██║  ██║███████╗",
            "  ╚═══╝  ╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚═╝  ╚═╝╚══════╝",
    };
    private static final String[] ASCII_LOGO = {
            "__     __         ____               ",
            "\\ \\   / /____  __/ ___|___  _ __ ___ ",
            " \\ \\ / / _ \\ \\/ / |   / _ \\| '__/ _ \\",
            "  \\ V /  __/>  <| |__| (_) | | |  __/",
            "   \\_/ \\___/_/\\_\\\\____\\___/|_|  \\___|",
    };
    /** Brand gradient: violet → pink → sky, run diagonally across the logo. */
    private static final TextColor[] STOPS = {TextColor.color(0xA66CFF), TextColor.color(0xFF6CD8), TextColor.color(0x3BC8FF)};
    private static final TextColor ACCENT = TextColor.color(0xA66CFF);
    private static final TextColor GOOD = TextColor.color(0x7CFC00);
    private static final TextColor BAD = TextColor.color(0xFF3B3B);
    private static final TextColor WARN = TextColor.color(0xFFD23B);
    private static final TextColor LABEL = NamedTextColor.GRAY;
    private static final TextColor DIM = NamedTextColor.DARK_GRAY;

    /** Plugins VexCore works with: name shown, plugin name as registered. */
    private static final String[][] HOOKS = {
            {"Vault", "Vault"}, {"PlaceholderAPI", "PlaceholderAPI"}, {"PacketEvents", "packetevents"},
            {"LuckPerms", "LuckPerms"}, {"NuVotifier", "Votifier"},
    };

    /** The symbols of one style: bullet, star, yes, no, separator, warning. */
    private record Symbols(String bullet, String star, String yes, String no, String dot, String warn) {
        static final Symbols FANCY = new Symbols("▸", "✦", "✔", "✘", "·", "⚠");
        static final Symbols SIMPLE = new Symbols(">", "*", "+", "x", "-", "!");
    }

    /** Everything the block shows; {@link #print} reads it from the running server. */
    record Info(String version, String platform, String minecraft, int java, int online,
                boolean databaseOk, String database, long databaseMillis,
                int on, int off, int failedCount, int menus, int commands,
                List<Map.Entry<String, Boolean>> hooks, Map<String, String> failed, List<String> problems, long millis) {
    }

    private Banner() {
    }

    /** The style named in config.yml; anything unknown is FANCY. */
    public static Style style(String raw) {
        try {
            return Style.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Style.FANCY;
        }
    }

    /** Prints the startup block to the console. */
    public static void print(VexCore plugin, Style style, long millis, long databaseMillis, List<String> problems) {
        int on = plugin.features().active().size();
        int failed = plugin.features().failed().size();
        int menus = 0;
        for (Feature f : plugin.features().active()) menus += f.menus().size();
        List<Map.Entry<String, Boolean>> hooks = new ArrayList<>();
        for (String[] hook : HOOKS) hooks.add(Map.entry(hook[0], Bukkit.getPluginManager().getPlugin(hook[1]) != null));
        Info info = new Info(plugin.getPluginMeta().getVersion(), Scheduler.FOLIA ? "Folia" : Bukkit.getName(),
                Bukkit.getMinecraftVersion(), Runtime.version().feature(), Bukkit.getOnlinePlayers().size(),
                problems.stream().noneMatch(p -> p.startsWith("database.yml")),
                plugin.database().type() == Database.Type.MYSQL ? "MySQL" : "SQLite", databaseMillis,
                on, plugin.features().available().size() - on - failed, failed, menus, plugin.commands().active().size(),
                hooks, plugin.features().failed(), problems, millis);
        CommandSender console = Bukkit.getConsoleSender();
        for (Component line : lines(info, style)) console.sendMessage(line);
    }

    // ── Building ──────────────────────────────────────────────────────────

    private static TextColor at(float t) {
        t = Math.max(0, Math.min(1, t));
        float scaled = t * (STOPS.length - 1);
        int i = Math.min(STOPS.length - 2, (int) scaled);
        return TextColor.lerp(scaled - i, STOPS[i], STOPS[i + 1]);
    }

    /** One logo row, coloured diagonally; spaces stay uncoloured so the line stays short. */
    private static Component logoRow(String[] logo, int row) {
        String line = logo[row];
        TextComponent.Builder out = Component.text().append(Component.text("  "));
        float span = line.length() + logo.length * 2f;
        StringBuilder run = new StringBuilder();
        TextColor runColor = null;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            // Steps of 3 columns: a few characters share a colour, which keeps the output small.
            TextColor color = c == ' ' ? null : at(((i / 3) * 3 + row * 2f) / span);
            if (run.length() > 0 && !java.util.Objects.equals(runColor, color)) { // colour changes: flush the run
                out.append(Component.text(run.toString(), runColor));
                run.setLength(0);
            }
            runColor = color;
            run.append(c);
        }
        if (run.length() > 0) out.append(Component.text(run.toString(), runColor));
        return out.build();
    }

    static List<Component> lines(Info i, Style style) {
        Symbols s = style == Style.FANCY ? Symbols.FANCY : Symbols.SIMPLE;
        Component dot = Component.text(" " + s.dot + " ", DIM);
        List<Component> lines = new ArrayList<>();
        if (style != Style.OFF) {
            String[] logo = style == Style.FANCY ? BLOCK_LOGO : ASCII_LOGO;
            lines.add(Component.empty());
            for (int r = 0; r < logo.length; r++) lines.add(logoRow(logo, r));
            lines.add(Component.text()
                    .append(Component.text("  " + s.star + " ", at(0.5f)))
                    .append(Component.text("VexCore ", NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                    .append(Component.text(i.version, ACCENT))
                    .append(dot)
                    .append(Component.text("by VexorStudios", LABEL))
                    .build());
            lines.add(Component.empty());

            lines.add(row(s, "Server", Component.text()
                    .append(Component.text(i.platform + " " + i.minecraft, NamedTextColor.WHITE))
                    .append(dot).append(Component.text("Java " + i.java, LABEL))
                    .append(dot).append(Component.text(i.online + " online", LABEL))
                    .build()));
            lines.add(row(s, "Database", Component.text()
                    .append(Component.text(i.databaseOk ? s.yes + " " : s.no + " ", i.databaseOk ? GOOD : BAD))
                    .append(Component.text(i.database, NamedTextColor.WHITE))
                    .append(dot).append(Component.text(i.databaseOk ? "connected in " + i.databaseMillis + " ms" : "not connected",
                            i.databaseOk ? LABEL : BAD))
                    .build()));
            lines.add(row(s, "Features", Component.text()
                    .append(Component.text(i.on + " on", GOOD))
                    .append(dot).append(Component.text(i.off + " off", LABEL))
                    .append(i.failedCount > 0 ? Component.text().append(dot).append(Component.text(i.failedCount + " failed", BAD)).build()
                            : Component.empty())
                    .append(dot).append(Component.text(i.menus + " menus", LABEL))
                    .build()));
            lines.add(row(s, "Commands", Component.text(i.commands + " registered", NamedTextColor.WHITE)));
            TextComponent.Builder hooks = Component.text();
            boolean first = true;
            for (Map.Entry<String, Boolean> hook : i.hooks) {
                if (!first) hooks.append(Component.text("  "));
                first = false;
                boolean found = hook.getValue();
                hooks.append(Component.text((found ? s.yes : s.no) + " ", found ? GOOD : DIM))
                        .append(Component.text(hook.getKey(), found ? NamedTextColor.WHITE : DIM));
            }
            lines.add(row(s, "Hooks", hooks.build()));
            lines.add(Component.empty());
        }
        for (Map.Entry<String, String> e : i.failed.entrySet()) {
            lines.add(Component.text()
                    .append(Component.text("  " + s.no + " ", BAD)).append(Component.text(e.getKey(), BAD))
                    .append(dot).append(Component.text(String.valueOf(e.getValue()), LABEL)).build());
        }
        for (String problem : i.problems) {
            lines.add(Component.text().append(Component.text("  " + s.warn + " ", WARN)).append(Component.text(problem, WARN)).build());
        }
        boolean clean = i.problems.isEmpty() && i.failedCount == 0;
        lines.add(Component.text()
                .append(Component.text("  " + (clean ? s.yes : s.warn) + " ", clean ? GOOD : WARN))
                .append(Component.text(style == Style.OFF ? "VexCore " + i.version + " ready in " : "Ready in ", NamedTextColor.WHITE))
                .append(Component.text(i.millis + " ms", ACCENT).decorate(TextDecoration.BOLD))
                .append(dot)
                .append(clean ? Component.text("no problems", GOOD)
                        : Component.text((i.problems.size() + i.failedCount) + " to look at (see above; /vexcore features)", WARN))
                .build());
        if (style != Style.OFF) lines.add(Component.empty());
        return lines;
    }

    private static Component row(Symbols s, String label, Component value) {
        return Component.text()
                .append(Component.text("  " + s.bullet + " ", ACCENT))
                .append(Component.text(String.format("%-10s", label), LABEL))
                .append(value)
                .build();
    }
}
