package com.vexorstudios.vexcore.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SoundGateTest {

    private static final SoundSpec CLICK = new SoundSpec(true, "ui.button.click", 1, 1);
    private static final SoundSpec OPEN = new SoundSpec(true, "block.chest.open", 1, 1);
    private static final SoundSpec REWARD = new SoundSpec(true, "entity.player.levelup", 1, 1);
    private static final SoundSpec ERROR = new SoundSpec(true, "entity.villager.no", 1, 1);

    @Test
    void theResultOfAClickWins() {
        SoundGate gate = new SoundGate();
        UUID p = UUID.randomUUID();
        assertTrue(gate.offer(p, CLICK, SoundGate.CLICK, 0), "first sound of the tick schedules the play");
        assertFalse(gate.offer(p, REWARD, SoundGate.NORMAL, 0));
        assertEquals(REWARD, gate.take(p, 50));
        assertNull(gate.take(p, 50), "only one plays");
    }

    @Test
    void anErrorBeatsEverythingAndTheFirstOfEqualsWins() {
        SoundGate gate = new SoundGate();
        UUID p = UUID.randomUUID();
        gate.offer(p, CLICK, SoundGate.CLICK, 0);
        gate.offer(p, OPEN, SoundGate.MENU, 0);
        gate.offer(p, ERROR, SoundGate.ERROR, 0);
        gate.offer(p, REWARD, SoundGate.NORMAL, 0);
        assertEquals(ERROR, gate.take(p, 50));

        UUID q = UUID.randomUUID();
        gate.offer(q, REWARD, SoundGate.NORMAL, 0);
        gate.offer(q, new SoundSpec(true, "entity.experience_orb.pickup", 1, 1), SoundGate.NORMAL, 0);
        assertEquals(REWARD, gate.take(q, 50));
    }

    @Test
    void aClickRightAfterASoundIsDroppedButSomethingMoreImportantStillPlays() {
        SoundGate gate = new SoundGate();
        UUID p = UUID.randomUUID();
        gate.offer(p, REWARD, SoundGate.NORMAL, 0);
        gate.take(p, 50);
        assertFalse(gate.offer(p, CLICK, SoundGate.CLICK, 100), "within the window: dropped");
        assertFalse(gate.offer(p, REWARD, SoundGate.NORMAL, 120), "as important, within the window: dropped");
        assertTrue(gate.offer(p, ERROR, SoundGate.ERROR, 120), "more important: plays");
        assertEquals(ERROR, gate.take(p, 170));
        assertTrue(gate.offer(p, CLICK, SoundGate.CLICK, 170 + SoundGate.WINDOW_MS), "after the window: plays again");
    }

    @Test
    void playersDontAffectEachOther() {
        SoundGate gate = new SoundGate();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(gate.offer(a, CLICK, SoundGate.CLICK, 0));
        assertTrue(gate.offer(b, CLICK, SoundGate.CLICK, 0));
        assertEquals(CLICK, gate.take(a, 50));
        assertEquals(CLICK, gate.take(b, 50));
    }
}
