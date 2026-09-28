package com.vexorstudios.vexcore.features.kits;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class KitCooldownTest {
    @Test void cooldownExpiresOnlyAfterEntireDuration() {
        assertEquals(60, KitsFeature.remaining(1000L, 60, 1000));
        assertEquals(1, KitsFeature.remaining(1000L, 60, 60999));
        assertEquals(0, KitsFeature.remaining(1000L, 60, 61000));
        assertEquals(0, KitsFeature.remaining(1000L, 60, 62000));
    }
    @Test void disabledAndUnclaimedAreReady() {
        assertEquals(0, KitsFeature.remaining(null, 60, 1000));
        assertEquals(0, KitsFeature.remaining(1000L, 0, 1000));
    }
}
