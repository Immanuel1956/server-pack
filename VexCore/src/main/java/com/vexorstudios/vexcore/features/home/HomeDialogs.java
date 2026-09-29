package com.vexorstudios.vexcore.features.home;

import com.vexorstudios.vexcore.core.Dialogs;
import com.vexorstudios.vexcore.core.Pos;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.chatfilter.ChatFilterFeature;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The home dialogs (Paper 1.21.7+ dialog screens): right-click a home in the menu for its own
 * screen (teleport, change icon, rename, delete). "Change icon" lists every item in the game with
 * its picture and a search bar. Every text is in features/home/gui/dialogs/: home.yml,
 * rename.yml, delete.yml and icons.yml.
 */
final class HomeDialogs {

    private static final Key BLOCKS = Key.key(Key.MINECRAFT_NAMESPACE, "blocks");
    private static final Key ITEMS = Key.key(Key.MINECRAFT_NAMESPACE, "items");

    private final HomeFeature feature;

    HomeDialogs(HomeFeature feature) {
        this.feature = feature;
    }

    private Dialogs.Screen screen(String name, Player p, int n) {
        return new Dialogs.Screen(feature, name, p, feature.placeholdersOf(p, n));
    }

    private DialogBody icon(Player p, int n) {
        return DialogBody.item(new ItemStack(feature.icon(p.getUniqueId(), n))).showTooltip(false).build();
    }

    // ── The home ──────────────────────────────────────────────────────────

    void home(Player p, int n) {
        Pos pos = feature.homesOf(p).get(n);
        if (pos == null) {
            feature.openMenu(p);
            return;
        }
        Dialogs.Screen s = screen("home", p, n);
        List<ActionButton> buttons = List.of(
                s.button("teleport", "Teleport", Dialogs.act(p, v -> {
                    s.click();
                    feature.teleportTo(p, n);
                })),
                s.button("change-icon", "Change Icon", Dialogs.act(p, v -> {
                    s.click();
                    icons(p, n, "", 0);
                })),
                s.button("rename", "Rename", Dialogs.act(p, v -> {
                    s.click();
                    rename(p, n);
                })),
                s.button("delete", "<red>Delete", Dialogs.act(p, v -> {
                    s.click();
                    delete(p, n);
                })));
        ActionButton back = s.button("back", "Back", Dialogs.act(p, v -> {
            s.click();
            feature.openMenu(p);
        }));
        List<DialogBody> body = new ArrayList<>();
        if (s.yml().getBoolean("show-icon", true)) body.add(icon(p, n));
        body.addAll(s.lines("body"));
        s.show(s.base(s.text("title", "%name%"), body, List.of()),
                DialogType.multiAction(buttons).columns(Math.max(1, s.yml().getInt("columns", 2))).exitAction(back).build());
    }

    // ── Rename ────────────────────────────────────────────────────────────

    private void rename(Player p, int n) {
        Dialogs.Screen s = screen("rename", p, n);
        int max = Math.max(1, Math.min(64, s.yml().getInt("input.max-length", 24)));
        ActionButton save = s.button("save", "Save", Dialogs.act(p, v -> {
            String name = v.getText("name");
            // Plain text only: no colour codes or tags in a home name.
            name = name == null ? "" : name.replace("&", "").replace("<", "").replace(">", "").replace("§", "").strip();
            if (name.isEmpty()) {
                feature.msg(p, "name-empty");
                rename(p, n);
                return;
            }
            if (name.length() > max) name = name.substring(0, max);
            ChatFilterFeature filter = ChatFilterFeature.of(com.vexorstudios.vexcore.VexCore.get());
            if (filter != null && !filter.cleanName(name.replace(" ", "_"))) {
                feature.msg(p, "name-not-allowed");
                rename(p, n);
                return;
            }
            feature.setMeta(p, n, name, null);
            s.click();
            feature.msg(p, "renamed", feature.placeholdersOf(p, n));
            home(p, n);
        }));
        ActionButton cancel = s.button("cancel", "Cancel", Dialogs.act(p, v -> {
            s.click();
            home(p, n);
        }));
        s.show(s.base(s.text("title", "Rename"), s.lines("body"),
                List.of(s.input("name", "New Name", feature.homeName(p.getUniqueId(), n), max))), DialogType.confirmation(save, cancel));
    }

    // ── Delete ────────────────────────────────────────────────────────────

    private void delete(Player p, int n) {
        Dialogs.Screen s = screen("delete", p, n);
        ActionButton yes = s.button("confirm", "<red>Delete", Dialogs.act(p, v -> {
            feature.deleteHome(p, n);
            feature.openMenu(p);
        }));
        ActionButton no = s.button("cancel", "Cancel", Dialogs.act(p, v -> {
            s.click();
            home(p, n);
        }));
        s.show(s.base(s.text("title", "Delete %name%?"), s.lines("body"), List.of()), DialogType.confirmation(yes, no));
    }

    // ── Choose an icon ────────────────────────────────────────────────────

    /** Every item a player can hold, in name order, with its picture (built once). */
    private static volatile List<Material> items;
    private static volatile Map<String, String> sprites;

    private static List<Material> items() {
        List<Material> list = items;
        if (list == null) {
            list = new ArrayList<>();
            for (Material m : Material.values()) if (!m.isLegacy() && m.isItem() && !m.isAir()) list.add(m);
            list.sort(java.util.Comparator.comparing(HomeDialogs::pretty));
            items = list = List.copyOf(list);
        }
        return list;
    }

    /** item id -> its texture (block/..., item/...), worked out from the vanilla 1.21.10 item models. */
    static Map<String, String> sprites() {
        Map<String, String> map = sprites;
        if (map == null) {
            map = new HashMap<>();
            try (InputStream in = HomeDialogs.class.getClassLoader().getResourceAsStream("features/home/icons.txt")) {
                if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        int space = line.indexOf(' ');
                        if (space > 0) map.put(line.substring(0, space), line.substring(space + 1).strip());
                    }
                }
            } catch (java.io.IOException ignored) {
            }
            sprites = map;
        }
        return map;
    }

    /**
     * The atlas a texture is stitched into. Since 1.21.9 item textures (item/...) have their own
     * atlas; asked for in the blocks atlas they show as the purple and black "missing" square.
     */
    static Key atlas(String sprite) {
        return sprite.startsWith("item/") ? ITEMS : BLOCKS;
    }

    static String pretty(Material m) {
        return Text.itemName(m);
    }

    /** The item's picture ({@code %icon%}), or nothing. */
    private static Component picture(Material m, boolean show) {
        String sprite = show ? sprites().get(m.getKey().getKey()) : null;
        if (sprite == null) return Component.empty();
        return Component.object(ObjectContents.sprite(atlas(sprite), Key.key(Key.MINECRAFT_NAMESPACE, sprite)));
    }

    private void icons(Player p, int n, String query, int page) {
        Dialogs.Screen s = screen("icons", p, n);
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Material> found = new ArrayList<>();
        for (Material m : items()) {
            if (q.isEmpty() || pretty(m).toLowerCase(Locale.ROOT).contains(q) || m.getKey().getKey().contains(q.replace(' ', '_'))) found.add(m);
        }
        int columns = Math.max(1, Math.min(6, s.yml().getInt("columns", 3)));
        int per = Math.max(columns, s.yml().getInt("per-page", 90) / columns * columns); // whole rows
        int pages = Math.max(1, (found.size() + per - 1) / per);
        int at = Math.max(0, Math.min(page, pages - 1));
        s.with("page", at + 1).with("pages", pages).with("found", com.vexorstudios.vexcore.core.Numbers.format(found.size()));

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(s.button("search", "Search", Dialogs.act(p, v -> {
            s.click();
            icons(p, n, v.getText("search"), 0);
        })));
        buttons.add(s.button("default", "Default", Dialogs.act(p, v -> {
            feature.setMeta(p, n, null, "");
            s.click();
            feature.msg(p, "icon-changed", feature.placeholdersOf(p, n));
            home(p, n);
        })));
        buttons.add(s.button("back", "Back", Dialogs.act(p, v -> {
            s.click();
            home(p, n);
        })));
        boolean pictures = s.yml().getBoolean("show-pictures", true);
        String label = s.yml().getString("item.text", "%icon% %name%");
        String hover = s.yml().getString("item.hover", "<gray>Use <white>%name%</white> as the icon");
        int width = s.width("button-width", 150);
        for (int i = at * per; i < Math.min(found.size(), (at + 1) * per); i++) {
            Material m = found.get(i);
            Map<String, Object> ph = new HashMap<>(s.placeholders());
            ph.put("name", pretty(m));
            ph.put("id", m.getKey().getKey());
            ph.put("icon", picture(m, pictures));
            buttons.add(s.button(Text.parse(label, p, ph), hover.isBlank() ? null : Text.parse(hover, p, ph), width, Dialogs.act(p, v -> {
                feature.setMeta(p, n, null, m.getKey().getKey());
                s.click();
                feature.msg(p, "icon-changed", feature.placeholdersOf(p, n));
                home(p, n);
            })));
        }
        // Page buttons after the items: every page is whole rows, so they start a row of their own.
        if (at > 0) buttons.add(s.button("previous", "◀ Previous Page", Dialogs.act(p, v -> {
            s.click();
            icons(p, n, v.getText("search"), at - 1);
        })));
        if (at < pages - 1) buttons.add(s.button("next", "Next Page ▶", Dialogs.act(p, v -> {
            s.click();
            icons(p, n, v.getText("search"), at + 1);
        })));

        List<DialogBody> body = new ArrayList<>();
        if (s.yml().getBoolean("show-icon", true)) body.add(icon(p, n));
        if (found.isEmpty()) body.addAll(s.lines("nothing-found"));
        else if (pages > 1) body.addAll(s.lines("page"));
        s.show(s.base(s.text("title", "Choose Icon"), body, List.of(s.input("search", "Search", query == null ? "" : query.strip(), 32))),
                DialogType.multiAction(buttons).columns(columns).build());
    }
}
