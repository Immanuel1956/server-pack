package com.vexorstudios.vexcore.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Text from outside (vote sites, the store, item names, typed text) can't format, click or add placeholders. */
class InjectionTest {

    @Test
    void outsideNamesKeepOnlyNameCharacters() {
        assertEquals("Notch", Text.safeName("Notch"));
        assertEquals(".Bedrock_Guy", Text.safeName(".Bedrock_Guy"));
        assertEquals("clickrun_commandopBob", Text.safeName("<click:run_command:/op Bob>"));
        assertEquals("player_ip", Text.safeName("%player_ip%"));
        assertEquals("xparentsetadmin", Text.safeName("x parent set admin"));
        assertEquals(32, Text.safeName("a".repeat(40)).length());
        assertEquals("", Text.safeName(null));
    }

    @Test
    void typedTextInCommandsHasNoPlaceholders() {
        Map<String, Object> ph = Map.of("player", "Bob", "text", Component.text("hi %player_ip% 50%"));
        assertEquals("say Bob: hi player_ip 50", Text.fill("say %player%: %text%", ph));
        // Values from the config/code are not touched.
        assertEquals("give Bob 50%", Text.fill("give %player% %amount%", Map.of("player", "Bob", "amount", "50%")));
    }

    @Test
    void itemNamesLoseClicksButKeepTheirLook() {
        Component name = Component.text("Sword").clickEvent(ClickEvent.runCommand("/op Bob"))
                .hoverEvent(HoverEvent.showText(Component.text("tip")))
                .append(Component.text(" of doom").clickEvent(ClickEvent.runCommand("/op Bob")));
        Component shown = Component.translatable("chat.square_brackets", name);
        Component safe = Text.inert(shown);
        assertEquals(Text.plain(shown), Text.plain(safe));
        assertFalse(hasClick(safe), "no click left anywhere");
        Component inner = ((net.kyori.adventure.text.TranslatableComponent) safe).arguments().getFirst().asComponent();
        assertNotNull(inner.hoverEvent(), "the tooltip stays");
        assertNull(Text.inert(null));
    }

    private static boolean hasClick(Component c) {
        if (c.clickEvent() != null) return true;
        if (c instanceof net.kyori.adventure.text.TranslatableComponent t) {
            for (var a : t.arguments()) if (a.value() instanceof Component arg && hasClick(arg)) return true;
        }
        for (Component child : c.children()) if (hasClick(child)) return true;
        return false;
    }
}
