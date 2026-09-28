package com.vexorstudios.vexcore.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Every feature's settings sit where the folder grouping says, and config.yml lists them all. */
class FeatureFilesTest {

    @Test
    void groupedPaths() {
        assertEquals("features/social/discord.yml", com.vexorstudios.vexcore.core.Files.configPath("discord"));
        assertEquals("features/social/gui/rules.yml", com.vexorstudios.vexcore.core.Files.menuPath("rules", "rules"));
        assertEquals("features/social/gui/rules-extra.yml", com.vexorstudios.vexcore.core.Files.menuPath("rules", "extra"));
        assertEquals("features/home/config.yml", com.vexorstudios.vexcore.core.Files.configPath("home"));
        assertEquals("features/home/gui/homes.yml", com.vexorstudios.vexcore.core.Files.menuPath("home", "homes"));
        assertNull(com.vexorstudios.vexcore.core.Files.group("home"));
    }

    @Test
    void everyFeatureHasItsFileAndToggle() throws Exception {
        String main = Files.readString(Path.of("src/main/java/com/vexorstudios/vexcore/VexCore.java"));
        YamlConfiguration config = new YamlConfiguration();
        config.load(Path.of("src/main/resources/config.yml").toFile());
        Matcher m = Pattern.compile("features\\.add\\(\"([a-z]+)\"").matcher(main);
        int count = 0;
        while (m.find()) {
            String id = m.group(1);
            count++;
            assertTrue(config.isBoolean("features." + id), "config.yml has no features." + id);
            // A group folder must never be named like a feature: its old folder would be migrated into itself.
            String group = com.vexorstudios.vexcore.core.Files.group(id);
            if (group != null) assertFalse(main.contains("features.add(\"" + group + "\""), group + " is also a feature id");
            assertTrue(Files.exists(Path.of("src/main/resources", com.vexorstudios.vexcore.core.Files.configPath(id))),
                    id + ": " + com.vexorstudios.vexcore.core.Files.configPath(id) + " is missing");
        }
        assertTrue(count > 70);
        assertEquals(count, config.getConfigurationSection("features").getKeys(false).size());
    }

    @Test
    void oldFoldersMoveAndKeepChanges(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path discord = root.resolve("features/discord/config.yml");
        Path rules = root.resolve("features/rules/gui/rules.yml");
        Path spawn = root.resolve("features/spawn/config.yml");
        Path taken = root.resolve("features/afk/config.yml");
        Path home = root.resolve("features/home/config.yml");
        for (Path p : new Path[]{discord, rules, spawn, taken, home}) Files.createDirectories(p.getParent());
        Files.writeString(discord, "url: mine");
        Files.writeString(rules, "title: mine");
        Files.writeString(spawn, "prefix: mine");
        Files.writeString(taken, "prefix: old");
        Files.writeString(home, "prefix: home");
        Files.createDirectories(root.resolve("features/teleport"));
        Files.writeString(root.resolve("features/teleport/afk.yml"), "prefix: new");
        Files.writeString(root.resolve("features/spawn/config.yml.bak"), "kept");

        com.vexorstudios.vexcore.core.Files.migrate(root.toFile(), java.util.logging.Logger.getAnonymousLogger());

        assertEquals("url: mine", Files.readString(root.resolve("features/social/discord.yml")));
        assertEquals("title: mine", Files.readString(root.resolve("features/social/gui/rules.yml")));
        assertEquals("prefix: mine", Files.readString(root.resolve("features/teleport/spawn.yml")));
        assertFalse(Files.exists(root.resolve("features/discord")), "emptied folders are removed");
        assertFalse(Files.exists(root.resolve("features/rules")));
        // The new place was taken: the old file stays where it is, nothing is overwritten.
        assertEquals("prefix: new", Files.readString(root.resolve("features/teleport/afk.yml")));
        assertEquals("prefix: old", Files.readString(taken));
        // Other files keep the old folder; features with their own folder are left alone.
        assertTrue(Files.exists(root.resolve("features/spawn/config.yml.bak")));
        assertEquals("prefix: home", Files.readString(home));

        // A second start moves nothing more.
        com.vexorstudios.vexcore.core.Files.migrate(root.toFile(), java.util.logging.Logger.getAnonymousLogger());
        assertEquals("url: mine", Files.readString(root.resolve("features/social/discord.yml")));
    }
}
