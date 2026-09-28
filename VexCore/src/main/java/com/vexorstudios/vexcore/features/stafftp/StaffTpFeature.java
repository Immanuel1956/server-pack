package com.vexorstudios.vexcore.features.stafftp;

import com.vexorstudios.vexcore.core.Feature;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Staff teleports without countdowns: /stp &lt;player&gt; [target], /stphere &lt;player&gt;, /stpback. */
public final class StaffTpFeature extends Feature {

    private final Map<UUID, Location> back = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        command("stp", (sender, label, args) -> {
            if (args.length == 0) {
                usage(sender, "stp");
                return;
            }
            Player target = target(sender, args[0]);
            if (target == null) return;
            if (args.length > 1) {
                Player to = target(sender, args[1]);
                if (to == null) return;
                move(target, to.getLocation());
                msg(sender, "sent", "player", target.getName(), "target", to.getName());
                return;
            }
            Player self = player(sender);
            if (self == null) return;
            move(self, target.getLocation());
            msg(self, "teleported", "player", target.getName());
        });
        command("stphere", (sender, label, args) -> {
            Player self = player(sender);
            if (self == null) return;
            if (args.length == 0) {
                usage(self, "stphere");
                return;
            }
            Player target = target(self, args[0]);
            if (target == null) return;
            move(target, self.getLocation());
            msg(self, "brought", "player", target.getName());
        });
        command("stpback", (sender, label, args) -> {
            Player self = player(sender);
            if (self == null) return;
            Location to = back.get(self.getUniqueId());
            if (to == null) {
                msg(self, "no-back");
                return;
            }
            move(self, to);
            msg(self, "back");
        });
    }

    private void move(Player who, Location to) {
        back.put(who.getUniqueId(), who.getLocation());
        who.teleportAsync(to);
    }
}
