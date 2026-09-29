package com.vexorstudios.vexcore.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Every dialog the code opens has its file in the feature's gui/dialogs/ folder, and each file reads. */
class DialogFilesTest {

    private static String sources() throws Exception {
        StringBuilder all = new StringBuilder();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                all.append("\n//FILE ").append(p).append('\n').append(Files.readString(p));
            }
        }
        return all.toString();
    }

    @Test
    void askedDialogsHaveFiles() throws Exception {
        Matcher m = Pattern.compile("Dialogs\\.ask\\(this, \\w+, \"([a-z-]+)\"").matcher(sources());
        Set<String> checked = new TreeSet<>();
        String src = sources();
        while (m.find()) {
            // The feature is the one whose file the call is in: features/<id>/...
            int file = src.lastIndexOf("//FILE ", m.start());
            String path = src.substring(file + 7, src.indexOf('\n', file));
            Matcher id = Pattern.compile("features/([a-z]+)/").matcher(path.replace('\\', '/'));
            assertTrue(id.find(), path);
            String dialog = Files_.dialogPath(id.group(1), m.group(1));
            YamlConfiguration yml = load(dialog);
            assertFalse(yml.getString("title", "").isBlank(), dialog + " has no title");
            assertFalse(yml.getString("input.label", "").isBlank(), dialog + " has no input.label");
            for (String b : List.of("confirm", "cancel")) {
                assertFalse(yml.getString("buttons." + b + ".text", "").isBlank(), dialog + " has no buttons." + b + ".text");
            }
            checked.add(dialog);
        }
        assertTrue(checked.size() >= 7, "teams search/invite/create, giveaway money, invest/withdraw, coinflip create: " + checked);
    }

    @Test
    void everyDialogFileReads() throws Exception {
        int count = 0;
        try (var paths = Files.walk(Path.of("src/main/resources/features"))) {
            for (Path p : paths.filter(f -> f.toString().replace('\\', '/').contains("/gui/dialogs/") && f.toString().endsWith(".yml")).toList()) {
                YamlConfiguration yml = new YamlConfiguration();
                yml.load(p.toFile());
                assertTrue(yml.contains("title"), p + " has no title");
                count++;
            }
        }
        assertTrue(count >= 12, "home (4), report, teams (3), giveaway, invest (2), coinflip");
    }

    private static YamlConfiguration load(String path) throws Exception {
        Path file = Path.of("src/main/resources", path);
        assertTrue(Files.exists(file), path + " is missing");
        YamlConfiguration yml = new YamlConfiguration();
        yml.load(file.toFile());
        return yml;
    }

    /** The plugin's Files class (java.nio.file.Files is imported here). */
    private static final class Files_ {
        static String dialogPath(String feature, String dialog) {
            return com.vexorstudios.vexcore.core.Files.dialogPath(feature, dialog);
        }
    }
}
