package com.vexorstudios.vexcore.core;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dialog screens (Paper 1.21.7+) whose every text is a file:
 * {@code features/<feature>/gui/dialogs/<name>.yml}. A file has a {@code title}, {@code body}
 * lines, an {@code input} (the text box: label, width, max-length, lines), {@code buttons} (each
 * a text, or {@code text}/{@code hover}/{@code width}), {@code button-width}, {@code columns} and
 * {@code sounds.click}. Missing keys fall back to the jar's copy of the file.
 */
public final class Dialogs {

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build();
    private static final boolean AVAILABLE = find();

    private Dialogs() {
    }

    private static boolean find() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Dialogs came with 1.21.7; older servers use the chat instead. */
    public static boolean available() {
        return AVAILABLE;
    }

    /** A button action: runs once, on the player's thread, while they are online. */
    public static DialogAction act(Player player, Consumer<DialogResponseView> run) {
        return DialogAction.customClick((view, audience) -> Scheduler.entity(player, () -> {
            if (player.isOnline()) run.accept(view);
        }), ONCE);
    }

    /** One dialog file for one player, with its placeholders. */
    public static final class Screen {

        private final ConfigurationSection yml;
        private final Player player;
        private final Map<String, Object> ph;

        public Screen(Feature feature, String name, Player player, Map<String, ?> placeholders) {
            this.yml = feature.dialog(name);
            this.player = player;
            this.ph = new HashMap<>(placeholders == null ? Map.of() : placeholders);
            this.ph.putIfAbsent("player", player.getName());
        }

        public ConfigurationSection yml() {
            return yml;
        }

        public Player player() {
            return player;
        }

        public Map<String, Object> placeholders() {
            return ph;
        }

        public Screen with(String key, Object value) {
            ph.put(key, value);
            return this;
        }

        public Component text(String path, String def) {
            return Text.parse(yml.getString(path, def), player, ph);
        }

        /** {@code body} (or another list): one text line each. */
        public List<DialogBody> lines(String path) {
            List<DialogBody> out = new ArrayList<>();
            int width = Math.max(10, Math.min(1024, yml.getInt("body-width", 250)));
            for (String line : yml.getStringList(path)) out.add(DialogBody.plainMessage(Text.parse(line, player, ph), width));
            return out;
        }

        public int width(String path, int def) {
            return Math.max(10, Math.min(1024, yml.getInt(path, def)));
        }

        /** {@code buttons.<key>}: a text, or a section with text, hover and width. */
        public ActionButton button(String key, String def, DialogAction action) {
            String path = "buttons." + key;
            int width = width("button-width", 150);
            String label = def;
            String hover = null;
            if (yml.isConfigurationSection(path)) {
                label = yml.getString(path + ".text", def);
                hover = yml.getString(path + ".hover", null);
                width = width(path + ".width", width);
            } else if (yml.isString(path)) {
                label = yml.getString(path);
            }
            ActionButton.Builder b = ActionButton.builder(Text.parse(label, player, ph)).width(width).action(action);
            if (hover != null && !hover.isBlank()) b.tooltip(Text.parse(hover, player, ph));
            return b.build();
        }

        /** A button whose label and hover are given here (list entries such as the icons). */
        public ActionButton button(Component label, Component hover, int width, DialogAction action) {
            ActionButton.Builder b = ActionButton.builder(label).width(Math.max(10, Math.min(1024, width))).action(action);
            if (hover != null) b.tooltip(hover);
            return b.build();
        }

        /** The text box under {@code input}: label, label-visible, width, max-length, lines. */
        public TextDialogInput input(String key, String defLabel, String initial, int defMax) {
            int lines = Math.max(1, Math.min(10, yml.getInt("input.lines", 1)));
            TextDialogInput.Builder in = DialogInput.text(key, text("input.label", defLabel))
                    .labelVisible(yml.getBoolean("input.label-visible", true))
                    .initial(initial != null ? initial : Text.plain(text("input.initial", "")))
                    .maxLength(Math.max(1, Math.min(4096, yml.getInt("input.max-length", defMax))))
                    .width(width("input.width", 300));
            if (lines > 1) in.multiline(TextDialogInput.MultilineOptions.create(lines, Math.max(20, Math.min(512, lines * 18))));
            return in.build();
        }

        public DialogBase base(Component title, List<DialogBody> body, List<? extends DialogInput> inputs) {
            return DialogBase.builder(title)
                    .canCloseWithEscape(yml.getBoolean("can-close-with-escape", true))
                    .body(body)
                    .inputs(inputs)
                    .build();
        }

        /** {@code sounds.click} of the file (one per action, like menu clicks). */
        public void click() {
            SoundSpec s = SoundSpec.of(yml.get("sounds.click"));
            if (s != null) s.play(player, SoundGate.CLICK);
        }

        public void show(DialogBase base, DialogType type) {
            player.showDialog(Dialog.create(b -> b.empty().base(base).type(type)));
        }
    }

    /**
     * "Type something": the dialog's title, body, one text box and confirm/cancel buttons
     * (Escape is cancel). Without dialogs (servers before 1.21.7) the {@code chatPrompt} message
     * is sent and the next chat line is the answer. Both callbacks run on the player's thread;
     * {@code cancelled} may be null.
     */
    public static void ask(Feature feature, Player player, String dialog, String chatPrompt, Map<String, ?> placeholders,
                           String initial, Consumer<String> answer, Runnable cancelled) {
        if (!AVAILABLE) {
            feature.msg(player, chatPrompt, placeholders == null ? Map.of() : placeholders);
            feature.plugin.chatInput().ask(player, Math.max(5, feature.config().getInt("input-seconds", 30)), answer, cancelled);
            return;
        }
        Screen s = new Screen(feature, dialog, player, placeholders);
        ActionButton confirm = s.button("confirm", "Confirm", act(player, view -> {
            s.click();
            String text = view.getText("text");
            answer.accept(text == null ? "" : text.strip());
        }));
        ActionButton cancel = s.button("cancel", "Cancel", act(player, view -> {
            s.click();
            if (cancelled != null) cancelled.run();
        }));
        DialogBase base = s.base(s.text("title", ""), s.lines("body"), List.of(s.input("text", "", initial, 32)));
        s.show(base, DialogType.confirmation(confirm, cancel));
    }
}
