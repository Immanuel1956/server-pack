package com.vexorstudios.vexcore.features.sell;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** prices.yml prices every survival item and nothing unobtainable; the unobtainable list works. */
class SellPricesTest {

    private static YamlConfiguration load(String path) throws Exception {
        YamlConfiguration yml = new YamlConfiguration();
        yml.load(Path.of("src/main/resources", path).toFile());
        return yml;
    }

    @Test
    void everyPriceIsARealItemAboveZero() throws Exception {
        YamlConfiguration prices = load(SellFeature.PRICES);
        assertTrue(prices.getKeys(false).size() > 1300, "prices for every obtainable item");
        for (String key : prices.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            assertNotNull(m, key + " is not an item"); // isItem() needs a running server
            assertTrue(prices.getDouble(key) > 0, key + " has no price");
        }
        assertEquals(3, prices.getDouble("CARROT"));
        assertEquals(10, prices.getDouble("IRON_INGOT"));
        assertEquals(90, prices.getDouble("IRON_BLOCK"), "a block is worth its 9 ingots, not more");
    }

    @Test
    void unobtainableItemsHaveNoPrice() throws Exception {
        YamlConfiguration prices = load(SellFeature.PRICES);
        List<String> never = load("features/sell/config.yml").getStringList("unobtainable");
        assertFalse(never.isEmpty());
        for (String key : prices.getKeys(false)) {
            for (String pattern : never) {
                assertFalse(SellFeature.matches(key, pattern.toUpperCase(java.util.Locale.ROOT)), key + " is unobtainable (" + pattern + ") but has a price");
            }
        }
        for (String pattern : never) {
            boolean any = false;
            for (Material m : Material.values()) if (!m.isLegacy() && SellFeature.matches(m.name(), pattern)) any = true;
            assertTrue(any, pattern + " matches no item");
        }
    }

    @Test
    void patterns() {
        assertTrue(SellFeature.matches("ZOMBIE_SPAWN_EGG", "*_SPAWN_EGG"));
        assertTrue(SellFeature.matches("INFESTED_STONE", "INFESTED_*"));
        assertTrue(SellFeature.matches("BEDROCK", "BEDROCK"));
        assertFalse(SellFeature.matches("BEDROCK_X", "BEDROCK"));
        assertFalse(SellFeature.matches("EGG", "*_SPAWN_EGG"));
    }
}
