package com.vexorstudios.vexcore.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatesTest {
    @Test void formatsWithZone() {
        assertEquals("01.01.1970 00:00", Dates.format(0, "dd.MM.yyyy HH:mm", "UTC"));
    }
    @Test void badPatternFallsBackInsteadOfThrowing() {
        assertEquals("01.01.1970 00:00", Dates.format(0, "dd.MM.yyyy HH:mm ppp", "UTC"));
        assertEquals("01.01.1970 00:00", Dates.format(0, "", "UTC"));
    }
}
