package com.vexorstudios.vexcore.core;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A countdown survives a nudge or a jump; walking away or falling cancels it. */
class TeleportMoveTest {

    private static Location at(double x, double y, double z) {
        return new Location(null, x, y, z);
    }

    @Test
    void nudgesAndJumpsDontCount() {
        Location start = at(0.5, 64, 0.5);
        assertTrue(Teleports.stayed(start, at(0.5, 64, 0.5), 1.0));
        assertTrue(Teleports.stayed(start, at(1.3, 64, 0.9), 1.0), "bumped sideways");
        assertTrue(Teleports.stayed(start, at(0.5, 65.25, 0.5), 1.0), "a jump in place");
        assertTrue(Teleports.stayed(start, at(1.4, 64, 0.5), 1.0), "0.9 blocks across a block edge");
    }

    @Test
    void walkingAwayOrFallingCounts() {
        Location start = at(0.5, 64, 0.5);
        assertFalse(Teleports.stayed(start, at(2.0, 64, 0.5), 1.0), "walked away");
        assertFalse(Teleports.stayed(start, at(0.5, 60, 0.5), 1.0), "fell");
        assertFalse(Teleports.stayed(start, at(0.6, 64, 0.5), 0), "tolerance 0: any movement");
        assertFalse(Teleports.stayed(null, at(0.5, 64, 0.5), 1.0));
    }
}
