package com.vexorstudios.vexcore.core;

import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A configured sound. Written in YAML either as a section
 * <pre>
 *   enabled: true
 *   sound: "ui.button.click"
 *   volume: 1.0
 *   pitch: 1.2
 * </pre>
 * or as one line: {@code "ui.button.click;1.0;1.2"}.
 *
 * <p>Any name style works: {@code ui.button.click}, {@code UI_BUTTON_CLICK},
 * {@code minecraft:ui.button.click}. A name the game does not know but that has a namespace
 * ({@code mypack:ding}) is played as a resource-pack sound. A bad name never throws.
 */
public record SoundSpec(boolean enabled, String name, float volume, float pitch) {

    private static final Map<String, Optional<Sound>> CACHE = new ConcurrentHashMap<>();

    /** Reads a section, a one-line string or a map. Null when there is nothing usable. */
    public static SoundSpec of(Object raw) {
        if (raw instanceof ConfigurationSection s) {
            String name = s.getString("sound", "");
            if (name == null || name.isBlank()) return null;
            return new SoundSpec(s.getBoolean("enabled", true), name,
                    (float) s.getDouble("volume", 1.0), (float) s.getDouble("pitch", 1.0));
        }
        if (raw instanceof Map<?, ?> m) {
            Object name = m.get("sound");
            if (name == null || name.toString().isBlank()) return null;
            return new SoundSpec(!Boolean.FALSE.equals(m.get("enabled")), name.toString(),
                    number(m.get("volume"), 1f), number(m.get("pitch"), 1f));
        }
        if (raw instanceof String line && !line.isBlank()) {
            String[] parts = line.trim().split("[;,\\s]+");
            return new SoundSpec(true, parts[0],
                    parts.length > 1 ? number(parts[1], 1f) : 1f,
                    parts.length > 2 ? number(parts[2], 1f) : 1f);
        }
        return null;
    }

    private static float number(Object o, float fallback) {
        if (o instanceof Number n) return n.floatValue();
        try {
            return o == null ? fallback : Float.parseFloat(o.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void play(Player player) {
        if (!enabled || player == null) return;
        try {
            Sound sound = resolve(name);
            if (sound != null) {
                player.playSound(player.getLocation(), sound, volume, pitch);
            } else if (name.indexOf(':') > 0) {
                player.playSound(player.getLocation(), name.toLowerCase(Locale.ROOT), volume, pitch);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** True when the game knows this sound. Used by the reload report to point out typos. */
    public boolean valid() {
        return resolve(name) != null || name.indexOf(':') > 0;
    }

    public static Sound resolve(String name) {
        if (name == null || name.isBlank()) return null;
        return CACHE.computeIfAbsent(name, SoundSpec::lookup).orElse(null);
    }

    private static Optional<Sound> lookup(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT);
        String namespace = "minecraft";
        int colon = name.indexOf(':');
        if (colon >= 0) {
            namespace = name.substring(0, colon);
            name = name.substring(colon + 1);
        }
        for (String candidate : new String[]{name, name.replace('-', '.'), name.replace('_', '.').replace('-', '.')}) {
            Sound sound = get(namespace, candidate);
            if (sound != null) return Optional.of(sound);
        }
        // Enum style whose key keeps an underscore (BLOCK_NOTE_BLOCK_PLING): try every split.
        String[] parts = name.split("_");
        if (parts.length > 1 && parts.length <= 8) {
            for (int mask = 0; mask < 1 << (parts.length - 1); mask++) {
                StringBuilder key = new StringBuilder(parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    key.append(((mask >> (i - 1)) & 1) == 1 ? '_' : '.').append(parts[i]);
                }
                Sound sound = get(namespace, key.toString());
                if (sound != null) return Optional.of(sound);
            }
        }
        return Optional.empty();
    }

    private static Sound get(String namespace, String key) {
        try {
            if (!Key.parseableNamespace(namespace) || !Key.parseableValue(key)) return null;
            return Registry.SOUND_EVENT.get(new NamespacedKey(namespace, key));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
