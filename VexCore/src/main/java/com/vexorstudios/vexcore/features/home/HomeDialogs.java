package com.vexorstudios.vexcore.features.home;

import com.vexorstudios.vexcore.core.Pos;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.chatfilter.ChatFilterFeature;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The home dialogs (Paper 1.21.7+ dialog screens): right-click a home in the menu for its own
 * screen (teleport, change icon, rename, delete). "Change icon" lists every item in the game with
 * its picture and a search bar. All text is in features/home/config.yml under dialog.
 */
final class HomeDialogs {

    private final HomeFeature feature;

    HomeDialogs(HomeFeature feature) {
        this.feature = feature;
    }

    /** Dialogs came with 1.21.7; older servers keep the plain menu. */
    static boolean available() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = feature.config().getConfigurationSection("dialog");
        return s != null ? s : feature.config().createSection("dialog");
    }

    private Component text(String path, String def, Player p, Map<String, ?> ph) {
        return Text.parse(cfg().getString(path, def), p, ph);
    }

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build();

    /** A button action; runs on the player's thread while they are online. */
    private static DialogAction act(Player p, Consumer<DialogResponseView> run) {
        return DialogAction.customClick((view, audience) -> Scheduler.entity(p, () -> {
            if (p.isOnline()) run.accept(view);
        }), ONCE);
    }

    private ActionButton button(String key, String def, Player p, Map<String, ?> ph, int width, DialogAction action) {
        return ActionButton.builder(text("buttons." + key, def, p, ph)).width(width).action(action).build();
    }

    private void click(Player p) {
        com.vexorstudios.vexcore.core.SoundSpec s = com.vexorstudios.vexcore.core.SoundSpec.of(feature.config().get("sounds.dialog-click"));
        if (s != null) s.play(p, com.vexorstudios.vexcore.core.SoundGate.CLICK);
    }

    // ── The home ──────────────────────────────────────────────────────────

    void home(Player p, int n) {
        Pos pos = feature.homesOf(p).get(n);
        if (pos == null) {
            feature.openMenu(p);
            return;
        }
        Map<String, Object> ph = feature.placeholdersOf(p, n);
        int w = cfg().getInt("button-width", 150);
        List<ActionButton> buttons = List.of(
                button("teleport", "Teleport", p, ph, w, act(p, v -> {
                    click(p);
                    feature.teleportTo(p, n);
                })),
                button("change-icon", "Change Icon", p, ph, w, act(p, v -> {
                    click(p);
                    icons(p, n, "", 0);
                })),
                button("rename", "Rename", p, ph, w, act(p, v -> {
                    click(p);
                    rename(p, n);
                })),
                button("delete", "<red>Delete", p, ph, w, act(p, v -> {
                    click(p);
                    delete(p, n);
                })));
        ActionButton back = button("back", "Back", p, ph, w, act(p, v -> {
            click(p);
            feature.openMenu(p);
        }));
        DialogBase base = DialogBase.builder(Text.parse(feature.homeName(p.getUniqueId(), n), p, ph))
                .body(List.of(DialogBody.item(new ItemStack(feature.icon(p.getUniqueId(), n))).showTooltip(false).build()))
                .build();
        p.showDialog(Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons).columns(2).exitAction(back).build())));
    }

    // ── Rename ────────────────────────────────────────────────────────────

    private void rename(Player p, int n) {
        Map<String, Object> ph = feature.placeholdersOf(p, n);
        int max = Math.max(1, Math.min(64, cfg().getInt("name-max-length", 24)));
        DialogBase base = DialogBase.builder(text("rename-title", "Rename", p, ph))
                .inputs(List.of(DialogInput.text("name", text("rename-label", "New Name", p, ph))
                        .initial(feature.homeName(p.getUniqueId(), n)).maxLength(max).width(300).build()))
                .build();
        ActionButton save = button("save", "Save", p, ph, 200, act(p, v -> {
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
            click(p);
            feature.msg(p, "renamed", feature.placeholdersOf(p, n));
            home(p, n);
        }));
        ActionButton cancel = button("cancel", "Cancel", p, ph, 200, act(p, v -> {
            click(p);
            home(p, n);
        }));
        p.showDialog(Dialog.create(b -> b.empty().base(base).type(DialogType.confirmation(save, cancel))));
    }

    // ── Delete ────────────────────────────────────────────────────────────

    private void delete(Player p, int n) {
        Map<String, Object> ph = feature.placeholdersOf(p, n);
        DialogBase base = DialogBase.builder(text("delete-title", "Delete %name%?", p, ph))
                .body(List.of(DialogBody.plainMessage(text("delete-text", "This home is gone for good.", p, ph))))
                .build();
        ActionButton yes = button("confirm-delete", "<red>Delete", p, ph, 150, act(p, v -> {
            feature.deleteHome(p, n);
            feature.openMenu(p);
        }));
        ActionButton no = button("cancel", "Cancel", p, ph, 150, act(p, v -> {
            click(p);
            home(p, n);
        }));
        p.showDialog(Dialog.create(b -> b.empty().base(base).type(DialogType.confirmation(yes, no))));
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

    /** item id -> its texture (block/..., item/...), worked out from the vanilla item models. */
    private static Map<String, String> sprites() {
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

    static String pretty(Material m) {
        String[] words = m.getKey().getKey().split("_");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    /** The item's picture in front of its name, like the vanilla item icons. */
    private Component label(Material m) {
        Component name = Component.text(pretty(m));
        String sprite = cfg().getBoolean("icons.show-pictures", true) ? sprites().get(m.getKey().getKey()) : null;
        if (sprite == null) return name;
        return Component.object(ObjectContents.sprite(Key.key(Key.MINECRAFT_NAMESPACE, sprite))).append(Component.text(" ")).append(name);
    }

    private void icons(Player p, int n, String query, int page) {
        Map<String, Object> ph = new HashMap<>(feature.placeholdersOf(p, n));
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Material> found = new ArrayList<>();
        for (Material m : items()) {
            if (q.isEmpty() || pretty(m).toLowerCase(Locale.ROOT).contains(q) || m.getKey().getKey().contains(q.replace(' ', '_'))) found.add(m);
        }
        int per = Math.max(8, cfg().getInt("icons.per-page", 120));
        int pages = Math.max(1, (found.size() + per - 1) / per);
        int at = Math.max(0, Math.min(page, pages - 1));
        ph.put("page", at + 1);
        ph.put("pages", pages);
        ph.put("found", found.size());
        int w = cfg().getInt("icons.button-width", 150);
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("search", "Search", p, ph, w, act(p, v -> {
            click(p);
            icons(p, n, v.getText("search"), 0);
        })));
        buttons.add(button("default", "Default", p, ph, w, act(p, v -> {
            feature.setMeta(p, n, null, "");
            click(p);
            feature.msg(p, "icon-changed", feature.placeholdersOf(p, n));
            home(p, n);
        })));
        buttons.add(button("back", "Back", p, ph, w, act(p, v -> {
            click(p);
            home(p, n);
        })));
        if (at > 0) buttons.add(button("previous", "Previous Page", p, ph, w, act(p, v -> {
            click(p);
            icons(p, n, v.getText("search"), at - 1);
        })));
        if (at < pages - 1) buttons.add(button("next", "Next Page", p, ph, w, act(p, v -> {
            click(p);
            icons(p, n, v.getText("search"), at + 1);
        })));
        for (int i = at * per; i < Math.min(found.size(), (at + 1) * per); i++) {
            Material m = found.get(i);
            buttons.add(ActionButton.builder(label(m)).width(w).action(act(p, v -> {
                feature.setMeta(p, n, null, m.getKey().getKey());
                click(p);
                feature.msg(p, "icon-changed", feature.placeholdersOf(p, n));
                home(p, n);
            })).build());
        }
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(new ItemStack(feature.icon(p.getUniqueId(), n))).showTooltip(false).build());
        if (found.isEmpty()) body.add(DialogBody.plainMessage(text("icons.nothing-found", "<red>Nothing found.", p, ph)));
        else if (pages > 1) body.add(DialogBody.plainMessage(text("icons.page", "<gray>Page %page%/%pages% (%found% items)", p, ph)));
        DialogBase base = DialogBase.builder(text("icons-title", "Choose Icon", p, ph))
                .body(body)
                .inputs(List.of(DialogInput.text("search", text("search-label", "Search", p, ph)).initial(query == null ? "" : query.strip())
                        .maxLength(32).width(300).build()))
                .build();
        p.showDialog(Dialog.create(b -> b.empty().base(base)
                .type(DialogType.multiAction(buttons).columns(Math.max(1, cfg().getInt("icons.columns", 4))).build())));
    }
}
