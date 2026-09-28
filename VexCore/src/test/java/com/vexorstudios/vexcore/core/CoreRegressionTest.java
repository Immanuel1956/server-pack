package com.vexorstudios.vexcore.core;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import com.vexorstudios.vexcore.features.commandroutes.CommandRoutesFeature;
import static org.junit.jupiter.api.Assertions.*;

class CoreRegressionTest {
    @Test void parserRegression() { TextCheck.main(new String[0]); }
    @Test void durationBoundaries() {
        assertEquals(93784, Time.seconds("1d2h3m4s"));
        assertEquals(-1, Time.seconds("999999999999999999999999d"));
        assertEquals(-1, Time.seconds("1d parent add owner"));
        assertEquals(-1, Time.seconds("NaN"));
        assertEquals(0, Time.seconds("0"));
    }
    @Test void pluginListAliases() {
        for (String s : new String[]{"/pl", "/plugins", "/Bukkit:PL", "/bukkit:plugins extra", "/paper:plugins", "/vexcore:pl"})
            assertTrue(CommandRoutesFeature.pluginListLabel(Commands.label(s)), s);
        assertFalse(CommandRoutesFeature.pluginListLabel(Commands.label("/pluginmanager")));
    }
    @Test void messageSoundsRespectOverridesAndSilence() throws Exception {
        var cfg = new YamlConfiguration();
        cfg.loadFromString("default-sound: 'ui.button.click;0.6;1.2'\nerror-sound: 'entity.villager.no;1;1'\nhello: Hello\nerror: '&#FF0000&lERROR bad'\nsounds:\n  muted: ''\n  disabled: {enabled: false, sound: ui.button.click}\n  custom: 'custom:ping;1;1'\n");
        var settings = new YamlConfiguration();
        settings.loadFromString("sounds:\n  click-on-messages: true\n");
        var messages = new Messages(null); messages.load(cfg, settings);
        assertEquals("ui.button.click", messages.messageSound(null, "hello", true, false, false).name());
        assertNull(messages.messageSound(null, "hello", true, false, true), "action-bar status lines never click");
        assertNull(messages.messageSound(null, "hello", false, false, false));
        assertNull(messages.messageSound(null, "hello", true, true, false));
        assertNull(messages.messageSound(null, "muted", true, false, false));
        assertFalse(messages.messageSound(null, "disabled", true, false, false).enabled());
        assertEquals("entity.villager.no", messages.messageSound(null, "error", true, false, false).name());
        assertEquals("custom:ping", messages.messageSound(null, "custom", true, false, false).name());
        Feature feature = new Feature() { protected void enable() {} };
        feature.config().set("sounds.hello", "custom:own;1;1");
        assertEquals("custom:own", messages.messageSound(feature, "hello", true, false, false).name());
    }

    @Test void messageClickIsOffByDefault() throws Exception {
        var cfg = new YamlConfiguration();
        cfg.loadFromString("default-sound: 'ui.button.click;0.6;1.2'\nhello: Hello\nsounds:\n  custom: 'custom:ping;1;1'\n");
        var messages = new Messages(null); messages.load(cfg);
        assertNull(messages.messageSound(null, "hello", true, false, false));
        assertEquals("custom:ping", messages.messageSound(null, "custom", true, false, false).name());
        assertFalse(SoundSpec.ticking());
    }
}
