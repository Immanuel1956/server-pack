package com.vexorstudios.vexcore.core;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** One way to write an item's name, used by the sell messages and the home icon list. */
class ItemNameTest {

    @Test
    void readableNames() {
        assertEquals("Acacia Boat", Text.itemName(Material.ACACIA_BOAT));
        assertEquals("Tnt", Text.itemName(Material.TNT));
        assertEquals("Carrot", Text.itemName(Material.CARROT));
        assertEquals("Netherite Upgrade Smithing Template", Text.itemName(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
    }
}
