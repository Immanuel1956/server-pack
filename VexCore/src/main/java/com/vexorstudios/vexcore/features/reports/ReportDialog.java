package com.vexorstudios.vexcore.features.reports;

import com.vexorstudios.vexcore.core.Dialogs;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The /report dialog (Paper 1.21.7+): the offender's head, a text box for the reason, Submit and
 * Cancel. Plays sounds.dialog-open when it opens and sounds.dialog-close when it is left (Submit,
 * Cancel or Escape). Texts are in features/reports/gui/dialogs/report.yml.
 */
final class ReportDialog {

    private final ReportsFeature feature;

    ReportDialog(ReportsFeature feature) {
        this.feature = feature;
    }

    static boolean available() {
        return Dialogs.available();
    }

    void open(Player p, UUID targetId, String targetName) {
        open(p, targetId, targetName, "");
    }

    /** {@code draft} is put back in the box when the reason was refused. */
    private void open(Player p, UUID targetId, String targetName, String draft) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("target", targetName);
        ph.put("min", feature.minLength());
        ph.put("max", feature.maxLength());
        Dialogs.Screen s = new Dialogs.Screen(feature, "report", p, ph);

        List<DialogBody> body = new ArrayList<>();
        if (s.yml().getBoolean("show-head", true)) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                Player online = org.bukkit.Bukkit.getPlayer(targetId);
                // Offline: the id is enough, the game fetches the skin itself.
                if (online != null) meta.setPlayerProfile(online.getPlayerProfile());
                else meta.setPlayerProfile(org.bukkit.Bukkit.createProfile(targetId, targetName));
                head.setItemMeta(meta);
            }
            body.add(DialogBody.item(head).showTooltip(false).build());
        }
        body.addAll(s.lines("body"));

        ActionButton submit = s.button("submit", "<#FF3B3B>Submit Report", Dialogs.act(p, view -> {
            String reason = view.getText("reason");
            feature.play(p, "dialog-close");
            if (!feature.submit(p, targetId, targetName, reason)) {
                open(p, targetId, targetName, reason == null ? "" : reason);
            }
        }));
        // Escape does the same as the "no" button of a confirmation dialog.
        ActionButton cancel = s.button("cancel", "Cancel", Dialogs.act(p, view -> {
            feature.play(p, "dialog-close");
            feature.msg(p, "cancelled");
        }));
        s.show(s.base(s.text("title", "Report %target%"), body,
                List.of(s.input("reason", "Reason", draft == null ? "" : draft, feature.maxLength()))), DialogType.confirmation(submit, cancel));
        feature.play(p, "dialog-open");
    }
}
