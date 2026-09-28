package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code %vexcore_...%} PlaceholderAPI expansion. Features add their own keys; a key also
 * answers {@code %vexcore_<key>_<argument>%}, for example {@code %vexcore_toggle_tpa%}.
 * Resolvers may be called off the main thread.
 */
public final class Placeholders {

    @FunctionalInterface
    public interface Resolver {
        String resolve(OfflinePlayer player, String argument);
    }

    private record Entry(Feature owner, Resolver resolver) {
    }

    private final VexCore plugin;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private Runnable unhook;

    public Placeholders(VexCore plugin) {
        this.plugin = plugin;
    }

    public void add(Feature owner, String key, Resolver resolver) {
        entries.put(key.toLowerCase(java.util.Locale.ROOT), new Entry(owner, resolver));
        lookups.clear();
    }

    public void clear(Feature owner) {
        entries.values().removeIf(e -> e.owner == owner);
        lookups.clear();
    }

    /** Which resolver (and argument) a placeholder name goes to, worked out once per name. */
    private record Lookup(Entry entry, String argument) {
    }

    private static final Lookup NONE = new Lookup(null, null);
    private final Map<String, Lookup> lookups = new ConcurrentHashMap<>();

    public String resolve(OfflinePlayer player, String params) {
        Lookup hit = lookups.get(params);
        if (hit == null) {
            hit = find(params);
            if (lookups.size() >= 4096) lookups.clear(); // names come from configs; a limit anyway
            lookups.put(params, hit);
        }
        return hit.entry == null ? null : hit.entry.resolver.resolve(player, hit.argument);
    }

    private Lookup find(String params) {
        String key = params.toLowerCase(java.util.Locale.ROOT);
        Entry exact = entries.get(key);
        if (exact != null) return new Lookup(exact, "");
        int cut = key.length();
        while ((cut = key.lastIndexOf('_', cut - 1)) > 0) {
            Entry e = entries.get(key.substring(0, cut));
            if (e != null) return new Lookup(e, params.substring(cut + 1));
        }
        return NONE;
    }

    private static final java.util.regex.Pattern OWN = java.util.regex.Pattern.compile("%vexcore_([A-Za-z0-9_]+)%");

    /** Fills in %vexcore_...% placeholders itself, so they work even without PlaceholderAPI. */
    public String expand(OfflinePlayer player, String text) {
        if (player == null || text == null || !text.contains("%vexcore_")) return text;
        java.util.regex.Matcher m = OWN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value;
            try {
                value = resolve(player, m.group(1));
            } catch (RuntimeException error) {
                value = null;
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Registers the expansion once PlaceholderAPI is running. */
    public void hook() {
        if (unhook != null || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        try {
            unhook = Expansion.register(this, plugin);
            Text.papi = true;
            plugin.getLogger().info("Hooked into PlaceholderAPI (%vexcore_...%).");
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not hook into PlaceholderAPI: " + error);
        }
    }

    public void unhook() {
        Text.papi = false;
        if (unhook != null) {
            try {
                unhook.run();
            } catch (Throwable ignored) {
            }
            unhook = null;
        }
    }

    /** Only loaded when PlaceholderAPI is installed. */
    static final class Expansion extends PlaceholderExpansion {
        private final Placeholders owner;
        private final VexCore plugin;

        private Expansion(Placeholders owner, VexCore plugin) {
            this.owner = owner;
            this.plugin = plugin;
        }

        static Runnable register(Placeholders owner, VexCore plugin) {
            Expansion expansion = new Expansion(owner, plugin);
            expansion.register();
            return expansion::unregister;
        }

        @Override
        public String getIdentifier() {
            return "vexcore";
        }

        @Override
        public String getAuthor() {
            return "Vexor";
        }

        @Override
        public String getVersion() {
            return plugin.getPluginMeta().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            try {
                return owner.resolve(player, params);
            } catch (RuntimeException error) {
                return null;
            }
        }
    }
}
