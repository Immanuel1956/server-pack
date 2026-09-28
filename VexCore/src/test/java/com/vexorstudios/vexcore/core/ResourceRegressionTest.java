package com.vexorstudios.vexcore.core;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class ResourceRegressionTest {
    @Test void allYamlResourcesParseAndMenusFit() throws Exception {
        try (var paths = java.nio.file.Files.walk(Path.of("src/main/resources"))) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".yml")).toList()) {
                var yaml = new YamlConfiguration(); yaml.load(p.toFile());
                if (!p.toString().contains("/gui/")) continue;
                int size = yaml.getInt("rows", 6) * 9;
                var items = yaml.getConfigurationSection("items");
                if (items != null) for (String key : items.getKeys(false)) {
                    if (items.contains(key + ".slot")) assertTrue(items.getInt(key + ".slot") >= 0 && items.getInt(key + ".slot") < size, p + ":" + key);
                }
            }
        }
        assertFalse(java.nio.file.Files.exists(Path.of("src/main/resources/features/smallcaps/config.yml")));
    }
}
