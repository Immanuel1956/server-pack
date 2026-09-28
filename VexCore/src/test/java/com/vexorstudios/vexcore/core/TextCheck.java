package com.vexorstudios.vexcore.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Self-check for the text parser (legacy/hex/MiniMessage mix). Run with the test classpath:
 * {@code java -ea com.vexorstudios.vexcore.core.TextCheck} - it throws on the first failure.
 */
public final class TextCheck {

    record Run(String text, Style style) {
    }

    public static void main(String[] args) {
        // A colour code ends bold, like vanilla: "Text" is white and not bold.
        List<Run> r = flatten(Text.parse("&#16D223&lHOME &fText"));
        check(style(r, "HOME").color().equals(TextColor.fromHexString("#16D223")), "hex colour");
        check(style(r, "HOME").decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE, "bold");
        check(style(r, "Text").color().equals(NamedTextColor.WHITE), "white after &f");
        check(style(r, "Text").decoration(TextDecoration.BOLD) != TextDecoration.State.TRUE, "&f ends bold");

        // Legacy codes inside a MiniMessage click tag keep the click.
        r = flatten(Text.parse("<click:run_command:'/tpaccept Bob'>&a&l[ACCEPT]</click> &c[DENY]"));
        check(style(r, "[ACCEPT]").clickEvent() != null, "click survives legacy codes");
        check(style(r, "[ACCEPT]").color().equals(NamedTextColor.GREEN), "green inside click");
        check(style(r, "[DENY]").clickEvent() == null, "click closed");
        check(style(r, "[DENY]").color().equals(NamedTextColor.RED), "red after click");

        // Bare #hex (SetupCore style) and &x hex; MiniMessage hex untouched.
        check(style(flatten(Text.parse("#019BEEHi")), "Hi").color().equals(TextColor.fromHexString("#019BEE")), "bare hex");
        check(style(flatten(Text.parse("&x&f&f&0&0&0&0Red")), "Red").color().equals(TextColor.fromHexString("#FF0000")), "&x hex");
        check(style(flatten(Text.parse("<#00ff00>Green")), "Green").color().equals(TextColor.fromHexString("#00FF00")), "mm hex");
        check(Text.plain(Text.parse("Issue #12345678")).equals("Issue #12345678"), "8 hex digits stay text");

        // Component placeholders are inserted as-is: a player can't inject tags or codes.
        Component c = Text.parse("&7Hi %name%!", null, Map.of("name", Component.text("<red>&cEvil")));
        check(Text.plain(c).equals("Hi <red>&cEvil!"), "component placeholder not parsed: " + Text.plain(c));
        check(Text.fill("give %player% %amount%", Map.of("player", "Bob", "amount", 5.0)).equals("give Bob 5"), "fill");

        // Item text is not italic unless asked.
        check(Text.item("&aSword", null, Map.of()).decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE, "item not italic");

        // Links become clickable.
        Component link = Text.links(Text.parse("Join https://discord.gg/x now"), "https://discord.gg/x");
        check(flatten(link).stream().anyMatch(x -> x.style.clickEvent() != null
                && x.style.clickEvent().action() == ClickEvent.Action.OPEN_URL), "link clickable");

        System.out.println("TextCheck: all passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static Style style(List<Run> runs, String contains) {
        for (Run run : runs) if (run.text.contains(contains)) return run.style;
        throw new AssertionError("no text '" + contains + "' in " + runs);
    }

    private static List<Run> flatten(Component root) {
        List<Run> out = new ArrayList<>();
        walk(root, Style.empty(), out);
        return out;
    }

    private static void walk(Component c, Style parent, List<Run> out) {
        Style style = parent.merge(c.style(), Style.Merge.Strategy.ALWAYS);
        if (c instanceof TextComponent t && !t.content().isEmpty()) out.add(new Run(t.content(), style));
        for (Component child : c.children()) walk(child, style, out);
    }
}
