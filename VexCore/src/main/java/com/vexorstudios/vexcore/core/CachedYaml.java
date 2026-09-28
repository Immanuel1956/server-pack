package com.vexorstudios.vexcore.core;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A YAML file that remembers every lookup. Bukkit walks the tree for each {@code getX("a.b.c")}
 * (cutting the path into new strings on the way); features read their settings in event handlers
 * and timers, so here a repeated read is one hash lookup and allocates nothing.
 *
 * <p>Same answers as {@link YamlConfiguration}: every typed getter goes through
 * {@link #get(String, Object)}, and a missing value is remembered as missing, so the caller's own
 * fallback still applies. Any change to the file ({@code set}, {@code createSection}, a load)
 * forgets everything. String lists come back read-only.
 */
public final class CachedYaml extends YamlConfiguration {

    private static final Object MISSING = new Object();
    /** Paths are the code's own; a limit anyway, in case one is ever built from player input. */
    private static final int LIMIT = 2048;

    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, List<String>> lists = new ConcurrentHashMap<>();

    @Override
    public Object get(String path, Object def) {
        Object value = values.get(path);
        if (value == null) {
            value = super.get(path, MISSING);
            if (value == null) value = MISSING;
            if (values.size() >= LIMIT) values.clear();
            values.put(path, value);
        }
        return value == MISSING ? def : value;
    }

    /** The root's defaults are looked up by the same path (no new string for it every time). */
    @Override
    protected Object getDefault(String path) {
        Configuration defaults = getDefaults();
        return defaults == null ? null : defaults.get(path);
    }

    @Override
    public List<String> getStringList(String path) {
        List<String> list = lists.get(path);
        if (list == null) {
            list = List.copyOf(super.getStringList(path));
            if (lists.size() >= LIMIT) lists.clear();
            lists.put(path, list);
        }
        return list;
    }

    private void changed() {
        values.clear();
        lists.clear();
    }

    @Override
    public void set(String path, Object value) {
        changed();
        super.set(path, value);
    }

    @Override
    public ConfigurationSection createSection(String path) {
        changed();
        return super.createSection(path);
    }

    @Override
    public ConfigurationSection createSection(String path, Map<?, ?> map) {
        changed();
        return super.createSection(path, map);
    }

    @Override
    public void loadFromString(String contents) throws InvalidConfigurationException {
        changed();
        super.loadFromString(contents);
        changed();
    }

    @Override
    public void setDefaults(Configuration defaults) {
        changed();
        super.setDefaults(defaults);
    }
}
