package com.vexorstudios.vexcore.features.quests;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Placed-block marks: every block of a world, negative coordinates and build height included, gets its own key. */
class BlockKeyTest {

    @Test
    void neighboursNeverShareAKey() {
        Set<Long> keys = new HashSet<>();
        for (int x = -2; x <= 2; x++) for (int y = -64; y <= -62; y++) for (int z = -2; z <= 2; z++) {
            assertTrue(keys.add(QuestsFeature.blockKey(x, y, z)), x + " " + y + " " + z);
        }
        assertNotEquals(QuestsFeature.blockKey(30_000_000, 319, -30_000_000), QuestsFeature.blockKey(-30_000_000, 319, 30_000_000));
        assertNotEquals(QuestsFeature.blockKey(0, 319, 0), QuestsFeature.blockKey(0, -64, 0));
    }
}
