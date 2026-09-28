package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every permission and placeholder of VexCore: {@code /vexcore permissions [search] [page]},
 * {@code /vexcore placeholders [search] [page]} (click an entry to copy it), and
 * permissions.txt / placeholders.txt in the plugin folder, written again on every start and
 * reload. Permissions come from plugin.yml, the commands and reference.yml (the ones made from
 * settings); placeholders from reference.yml (descriptions) and whatever the features registered.
 */
public final class Reference {

    public record Perm(String node, String access, String description) {
    }

    public record Holder(String placeholder, String description, String feature, boolean on) {
    }

    private static final int PAGE = 10;

    private Reference() {
    }

    // ── Collecting ────────────────────────────────────────────────────────

    private static YamlConfiguration reference(VexCore plugin) {
        YamlConfiguration yml = new YamlConfiguration();
        try (InputStream in = plugin.getResource("reference.yml")) {
            if (in != null) yml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read reference.yml from the jar: " + e.getMessage());
        }
        return yml;
    }

    /** everyone / op / nobody / not op, as plugin.yml's default says. */
    static String access(org.bukkit.permissions.PermissionDefault def) {
        return switch (def) {
            case TRUE -> "everyone";
            case OP -> "op";
            case NOT_OP -> "not op";
            case FALSE -> "nobody";
        };
    }

    public static List<Perm> permissions(VexCore plugin) {
        List<Perm> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Permission p : plugin.getPluginMeta().getPermissions()) {
            if (seen.add(p.getName())) out.add(new Perm(p.getName(), access(p.getDefault()), p.getDescription()));
        }
        // Commands whose permission plugin.yml doesn't list (links.yml, a renamed permission in
        // commands.yml). Bukkit gives an undeclared permission to operators only.
        for (Commands.Registered cmd : plugin.commands().active()) {
            String node = cmd.node;
            if (node == null || node.isBlank() || !seen.add(node)) continue;
            out.add(new Perm(node, "op", "/" + cmd.getName()));
        }
        out.sort((a, b) -> a.node.compareToIgnoreCase(b.node));
        for (Map<?, ?> m : reference(plugin).getMapList("permission-patterns")) {
            out.add(new Perm(String.valueOf(m.get("node")), String.valueOf(m.get("default")), String.valueOf(m.get("description"))));
        }
        return out;
    }

    public static List<Holder> placeholders(VexCore plugin) {
        List<Holder> out = new ArrayList<>();
        Map<String, String> registered = plugin.placeholders().keys();
        Set<String> documented = new HashSet<>();
        ConfigurationSection groups = reference(plugin).getConfigurationSection("placeholders");
        if (groups != null) for (String feature : groups.getKeys(false)) {
            boolean on = feature.equals("core") || plugin.features().get(feature) != null;
            for (Map<?, ?> m : groups.getMapList(feature)) {
                String placeholder = String.valueOf(m.get("placeholder"));
                out.add(new Holder(placeholder, String.valueOf(m.get("description")), feature, on));
                for (String key : registered.keySet()) {
                    if (placeholder.equalsIgnoreCase("%vexcore_" + key + "%")
                            || placeholder.toLowerCase(Locale.ROOT).startsWith("%vexcore_" + key + "_")) documented.add(key);
                }
            }
        }
        // Anything a feature registered that reference.yml doesn't describe still shows.
        for (Map.Entry<String, String> e : registered.entrySet()) {
            if (!documented.contains(e.getKey())) out.add(new Holder("%vexcore_" + e.getKey() + "%", "", e.getValue(), true));
        }
        return out;
    }

    // ── /vexcore permissions | placeholders ───────────────────────────────

    /** {@code args} are what follows the sub command: an optional search, then an optional page. */
    public static void show(VexCore plugin, CommandSender sender, String label, boolean perms, String[] args) {
        int page = 1;
        List<String> words = new ArrayList<>(List.of(args));
        if (!words.isEmpty() && words.getLast().matches("\\d{1,4}")) page = Integer.parseInt(words.removeLast());
        String search = String.join(" ", words).trim();
        String needle = search.toLowerCase(Locale.ROOT);

        List<Map<String, Object>> rows = new ArrayList<>();
        YamlConfiguration global = plugin.messages().global();
        String copy = global.getString("list-copy-hover", "&7Click to copy");
        if (perms) {
            for (Perm p : permissions(plugin)) {
                if (!needle.isEmpty() && !(p.node + " " + p.description).toLowerCase(Locale.ROOT).contains(needle)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("node", copyable(p.node, copy));
                row.put("default", global.getString("list-access." + p.access.replace(' ', '-'), p.access));
                row.put("description", Component.text(p.description == null ? "" : p.description));
                rows.add(row);
            }
        } else {
            String off = global.getString("list-off", "&c(off)");
            for (Holder h : placeholders(plugin)) {
                if (!needle.isEmpty() && !(h.placeholder + " " + h.description + " " + h.feature).toLowerCase(Locale.ROOT).contains(needle)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("placeholder", copyable(h.placeholder, copy));
                row.put("feature", h.feature);
                row.put("off", h.on ? "" : off);
                row.put("description", Component.text(h.description));
                rows.add(row);
            }
        }
        Messages m = plugin.messages();
        String what = perms ? "permissions" : "placeholders";
        if (rows.isEmpty()) {
            m.send(null, sender, "list-empty", Messages.map("search", search));
            return;
        }
        int pages = (rows.size() + PAGE - 1) / PAGE;
        page = Math.max(1, Math.min(pages, page));
        m.send(null, sender, perms ? "list-permissions-header" : "list-placeholders-header",
                Messages.map("count", rows.size(), "page", page, "pages", pages, "search", search.isEmpty() ? "-" : search));
        for (Map<String, Object> row : rows.subList((page - 1) * PAGE, Math.min(rows.size(), page * PAGE))) {
            m.send(null, sender, perms ? "list-permission" : "list-placeholder", row);
        }
        String base = "/" + label + " " + what + (search.isEmpty() ? "" : " " + search) + " ";
        m.send(null, sender, "list-footer", Messages.map(
                "previous", pageButton(global, "list-previous", page > 1 ? base + (page - 1) : null),
                "next", pageButton(global, "list-next", page < pages ? base + (page + 1) : null),
                "page", page, "pages", pages, "file", what + ".txt"));
    }

    private static Component copyable(String text, String hover) {
        return Component.text(text).clickEvent(ClickEvent.copyToClipboard(text)).hoverEvent(HoverEvent.showText(Text.parse(hover)));
    }

    private static Component pageButton(YamlConfiguration global, String key, String command) {
        String text = global.getString(key + (command == null ? "-off" : ""), "");
        Component c = Text.parse(text);
        return command == null ? c : c.clickEvent(ClickEvent.runCommand(command));
    }

    // ── permissions.txt / placeholders.txt ────────────────────────────────

    /** Main thread: collects both lists, then writes them off the main thread. */
    public static void write(VexCore plugin) {
        String perms = permissionsText(plugin);
        String holders = placeholdersText(plugin);
        File dir = plugin.getDataFolder();
        Runnable save = () -> {
            try {
                java.nio.file.Files.writeString(new File(dir, "permissions.txt").toPath(), perms, StandardCharsets.UTF_8);
                java.nio.file.Files.writeString(new File(dir, "placeholders.txt").toPath(), holders, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not write permissions.txt / placeholders.txt: " + e.getMessage());
            }
        };
        if (plugin.isEnabled()) Scheduler.async(save);
        else save.run();
    }

    static String permissionsText(VexCore plugin) {
        StringBuilder out = new StringBuilder();
        out.append("# VexCore permissions. Written again on every start and /vexcore reload: edits here are lost.\n");
        out.append("# Who has it without LuckPerms: everyone, op (operators), nobody (only who you give it to).\n");
        out.append("# In game: /vexcore permissions [search] [page]\n\n");
        List<Perm> list = permissions(plugin);
        int width = 0;
        for (Perm p : list) width = Math.max(width, p.node.length());
        for (Perm p : list) {
            out.append(String.format(Locale.ROOT, "%-" + width + "s  %-8s  %s%n", p.node, p.access, p.description == null ? "" : p.description));
        }
        return out.toString();
    }

    static String placeholdersText(VexCore plugin) {
        StringBuilder out = new StringBuilder();
        out.append("# VexCore placeholders. Written again on every start and /vexcore reload: edits here are lost.\n");
        out.append("# They work in every VexCore file, and everywhere else through PlaceholderAPI.\n");
        out.append("# [x] is a part you fill in. (off) = that feature is turned off in config.yml.\n");
        out.append("# In game: /vexcore placeholders [search] [page]\n");
        String feature = null;
        List<Holder> list = placeholders(plugin);
        int width = 0;
        for (Holder h : list) width = Math.max(width, h.placeholder.length());
        for (Holder h : list) {
            if (!h.feature.equals(feature)) {
                feature = h.feature;
                out.append('\n').append("## ").append(feature).append(h.on ? "" : " (off)").append('\n');
            }
            out.append(String.format(Locale.ROOT, "%-" + width + "s  %s%n", h.placeholder, h.description));
        }
        return out.toString();
    }
}
