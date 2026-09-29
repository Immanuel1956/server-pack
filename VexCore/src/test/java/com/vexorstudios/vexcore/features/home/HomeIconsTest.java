package com.vexorstudios.vexcore.features.home;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Every item's picture is asked for in the atlas it lives in (items/... is not in blocks). */
class HomeIconsTest {

    @Test
    void everySpriteHasItsAtlas() {
        Map<String, String> sprites = HomeDialogs.sprites();
        assertTrue(sprites.size() > 1400, "a picture for every item");
        for (Map.Entry<String, String> e : sprites.entrySet()) {
            String sprite = e.getValue();
            assertTrue(sprite.startsWith("block/") || sprite.startsWith("item/"), e.getKey() + ": " + sprite + " is in neither atlas");
            assertEquals(sprite.startsWith("item/") ? "minecraft:items" : "minecraft:blocks", HomeDialogs.atlas(sprite).asString(), e.getKey());
        }
        assertEquals("item/acacia_boat", sprites.get("acacia_boat"));
        assertEquals("minecraft:items", HomeDialogs.atlas("item/acacia_boat").asString());
        assertEquals("minecraft:blocks", HomeDialogs.atlas("block/acacia_planks").asString());
    }
}
