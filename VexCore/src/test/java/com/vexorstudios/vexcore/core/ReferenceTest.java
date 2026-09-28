package com.vexorstudios.vexcore.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** reference.yml and plugin.yml keep up with the code: what /vexcore placeholders and permissions list. */
class ReferenceTest {

    private static String sources() throws Exception {
        StringBuilder all = new StringBuilder();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) all.append(Files.readString(p)).append('\n');
        }
        return all.toString();
    }

    @Test
    void everyPlaceholderIsDescribed() throws Exception {
        Set<String> registered = new TreeSet<>();
        Matcher m = Pattern.compile("placeholder\\(\"([a-z_]+)\"").matcher(sources());
        while (m.find()) registered.add(m.group(1));
        registered.add("toggle"); // the core's own
        YamlConfiguration ref = new YamlConfiguration();
        ref.load(Path.of("src/main/resources/reference.yml").toFile());
        ConfigurationSection groups = ref.getConfigurationSection("placeholders");
        Set<String> documented = new HashSet<>();
        for (String feature : groups.getKeys(false)) {
            for (Map<?, ?> entry : groups.getMapList(feature)) {
                String placeholder = String.valueOf(entry.get("placeholder"));
                assertTrue(placeholder.startsWith("%vexcore_") && placeholder.endsWith("%"), placeholder);
                assertFalse(String.valueOf(entry.get("description")).isBlank(), placeholder + " has no description");
                boolean known = false;
                for (String key : registered) {
                    if (placeholder.equals("%vexcore_" + key + "%") || placeholder.startsWith("%vexcore_" + key + "_")) {
                        documented.add(key);
                        known = true;
                    }
                }
                assertTrue(known, placeholder + " is described but nothing registers it");
            }
        }
        Set<String> missing = new TreeSet<>(registered);
        missing.removeAll(documented);
        assertEquals(Set.of(), missing, "placeholders without a description in reference.yml");
    }

    @Test
    void everyUsedPermissionIsDeclared() throws Exception {
        YamlConfiguration plugin = new YamlConfiguration();
        plugin.options().pathSeparator('/'); // permission names are full of dots
        plugin.load(Path.of("src/main/resources/plugin.yml").toFile());
        Set<String> declared = plugin.getConfigurationSection("permissions").getKeys(false);
        Set<String> used = new TreeSet<>();
        Pattern node = Pattern.compile("\"(vexcore\\.[a-z0-9_.-]*[a-z0-9_-])\"");
        Matcher m = node.matcher(sources());
        while (m.find()) used.add(m.group(1));
        try (var paths = Files.walk(Path.of("src/main/resources"))) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".yml") && !f.endsWith("plugin.yml")).toList()) {
                Matcher y = node.matcher(Files.readString(p));
                while (y.find()) used.add(y.group(1));
            }
        }
        // Examples in comments of permissions a server owner makes up themselves.
        used.removeAll(List.of("vexcore.homes.vip"));
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(declared);
        assertEquals(Set.of(), missing, "permissions used but not in plugin.yml");
    }

    @Test
    void keyallCountdown() {
        assertEquals("0:00", com.vexorstudios.vexcore.features.keyall.KeyallFeature.countdown(0));
        assertEquals("0:09", com.vexorstudios.vexcore.features.keyall.KeyallFeature.countdown(9));
        assertEquals("42:10", com.vexorstudios.vexcore.features.keyall.KeyallFeature.countdown(42 * 60 + 10));
        assertEquals("1:02:03", com.vexorstudios.vexcore.features.keyall.KeyallFeature.countdown(3723));
        assertEquals("0:00", com.vexorstudios.vexcore.features.keyall.KeyallFeature.countdown(-5));
    }
}
