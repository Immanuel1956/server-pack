package com.vexorstudios.vexcore.features.ranktrial;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Time;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import java.util.List;
import java.util.Locale;

/** Delegates expiry to LuckPerms; VexCore never owns a rank-expiry timer. */
public final class RankTrialFeature extends Feature {
    @Override protected void enable() {
        command("ranktrial", (sender, label, args) -> {
            if (args.length != 3) { usage(sender, "ranktrial"); return; }
            var lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (lp == null || !lp.isEnabled()) { msg(sender, "missing"); return; }
            String rank = args[1].toLowerCase(Locale.ROOT);
            if (!rank.matches("[a-z0-9_+.-]{1,64}") || config().getStringList("allowed-ranks").stream().noneMatch(rank::equalsIgnoreCase)) {
                msg(sender, "rank-not-allowed", "ranks", String.join(", ", config().getStringList("allowed-ranks"))); return;
            }
            long seconds = Time.seconds(args[2]);
            long max = Time.seconds(config().getString("max-duration", "30d"));
            if (seconds <= 0 || seconds > (max > 0 ? max : 2592000)) { msg(sender, "invalid-duration"); return; }
            OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
            if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null) { msg(sender, "unknown-player", "player", args[0]); return; }
            String uuid = target.getUniqueId().toString();
            String name = target.getName() == null ? args[0] : target.getName();
            String modifier = config().getString("temporary-modifier", "deny").toLowerCase(Locale.ROOT);
            if (!List.of("deny", "replace", "accumulate").contains(modifier)) modifier = "deny";
            String run = "luckperms:luckperms user " + uuid + " parent addtemp " + rank + " " + seconds + "s " + modifier;
            Scheduler.global(() -> {
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run);
                msg(sender, dispatched ? "submitted" : "failed", "player", name, "rank", rank, "duration", plugin.messages().time(seconds));
            });
        }, (sender, args) -> switch (args.length) {
            case 1 -> playerNames(sender);
            case 2 -> config().getStringList("allowed-ranks");
            case 3 -> List.of("1h", "1d", "7d");
            default -> List.of();
        });
    }
}
