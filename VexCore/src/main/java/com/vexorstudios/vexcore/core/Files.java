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
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The plugin folder:
 * <pre>
 *   config.yml, database.yml, commands.yml, globalmessages.yml
 *   features/&lt;feature&gt;/config.yml
 *   features/&lt;feature&gt;/gui/*.yml
 *   data/
 * </pre>
 * Missing files are copied out of the jar on every start and reload; existing files are never
 * touched. Settings files get the jar's copy as defaults, so a key someone deleted falls back
 * to its default instead of breaking. Menu files do not, so an item someone deleted stays gone.
 */
public final class Files {

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

    /** The menu files of a feature: features/&lt;id&gt;/gui/*.yml. */
    public List<String> menus(String feature) {
        File[] files = new File(dir(), "features/" + feature + "/gui").listFiles((d, n) -> n.endsWith(".yml"));
        List<String> names = new ArrayList<>();
        if (files != null) for (File f : files) names.add(f.getName().substring(0, f.getName().length() - 4));
        return names;
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
