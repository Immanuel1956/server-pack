package com.vexorstudios.vexcore.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BannerTest {

    static Banner.Info sample(List<String> problems, Map<String, String> failed) {
        return new Banner.Info("1.0.0", "Paper", "1.21.10", 21, 0, true, "SQLite", 12,
                70, 2, failed.size(), 58, 141,
                List.of(Map.entry("Vault", true), Map.entry("PlaceholderAPI", true), Map.entry("PacketEvents", false)),
                failed, problems, 184);
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    @Test
    void logoRowsLineUp() {
        List<String> text = plain(Banner.lines(sample(List.of(), Map.of()), Banner.Style.FANCY));
        // Rows 1-6 are the logo: all the same width, so the letters stack.
        for (int r = 1; r <= 6; r++) assertEquals(60, text.get(r).length(), "logo row " + r);
    }

    @Test
    void cleanStartIsShortAndSaysSo() {
        List<String> text = plain(Banner.lines(sample(List.of(), Map.of()), Banner.Style.FANCY));
        assertTrue(text.stream().anyMatch(l -> l.contains("Ready in 184 ms") && l.contains("no problems")));
        assertTrue(text.stream().anyMatch(l -> l.contains("Paper 1.21.10") && l.contains("Java 21")));
        assertTrue(text.stream().anyMatch(l -> l.contains("✔ Vault") && l.contains("✘ PacketEvents")));
        assertTrue(text.size() <= 20, "a clean start fits on one screen");
    }

    @Test
    void problemsAndFailuresAreListed() {
        List<String> text = plain(Banner.lines(sample(List.of("database.yml: could not connect"), Map.of("teams", "boom")), Banner.Style.FANCY));
        assertTrue(text.stream().anyMatch(l -> l.contains("⚠ database.yml: could not connect")));
        assertTrue(text.stream().anyMatch(l -> l.contains("✘ teams") && l.contains("boom")));
        assertTrue(text.stream().anyMatch(l -> l.contains("2 to look at")));
    }

    @Test
    void simpleStyleIsPlainAscii() {
        for (String line : plain(Banner.lines(sample(List.of("x"), Map.of("teams", "boom")), Banner.Style.SIMPLE))) {
            assertTrue(line.chars().allMatch(c -> c < 128), "not ASCII: " + line);
        }
    }

    @Test
    void offStyleIsOneLineWhenClean() {
        List<String> text = plain(Banner.lines(sample(List.of(), Map.of()), Banner.Style.OFF));
        assertEquals(1, text.size());
        assertTrue(text.getFirst().contains("VexCore 1.0.0 ready in 184 ms"));
        assertEquals(Banner.Style.FANCY, Banner.style("nonsense"));
        assertEquals(Banner.Style.SIMPLE, Banner.style(" simple "));
    }
}
