package com.vexorstudios.vexcore.core;

import org.junit.jupiter.api.Test;

import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

/** Payouts round down and charges up, without floating-point noise costing or giving a cent. */
class MoneyRoundingTest {

    @Test
    void floatNoiseIsIgnored() {
        assertEquals(57.00, Numbers.round(0.57 * 100, 2, RoundingMode.FLOOR));
        assertEquals(3.30, Numbers.round(1.1 * 3, 2, RoundingMode.CEILING));
        assertEquals(0.30, Numbers.round(0.1 * 3, 2, RoundingMode.FLOOR));
    }

    @Test
    void payoutsRoundDownChargesUp() {
        assertEquals(0.38, Numbers.round(0.006 * 64, 2, RoundingMode.FLOOR));
        assertEquals(0.00, Numbers.round(0.006, 2, RoundingMode.FLOOR));
        assertEquals(0.01, Numbers.round(0.001, 2, RoundingMode.CEILING));
        assertEquals(0, Numbers.round(0.95, 0, RoundingMode.FLOOR), "a taxed $1 in a whole-dollar economy");
    }

    @Test
    void splittingNeverPaysMore() {
        double each = 0.006;
        double whole = Numbers.round(each * 64, 2, RoundingMode.FLOOR);
        double split = 0;
        for (int i = 0; i < 64; i++) split += Numbers.round(each, 2, RoundingMode.FLOOR);
        assertTrue(split <= whole, "one by one " + split + " vs together " + whole);
        // Paying tax in pieces: 100 payments of $1 at 5% never deliver more than one $100 payment.
        double pieces = 0;
        for (int i = 0; i < 100; i++) pieces += Numbers.round(1 * 0.95, 0, RoundingMode.FLOOR);
        assertTrue(pieces <= Numbers.round(100 * 0.95, 0, RoundingMode.FLOOR));
    }

    @Test
    void refusesNonsenseAmounts() {
        for (String bad : new String[]{"-5", "NaN", "Infinity", "0x10", "1e9", "", "abc", "0"}) {
            assertTrue(Double.isNaN(Numbers.amount(bad, 2)), bad);
        }
        assertEquals(1500, Numbers.amount("1.5k", 2));
    }
}
