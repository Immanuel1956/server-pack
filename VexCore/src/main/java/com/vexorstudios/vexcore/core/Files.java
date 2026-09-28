package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The plugin folder:
 * <pre>
 *   config.yml, database.yml, commands.yml, globalmessages.yml
 *   features/&lt;feature&gt;/config.yml
 *   features/&lt;feature&gt;/gui/*.yml
 *   features/&lt;group&gt;/&lt;feature&gt;.yml        small features share a folder (see GROUPS)
 *   features/&lt;group&gt;/gui/&lt;feature&gt;.yml
 *   data/
 * </pre>
 * Missing files are copied out of the jar on every start and reload; existing files are never
 * touched. Settings files get the jar's copy as defaults, so a key someone deleted falls back
 * to its default instead of breaking. Menu files do not, so an item someone deleted stays gone.
 */
public final class Files {

    /**
     * Small features whose files live together in one folder instead of a folder each:
     * features/social/discord.yml instead of features/discord/config.yml. A grouped feature's
     * menus are features/&lt;group&gt;/gui/&lt;feature&gt;.yml (its main menu) and
     * &lt;feature&gt;-&lt;menu&gt;.yml. Folders from before the grouping are moved on start.
     */
    private static final Map<String, String> GROUPS = groups(
            "social", "discord, store, apply, live, rules, guide, media, ranks, socials, links, broadcast",
            "teleport", "spawn, afk, tpa",
            "toggles", "nightvision, playerhide, mobtoggle, phantoms, joinmessages, deathmessages",
            "utility", "dropfix, workstations, sign, ping, msg, rename",
            "pvp", "combat, duel, ffa",
            "staff", "vanish, screenshare, stafftp, staffessentials, staffchat, hide, ranktrial, ipprotection",
            "server", "announce, antilag, joincounter, ggwave, keyall, tebex, events, leaderboard, stats, scoreboard, "
                    + "nametags, commandwhitelist, commandroutes");

    private static Map<String, String> groups(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            for (String id : pairs[i + 1].split(",")) out.put(id.trim(), pairs[i]);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The shared folder of a feature, or null when it has a folder of its own. */
    public static String group(String feature) {
        return GROUPS.get(feature);
    }

    /** Where a feature's settings are: features/&lt;id&gt;/config.yml or features/&lt;group&gt;/&lt;id&gt;.yml. */
    public static String configPath(String feature) {
        String group = GROUPS.get(feature);
        return group == null ? "features/" + feature + "/config.yml" : "features/" + group + "/" + feature + ".yml";
    }

    /** Where one of a feature's menus is. */
    public static String menuPath(String feature, String menu) {
        String group = GROUPS.get(feature);
        if (group == null) return "features/" + feature + "/gui/" + menu + ".yml";
        return "features/" + group + "/gui/" + (menu.equals(feature) ? feature : feature + "-" + menu) + ".yml";
    }

    private final VexCore plugin;
    private final List<String> errors = new ArrayList<>();

    public Files(VexCore plugin) {
        this.plugin = plugin;
    }

    public File dir() {
        return plugin.getDataFolder();
    }

    public File data(String name) {
        return new File(new File(dir(), "data"), name);
    }

    /** Copies every missing file out of the jar. */
    public void extract() {
        migrate();
        for (String name : List.of("config.yml", "database.yml", "commands.yml", "globalmessages.yml")) {
            if (!new File(dir(), name).exists()) plugin.saveResource(name, false);
        }
        try (JarFile jar = new JarFile(plugin.jar())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("features/") || !name.endsWith(".yml")) continue;
                if (!new File(dir(), name).exists()) plugin.saveResource(name, false);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the plugin jar to copy default files: " + e.getMessage());
        }
        new File(dir(), "data").mkdirs();
    }

    /** Loads a settings file with the jar's copy behind it as defaults. */
    public YamlConfiguration settings(String path) {
        YamlConfiguration yaml = read(path);
        YamlConfiguration defaults = jar(path);
        if (defaults != null) yaml.setDefaults(defaults);
        return yaml;
    }

    /** Loads a menu file as written; falls back to the jar's copy only if it cannot be read. */
    public YamlConfiguration menu(String path) {
        File file = new File(dir(), path);
        YamlConfiguration yaml = new CachedYaml();
        try {
            yaml.load(file);
            normalizeErrors(yaml);
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            error(path, e);
            YamlConfiguration fallback = jar(path);
            return fallback != null ? fallback : yaml;
        }
    }

    /** The menu files of a feature: features/&lt;id&gt;/gui/*.yml, or its own files in its group's gui/. */
    public List<String> menus(String feature) {
        String group = GROUPS.get(feature);
        File dir = new File(dir(), group == null ? "features/" + feature + "/gui" : "features/" + group + "/gui");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        List<String> names = new ArrayList<>();
        if (files != null) for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - 4);
            if (group == null) names.add(name);
            else if (name.equals(feature)) names.add(name);
            else if (name.startsWith(feature + "-") && name.length() > feature.length() + 1) names.add(name.substring(feature.length() + 1));
        }
        return names;
    }

    /**
     * Moves the files of features that now share a folder out of their old one-feature folders
     * (features/discord/config.yml to features/social/discord.yml, its menus to gui/), so an
     * update keeps every change the server made. A file whose new place is taken stays where it
     * is and is reported. Emptied old folders are removed.
     */
    private void migrate() {
        migrate(dir(), plugin.getLogger());
    }

    /** {@link #migrate()} for a plugin folder; static so it can be tried on a copy. */
    static void migrate(File root, java.util.logging.Logger log) {
        for (Map.Entry<String, String> e : GROUPS.entrySet()) {
            String id = e.getKey();
            File old = new File(root, "features/" + id);
            if (!old.isDirectory()) continue;
            boolean moved = move(new File(old, "config.yml"), new File(root, configPath(id)), log);
            File[] menus = new File(old, "gui").listFiles((d, n) -> n.endsWith(".yml"));
            if (menus != null) for (File menu : menus) {
                String name = menu.getName().substring(0, menu.getName().length() - 4);
                moved |= move(menu, new File(root, menuPath(id, name)), log);
            }
            deleteIfEmpty(new File(old, "gui"));
            deleteIfEmpty(old);
            if (moved) log.info("Moved features/" + id + "/ into features/" + e.getValue() + "/ (" + configPath(id) + ")");
            if (old.exists()) log.warning("features/" + id + "/ is no longer read; its settings are in "
                    + configPath(id) + ". Move anything you still need and delete the old folder.");
        }
    }

    private static boolean move(File from, File to, java.util.logging.Logger log) {
        if (!from.isFile() || to.exists()) return false;
        to.getParentFile().mkdirs();
        try {
            java.nio.file.Files.move(from.toPath(), to.toPath());
            return true;
        } catch (IOException ex) {
            log.warning("Could not move " + from + " to " + to + ": " + ex.getMessage());
            return false;
        }
    }

    private static void deleteIfEmpty(File dir) {
        String[] left = dir.list();
        if (left != null && left.length == 0) dir.delete();
    }

    private YamlConfiguration read(String path) {
        File file = new File(dir(), path);
        YamlConfiguration yaml = new CachedYaml();
        if (!file.exists()) return yaml;
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            error(path, e);
        }
        normalizeErrors(yaml);
        return yaml;
    }

    private static void normalizeErrors(YamlConfiguration yaml) {
        for (String key : yaml.getKeys(true)) {
            Object value = yaml.get(key);
            if (value instanceof String s) yaml.set(key, errorLabel(s));
            else if (value instanceof List<?> list) yaml.set(key, list.stream()
                    .map(v -> v instanceof String s ? errorLabel(s) : v).toList());
        }
    }

    private static String errorLabel(String text) {
        return text.replaceAll("(?:&#[0-9a-fA-F]{6}|&[0-9a-fA-F])(?:&l)?ERROR", "&#FF0000&lERROR");
    }

    private YamlConfiguration jar(String path) {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) return null;
            YamlConfiguration yaml = new CachedYaml();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            return null;
        }
    }

    private void error(String path, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip();
        int newline = message.indexOf('\n');
        errors.add(path + ": " + (newline > 0 ? message.substring(0, newline) : message));
        plugin.getLogger().severe("Could not read " + path + " - using the defaults. " + message);
    }

    /** True if {@code path} could not be read since the last {@link #takeErrors()}. */
    public boolean failed(String path) {
        for (String e : errors) if (e.startsWith(path + ":")) return true;
        return false;
    }

    /** Problems found since the last call, for the reload report. */
    public List<String> takeErrors() {
        List<String> out = new ArrayList<>(errors);
        errors.clear();
        return out;
    }
}
