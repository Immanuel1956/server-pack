package com.vexorstudios.vexcore.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** numbers: in config.yml: 1000 is 1k, 1500000 is 1.5m, and so on. */
class NumberStyleTest {

    @AfterEach
    void reset() {
        Numbers.style(null);
    }

    @Test
    void shortByDefault() {
        assertEquals("0", Numbers.format(0));
        assertEquals("999", Numbers.format(999));
        assertEquals("1k", Numbers.format(1000));
        assertEquals("1.5k", Numbers.format(1500));
        assertEquals("12.34k", Numbers.format(12_345));
        assertEquals("1.23m", Numbers.format(1_234_567));
        assertEquals("1.15m", Numbers.format(1_150_000), "no floating-point noise (1.149999...)");
        assertEquals("2.5b", Numbers.format(2_500_000_000L));
        assertEquals("3t", Numbers.format(3e12));
        assertEquals("4q", Numbers.format(4e15));
        assertEquals("-1.5k", Numbers.format(-1500));
    }

    @Test
    void neverRoundsUp() {
        assertEquals("999.99k", Numbers.format(999_999), "not 1000k");
        assertEquals("1.99k", Numbers.format(1_999));
        assertEquals("999.99m", Numbers.format(999_999_999));
    }

    @Test
    void moneyKeepsItsCents() {
        assertEquals("12.50", Numbers.formatMoney(12.5, 2, ","));
        assertEquals("999.99", Numbers.formatMoney(999.99, 2, ","));
        assertEquals("1.5k", Numbers.formatMoney(1500.75, 2, ","));
        assertEquals("0.01", Numbers.shortMoney(0.006, 2, ","));
    }

    @Test
    void fullStyleAndSettings() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("numbers.style", "FULL");
        yml.set("numbers.suffixes", List.of("K", "M", "B"));
        Numbers.configure(yml.getConfigurationSection("numbers"));
        assertEquals("1,500", Numbers.format(1500));
        assertEquals("1,500.25", Numbers.formatMoney(1500.25, 2, ","));
        assertEquals("1.5K", Numbers.shortMoney(1500, 2, ","), "menus and _short placeholders stay short");

        yml = new YamlConfiguration();
        yml.set("numbers.short-from", 10_000);
        yml.set("numbers.decimals", 1);
        yml.set("numbers.thousands-separator", ".");
        Numbers.configure(yml.getConfigurationSection("numbers"));
        assertEquals("9.999", Numbers.format(9_999));
        assertEquals("12.3k", Numbers.format(12_345));

        yml = new YamlConfiguration();
        yml.set("numbers.decimals", 0);
        yml.set("numbers.short-from", 5); // below 1000 makes no sense: 1000 is used
        Numbers.configure(yml.getConfigurationSection("numbers"));
        assertEquals("999", Numbers.format(999));
        assertEquals("1k", Numbers.format(1_999));

        Numbers.configure(null);
        assertEquals("1.5k", Numbers.format(1500));
    }

    @Test
    void readsWhatItWrites() {
        for (String shown : List.of("1k", "1.5k", "1.23m", "2.5b", "3t")) {
            assertEquals(shown, Numbers.format(Numbers.parse(shown)), shown);
        }
    }
}
