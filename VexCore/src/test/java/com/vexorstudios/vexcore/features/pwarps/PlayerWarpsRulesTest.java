package com.vexorstudios.vexcore.features.pwarps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerWarpsRulesTest {

    @Test
    void defaultCostStartsAt100kAndClimbs() {
        // Defaults: base 100k, +100k per warp owned, multiplier 1.0.
        assertEquals(100_000, PlayerWarpsFeature.price(100_000, 100_000, 1.0, 0));
        assertEquals(200_000, PlayerWarpsFeature.price(100_000, 100_000, 1.0, 1));
        assertEquals(300_000, PlayerWarpsFeature.price(100_000, 100_000, 1.0, 2));
    }

    @Test
    void multiplierCompounds() {
        assertEquals(100_000, PlayerWarpsFeature.price(100_000, 0, 1.5, 0), 1e-6);
        assertEquals(150_000, PlayerWarpsFeature.price(100_000, 0, 1.5, 1), 1e-6);
        assertEquals(225_000, PlayerWarpsFeature.price(100_000, 0, 1.5, 2), 1e-6);
    }

    @Test
    void absurdCostsNeverOverflowIntoFreeWarps() {
        double huge = PlayerWarpsFeature.price(100_000, 0, 1000, 500);
        assertEquals(Double.MAX_VALUE, huge); // infinite would compare oddly; capped instead
        assertTrue(PlayerWarpsFeature.price(0, 0, 1, 5) >= 0);
    }

    @Test
    void namesAreShortPlainAndNotSubcommands() {
        assertTrue(PlayerWarpsFeature.validName("Shop_1", 3, 16));
        assertTrue(PlayerWarpsFeature.validName("my-base", 3, 16));
        assertFalse(PlayerWarpsFeature.validName("ab", 3, 16));
        assertFalse(PlayerWarpsFeature.validName("a".repeat(17), 3, 16));
        assertFalse(PlayerWarpsFeature.validName("<red>x", 3, 16));
        assertFalse(PlayerWarpsFeature.validName("&cshop", 3, 16));
        assertFalse(PlayerWarpsFeature.validName("sp ace", 3, 16));
        assertFalse(PlayerWarpsFeature.validName(null, 3, 16));
        assertTrue(PlayerWarpsFeature.RESERVED.contains("set"));
        assertTrue(PlayerWarpsFeature.RESERVED.contains("delete"));
    }
}
