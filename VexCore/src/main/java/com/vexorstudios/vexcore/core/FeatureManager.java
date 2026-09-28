package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Turns features on and off following {@code features:} in config.yml. */
public final class FeatureManager {

    private final VexCore plugin;
    private final Map<String, Supplier<Feature>> available = new LinkedHashMap<>();
    private final Map<String, Feature> active = new LinkedHashMap<>();
    private final Map<String, String> failed = new LinkedHashMap<>();

    public FeatureManager(VexCore plugin) {
        this.plugin = plugin;
    }

    public void add(String id, Supplier<Feature> factory) {
        available.put(id, factory);
    }

    public Set<String> available() {
        return available.keySet();
    }

    public Collection<Feature> active() {
        return active.values();
    }

    public Feature get(String id) {
        return active.get(id);
    }

    /** Features that threw while enabling, with the error. */
    public Map<String, String> failed() {
        return failed;
    }

    public boolean wanted(YamlConfiguration config, String id) {
        return config.getBoolean("features." + id, true);
    }

    public void enableAll(YamlConfiguration config) {
        failed.clear();
        for (Map.Entry<String, Supplier<Feature>> e : available.entrySet()) {
            if (!wanted(config, e.getKey())) continue;
            Feature feature = e.getValue().get();
            try {
                feature.start(plugin, e.getKey());
                active.put(e.getKey(), feature);
            } catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE, "Feature '" + e.getKey() + "' failed to start", error);
                failed.put(e.getKey(), String.valueOf(error.getMessage()));
                try {
                    feature.stop();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public void prepareShutdown() {
        for (Feature feature : new ArrayList<>(active.values())) {
            try { feature.prepareShutdown(); }
            catch (RuntimeException e) { plugin.getLogger().log(Level.SEVERE, "Could not settle " + feature.id(), e); }
        }
    }

    public void disableAll() {
        List<Feature> reversed = new ArrayList<>(active.values());
        java.util.Collections.reverse(reversed);
        for (Feature feature : reversed) {
            try {
                feature.stop();
            } catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE, "Feature '" + feature.id() + "' failed to stop cleanly", error);
            }
        }
        active.clear();
    }
}
