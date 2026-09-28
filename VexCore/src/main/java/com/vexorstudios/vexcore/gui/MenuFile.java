package com.vexorstudios.vexcore.gui;

import com.vexorstudios.vexcore.core.SoundSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A menu file: features/&lt;feature&gt;/gui/&lt;name&gt;.yml.
 * <pre>
 *   title: "&8Menu"          # placeholders allowed, %page%/%pages% in paged menus
 *   rows: 3                  # or size: 27
 *   content-slots: "10-16"   # paged menus: where the entries go
 *   items:                   # placed where they say; add, change and remove freely
 *   templates:               # the look of entries the feature fills in (homes, toggles...)
 *   sounds:                  # open, click, and the feature's own
 * </pre>
 */
public final class MenuFile {

    private final String path;
    private final YamlConfiguration yml;
    private final String title;
    private final int size;
    private final List<MenuItem> items = new ArrayList<>();
    private final Map<String, MenuItem> templates = new LinkedHashMap<>();
    private final List<Integer> contentSlots;
    private final List<String> problems = new ArrayList<>();

    public MenuFile(String path, YamlConfiguration yml) {
        this.path = path;
        this.yml = yml;
        this.title = yml.getString("title", "");
        int rows = yml.contains("rows") ? yml.getInt("rows", 3) : Math.max(1, yml.getInt("size", 27) / 9);
        this.size = Math.max(1, Math.min(6, rows)) * 9;
        this.contentSlots = Slots.parse(yml.get("content-slots"));
        read("items", yml.getConfigurationSection("items"), true);
        read("templates", yml.getConfigurationSection("templates"), false);
        ConfigurationSection sounds = yml.getConfigurationSection("sounds");
        if (sounds != null) for (String key : sounds.getKeys(false)) {
            SoundSpec sound = SoundSpec.of(sounds.get(key));
            if (sound != null && !sound.valid()) problems.add(path + ": sounds." + key + ": unknown sound '" + sound.name() + "'");
        }
        for (int slot : contentSlots) if (slot < 0 || slot >= size) problems.add(path + ": content slot " + slot + " is outside the menu");
    }

    private void read(String section, ConfigurationSection root, boolean placed) {
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            MenuItem item = MenuItem.of(key, s);
            String problem = item.spec().problem();
            if (problem != null) problems.add(path + ": " + section + "." + key + ": " + problem);
            if (placed) {
                if (item.slots().isEmpty()) problems.add(path + ": " + section + "." + key + ": no slot");
                for (int slot : item.slots()) if (slot < 0 || slot >= size) problems.add(path + ": " + section + "." + key + ": slot " + slot + " is outside the menu");
                items.add(item);
            } else {
                templates.put(key, item);
            }
        }
    }

    public String path() {
        return path;
    }

    public YamlConfiguration yml() {
        return yml;
    }

    public String title() {
        return title;
    }

    public int size() {
        return size;
    }

    public List<MenuItem> items() {
        return items;
    }

    public MenuItem template(String key) {
        return templates.get(key);
    }

    /** Template names in file order (menus that list every template, like settings). */
    public List<String> templateKeys() {
        return new ArrayList<>(templates.keySet());
    }

    public List<Integer> contentSlots() {
        return Collections.unmodifiableList(contentSlots);
    }

    public SoundSpec sound(String key) {
        return SoundSpec.of(yml.get("sounds." + key));
    }

    public List<String> problems() {
        return problems;
    }
}
