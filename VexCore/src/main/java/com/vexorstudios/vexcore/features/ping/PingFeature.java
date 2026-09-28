package com.vexorstudios.vexcore.features.ping;

import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.entity.Player;

/** /ping [player], coloured by how good it is. */
public final class PingFeature extends Feature {

    @Override
    protected void enable() {
        command("ping", (sender, label, args) -> {
            Player target;
            if (args.length > 0) {
                if (!sender.hasPermission("vexcore.ping.others")) {
                    msg(sender, "no-permission", "permission", "vexcore.ping.others");
                    return;
                }
                target = target(sender, args[0]);
            } else {
                target = player(sender);
            }
            if (target == null) return;
            int ping = target.getPing();
            String colour = config().getString(ping < config().getInt("good-below", 80) ? "colours.good"
                    : ping < config().getInt("okay-below", 180) ? "colours.okay" : "colours.bad", "");
            msg(sender, target == sender ? "self" : "other", "player", target.getName(), "ping", ping, "colour", colour);
        });
        placeholder("ping", (p, arg) -> p.getPlayer() == null ? "0" : String.valueOf(p.getPlayer().getPing()));
    }
}
