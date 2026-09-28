package com.vexorstudios.vexcore.features.links;

import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * features/social/links.yml: more link commands like /discord (/website, /tiktok, /youtube...).
 * Every entry with {@code enabled: true} becomes a command with its own name, aliases and
 * permission; its message works exactly like discord.yml.
 */
public final class LinksFeature extends Feature {

    @Override
    protected void enable() {
        ConfigurationSection links = config().getConfigurationSection("links");
        if (links == null) return;
        for (String key : links.getKeys(false)) {
            ConfigurationSection s = links.getConfigurationSection(key);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            String name = s.getString("name", key).toLowerCase(Locale.ROOT).trim();
            if (name.isEmpty() || name.contains(" ")) {
                problems().add(com.vexorstudios.vexcore.core.Files.configPath(id()) + ": links." + key + ": '" + name + "' can't be a command name");
                continue;
            }
            // Never steal a name from another VexCore command (/store, /vote...).
            if (plugin.commands().taken(name)) {
                problems().add(com.vexorstudios.vexcore.core.Files.configPath(id()) + ": links." + key + ": /" + name + " is already a VexCore command");
                continue;
            }
            java.util.List<String> aliases = new java.util.ArrayList<>();
            for (String alias : s.getStringList("aliases")) if (!plugin.commands().taken(alias)) aliases.add(alias);
            command("link-" + key.toLowerCase(Locale.ROOT), name, aliases, s.getString("permission", ""),
                    s.getString("description", "The " + key + " link"),
                    (sender, label, args) -> LinkFeature.show(sender, s, plugin.messages().prefix(this)));
        }
    }

    /** A link of this file by its key or command name, or null. For the social broadcasts. */
    public ConfigurationSection link(String name) {
        ConfigurationSection links = config().getConfigurationSection("links");
        if (links == null) return null;
        for (String key : links.getKeys(false)) {
            ConfigurationSection s = links.getConfigurationSection(key);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            if (key.equalsIgnoreCase(name) || s.getString("name", key).equalsIgnoreCase(name)) return s;
        }
        return null;
    }
}
