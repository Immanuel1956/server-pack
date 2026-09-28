package com.vexorstudios.vexcore.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns config text into components. Every string in every VexCore file can mix:
 * <ul>
 *   <li>legacy codes: {@code &a}, {@code &l}, {@code &r} (and {@code §})</li>
 *   <li>hex colours: {@code &#FF00AA}, {@code #FF00AA}, {@code &x&F&F&0&0&A&A}</li>
 *   <li>MiniMessage: {@code <gradient:#f00:#00f>}, {@code <click:run_command:/spawn>}, {@code <hover:show_text:'hi'>}</li>
 *   <li>{@code %placeholders%}, and PlaceholderAPI placeholders when it is installed</li>
 * </ul>
 * A colour code ends the bold/italic/... before it, exactly like in vanilla chat.
 *
 * <p>Placeholder values that are {@link Component}s are inserted as they are and never parsed,
 * so anything a player typed can be passed that way without it being able to inject formatting.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] COLORS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+");
    private static final Pattern TAG_UNSAFE = Pattern.compile("[^a-z0-9_-]");
    private static final TextReplacementConfig LINKS = TextReplacementConfig.builder()
            .match(URL)
            .replacement(b -> b.clickEvent(ClickEvent.openUrl(b.content())))
            .build();

    /** Set while PlaceholderAPI is hooked. */
    static volatile boolean papi;

    private Text() {
    }

    public static Component parse(String text) {
        return parse(text, null, Map.of());
    }

    public static Component parse(String text, Player viewer, Map<String, ?> placeholders) {
        if (text == null || text.isEmpty()) return Component.empty();
        TagResolver.Builder tags = null;
        if (placeholders != null && !placeholders.isEmpty() && text.indexOf('%') >= 0) {
            for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
                String token = "%" + entry.getKey() + "%";
                if (!text.contains(token)) continue;
                if (entry.getValue() instanceof Component component) {
                    String tag = "vx_" + TAG_UNSAFE.matcher(entry.getKey().toLowerCase(Locale.ROOT)).replaceAll("_");
                    text = text.replace(token, "<" + tag + ">");
                    if (tags == null) tags = TagResolver.builder();
                    tags.resolver(Placeholder.component(tag, component));
                } else {
                    text = text.replace(token, string(entry.getValue()));
                }
            }
        }
        if (viewer != null && text.contains("%vexcore_")) {
            com.vexorstudios.vexcore.VexCore core = com.vexorstudios.vexcore.VexCore.get();
            if (core != null && core.placeholders() != null) text = core.placeholders().expand(viewer, text);
        }
        if (papi && viewer != null && text.indexOf('%') >= 0) text = PapiBridge.apply(viewer, text);
        if (tags != null) return MM.deserialize(toMini(text), tags.build());
        return cached(text);
    }

    /**
     * Finished lines: menus, scoreboards, name tags and messages parse the same text over and over.
     * Two generations: when the new one fills up it becomes the old one, and anything still used
     * from the old one moves back to the new one. Lines in use stay; one-off lines (a balance that
     * changed) drop out after two turns.
     */
    private static final int GENERATION = 4096;
    private static volatile java.util.Map<String, Component> young = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile java.util.Map<String, Component> old = new java.util.concurrent.ConcurrentHashMap<>();

    private static Component cached(String text) {
        java.util.Map<String, Component> y = young;
        Component c = y.get(text);
        if (c != null) return c;
        c = old.get(text);
        if (c == null) c = MM.deserialize(toMini(text));
        if (y.size() >= GENERATION) {
            synchronized (Text.class) {
                if (young == y) {
                    old = y;
                    young = y = new java.util.concurrent.ConcurrentHashMap<>();
                } else {
                    y = young;
                }
            }
        }
        y.put(text, c);
        return c;
    }

    /** For item names and lore: same as {@link #parse} but not italic unless asked for. */
    public static Component item(String text, Player viewer, Map<String, ?> placeholders) {
        return parse(text, viewer, placeholders).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Makes every http(s) link in a chat message clickable. */
    public static Component links(Component component, String source) {
        return source != null && source.contains("http") ? component.replaceText(LINKS) : component;
    }

    /** Only the {@code %key%} replacement, for strings that are not shown (commands, file names). */
    public static String fill(String text, Map<String, ?> placeholders) {
        if (text == null || placeholders == null || text.indexOf('%') < 0) return text;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            Object value = entry.getValue();
            text = text.replace("%" + entry.getKey() + "%",
                    value instanceof Component c ? plain(c) : string(value));
        }
        return text;
    }

    /** PlaceholderAPI placeholders only (for commands). Unchanged without PlaceholderAPI. */
    public static String papi(Player player, String text) {
        return papi && player != null && text.indexOf('%') >= 0 ? PapiBridge.apply(player, text) : text;
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** A YAML value that may be one string or a list of them. */
    public static List<String> lines(Object raw) {
        if (raw == null) return Collections.emptyList();
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(o == null ? "" : o.toString());
            return out;
        }
        return List.of(raw.toString());
    }

    private static String string(Object value) {
        if (value == null) return "";
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString(d.longValue());
        return value.toString();
    }

    // ── Legacy to MiniMessage ─────────────────────────────────────────────

    /**
     * Rewrites legacy and hex codes as MiniMessage tags. Tags opened here are closed again at the
     * next colour code or {@code &r}, which is what gives colour codes their legacy meaning of
     * "reset the formatting". Tags the user wrote themselves are left alone.
     */
    public static String toMini(String s) {
        if (s.indexOf('&') < 0 && s.indexOf('§') < 0 && s.indexOf('#') < 0) return s;
        StringBuilder out = new StringBuilder(s.length() + 32);
        List<String> open = new ArrayList<>(4);
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            // Links keep every character: "&b=2" in a URL is not the colour aqua.
            if (c == '<' && s.regionMatches(true, i, "<click:", 0, 7)) {
                int end = s.indexOf('>', i);
                if (end > 0) {
                    out.append(s, i, end + 1);
                    i = end;
                    continue;
                }
            }
            if ((c == 'h' || c == 'H') && (s.regionMatches(true, i, "http://", 0, 7) || s.regionMatches(true, i, "https://", 0, 8))) {
                int end = i;
                while (end < n && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != '<') end++;
                out.append(s, i, end);
                i = end - 1;
                continue;
            }
            if ((c == '&' || c == '§') && i + 1 < n) {
                char k = Character.toLowerCase(s.charAt(i + 1));
                if (k == '#' && hex(s, i + 2, 6)) {
                    color(out, open, "#" + s.substring(i + 2, i + 8));
                    i += 7;
                    continue;
                }
                if (k == 'x' && bungeeHex(s, i)) {
                    StringBuilder hex = new StringBuilder("#");
                    for (int j = 0; j < 6; j++) hex.append(s.charAt(i + 3 + j * 2));
                    color(out, open, hex.toString());
                    i += 13;
                    continue;
                }
                int index = "0123456789abcdef".indexOf(k);
                if (index >= 0) {
                    color(out, open, COLORS[index]);
                    i++;
                    continue;
                }
                String decoration = switch (k) {
                    case 'k' -> "obfuscated";
                    case 'l' -> "bold";
                    case 'm' -> "strikethrough";
                    case 'n' -> "underlined";
                    case 'o' -> "italic";
                    default -> null;
                };
                if (decoration != null) {
                    out.append('<').append(decoration).append('>');
                    open.add(decoration);
                    i++;
                    continue;
                }
                if (k == 'r') {
                    close(out, open);
                    i++;
                    continue;
                }
            }
            // Bare #RRGGBB, but not inside MiniMessage (<#..>, <color:#..>, <gradient:#..:#..>).
            if (c == '#' && hex(s, i + 1, 6) && !hex(s, i + 7, 1)
                    && (i == 0 || "<:&§=\"'#".indexOf(s.charAt(i - 1)) < 0)) {
                color(out, open, s.substring(i, i + 7));
                i += 6;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static void color(StringBuilder out, List<String> open, String color) {
        close(out, open);
        out.append("<color:").append(color).append('>');
        open.add("color");
    }

    private static void close(StringBuilder out, List<String> open) {
        for (int j = open.size() - 1; j >= 0; j--) out.append("</").append(open.get(j)).append('>');
        open.clear();
    }

    private static boolean hex(String s, int from, int length) {
        if (from + length > s.length()) return false;
        for (int i = from; i < from + length; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** {@code &x&R&R&G&G&B&B}: 14 characters starting at {@code i}. */
    private static boolean bungeeHex(String s, int i) {
        if (i + 13 >= s.length()) return false;
        for (int j = 0; j < 6; j++) {
            char marker = s.charAt(i + 2 + j * 2);
            if ((marker != '&' && marker != '§') || Character.digit(s.charAt(i + 3 + j * 2), 16) < 0) return false;
        }
        return true;
    }

    /** Kept apart so PlaceholderAPI's classes are only touched when it is installed. */
    static final class PapiBridge {
        static String apply(Player player, String text) {
            try {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            } catch (Throwable error) {
                return text;
            }
        }
    }
}
