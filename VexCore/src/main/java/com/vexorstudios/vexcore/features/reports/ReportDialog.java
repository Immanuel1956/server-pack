package com.vexorstudios.vexcore.features.reports;

import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
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
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The /report dialog (Paper 1.21.7+): the offender's head, a text box for the reason, Submit and
 * Cancel. Plays sounds.dialog-open when it opens and sounds.dialog-close when it is left (Submit,
 * Cancel or Escape). Texts are in features/reports/config.yml under dialog.
 */
final class ReportDialog {

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build();

    private final ReportsFeature feature;

    ReportDialog(ReportsFeature feature) {
        this.feature = feature;
    }

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

    private static DialogAction act(Player p, Consumer<DialogResponseView> run) {
        return DialogAction.customClick((view, audience) -> Scheduler.entity(p, () -> {
            if (p.isOnline()) run.accept(view);
        }), ONCE);
    }

    void open(Player p, Player target) {
        open(p, target.getUniqueId(), target.getName(), "");
    }

    /** {@code draft} is put back in the box when the reason was refused. */
    private void open(Player p, UUID targetId, String targetName, String draft) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("target", targetName);
        ph.put("player", p.getName());
        ph.put("min", feature.minLength());
        ph.put("max", feature.maxLength());

        List<DialogBody> body = new ArrayList<>();
        if (cfg().getBoolean("show-head", true)) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                Player online = org.bukkit.Bukkit.getPlayer(targetId);
                if (online != null) meta.setPlayerProfile(online.getPlayerProfile());
                head.setItemMeta(meta);
            }
            body.add(DialogBody.item(head).showTooltip(false).build());
        }
        for (String line : cfg().getStringList("body")) body.add(DialogBody.plainMessage(Text.parse(line, p, ph)));

        int lines = Math.max(1, Math.min(10, cfg().getInt("input.lines", 4)));
        TextDialogInput.Builder input = DialogInput.text("reason", text("input.label", "Reason", p, ph))
                .initial(draft == null ? "" : draft)
                .maxLength(feature.maxLength())
                .width(Math.max(50, Math.min(1024, cfg().getInt("input.width", 300))));
        if (lines > 1) input.multiline(TextDialogInput.MultilineOptions.create(lines, Math.max(20, Math.min(512, lines * 18))));

        DialogBase base = DialogBase.builder(text("title", "Report %target%", p, ph))
                .body(body)
                .inputs(List.of(input.build()))
                .build();

        int w = Math.max(40, Math.min(1024, cfg().getInt("button-width", 150)));
        ActionButton submit = ActionButton.builder(text("buttons.submit", "<#FF3B3B>Submit Report", p, ph)).width(w)
                .action(act(p, view -> {
                    String reason = view.getText("reason");
                    feature.play(p, "dialog-close");
                    if (!feature.submit(p, targetId, targetName, reason)) {
                        open(p, targetId, targetName, reason == null ? "" : reason);
                    }
                })).build();
        // Escape does the same as the "no" button of a confirmation dialog.
        ActionButton cancel = ActionButton.builder(text("buttons.cancel", "Cancel", p, ph)).width(w)
                .action(act(p, view -> {
                    feature.play(p, "dialog-close");
                    feature.msg(p, "cancelled");
                })).build();

        p.showDialog(Dialog.create(b -> b.empty().base(base).type(DialogType.confirmation(submit, cancel))));
        feature.play(p, "dialog-open");
    }
}
