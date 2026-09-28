package com.vexorstudios.vexcore.features.msg;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.chatfilter.ChatFilterFeature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private messages: /msg, /reply, /msgtoggle, /ignore, /socialspy. Messages go through the chat
 * filter and are never parsed for tags. Vanished players can't be found by those who can't see
 * them. Staff (vexcore.msg.bypass) reach players who turned messages off or ignore them.
 */
public final class MsgFeature extends Feature implements PlayerData.Store, org.bukkit.event.Listener {

    private final Map<UUID, UUID> replies = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignored = new ConcurrentHashMap<>();
    private final Set<UUID> spies = ConcurrentHashMap.newKeySet();
    private static final UUID CONSOLE = new UUID(0, 0);

    @Override
    protected void enable() {
        db().schema("ignores", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, ignored VARCHAR(36) NOT NULL, PRIMARY KEY (uuid, ignored))");
        store(this);
        listen(this);
        toggle("msg", true, p -> flip(p, "msg", "toggle-on", "toggle-off"));
        command("msg", (s, l, a) -> {
            if (a.length < 2) usage(s, "msg");
            else {
                Player target = target(s, a[0]);
                if (target != null) send(s, target, String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
            }
        });
        command("reply", (s, l, a) -> {
            if (a.length < 1) {
                usage(s, "reply");
                return;
            }
            UUID last = replies.get(id(s));
            Player target = last == null ? null : Bukkit.getPlayer(last);
            // No visibility check: answering someone who just wrote to you (vanished staff too) is fine.
            if (target == null) {
                msg(s, "no-reply");
                return;
            }
            send(s, target, String.join(" ", a));
        });
        command("msgtoggle", (s, l, a) -> {
            Player p = player(s);
            if (p != null) flip(p, "msg", "toggle-on", "toggle-off");
        });
        command("ignore", this::ignore, (s, a) -> a.length == 1 ? null : List.of());
        command("socialspy", (s, l, a) -> {
            Player p = player(s);
            if (p == null) return;
            boolean on = spies.add(p.getUniqueId());
            if (!on) spies.remove(p.getUniqueId());
            msg(p, on ? "spy-on" : "spy-off");
        });
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPublicChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        UUID sender = event.getPlayer().getUniqueId();
        event.viewers().removeIf(a -> a instanceof Player viewer && ignores(viewer.getUniqueId(), sender));
    }

    private static UUID id(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId() : CONSOLE;
    }

    /** True if {@code viewer} ignores {@code sender}. */
    public boolean ignores(UUID viewer, UUID sender) {
        Set<UUID> set = ignored.get(viewer);
        return set != null && set.contains(sender);
    }

    private void send(CommandSender from, Player to, String text) {
        Player fromPlayer = from instanceof Player p ? p : null;
        if (fromPlayer != null && fromPlayer.equals(to)) {
            msg(from, "self");
            return;
        }
        boolean staff = from.hasPermission("vexcore.msg.bypass");
        if (!staff && !plugin.toggles().isOn(to.getUniqueId(), "msg")) {
            msg(from, "disabled", "player", to.getName());
            return;
        }
        if (!staff && fromPlayer != null && ignores(to.getUniqueId(), fromPlayer.getUniqueId())) {
            msg(from, "ignored-by", "player", to.getName());
            return;
        }
        if (fromPlayer != null && ignores(fromPlayer.getUniqueId(), to.getUniqueId())) {
            msg(from, "you-ignore", "player", to.getName());
            return;
        }
        ChatFilterFeature filter = ChatFilterFeature.of(plugin);
        if (filter != null && fromPlayer != null && filter.scans("msg")) {
            ChatFilterFeature.Verdict v = filter.check(fromPlayer, text, "msg");
            if (v.blocked()) return;
            text = v.text();
        }
        Component message = from.hasPermission("vexcore.msg.color")
                ? Text.parse(MiniMessage.miniMessage().escapeTags(text)) : Component.text(text);
        if (fromPlayer != null && plugin.features().get("chat") instanceof com.vexorstudios.vexcore.features.chat.ChatFeature chat
                && chat.config().getBoolean("private-messages", true)) {
            message = chat.render(fromPlayer, message, text);
        }
        Map<String, Object> ph = new HashMap<>();
        ph.put("sender", shown(from));
        ph.put("receiver", shown(to));
        ph.put("message", message);
        msg(from, "sent", ph);
        msg(to, "received", ph);
        replies.put(to.getUniqueId(), id(from));
        replies.put(id(from), to.getUniqueId());
        List<CommandSender> spying = new ArrayList<>();
        for (UUID spy : spies) {
            Player p = Bukkit.getPlayer(spy);
            if (p != null && !p.equals(from) && !p.equals(to) && p.hasPermission("vexcore.socialspy")) spying.add(p);
        }
        if (config().getBoolean("spy-console", true)) spying.add(Bukkit.getConsoleSender());
        broadcast(spying, "spy", ph);
    }

    private void ignore(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null || !ready(player)) return;
        Set<UUID> own = ignored.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            List<String> names = new ArrayList<>();
            for (UUID id : own) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                names.add(op.getName() == null ? id.toString().substring(0, 8) : op.getName());
            }
            msg(player, names.isEmpty() ? "ignore-none" : "ignore-list", "players", String.join(", ", names));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (target == null) {
            msg(player, "unknown-player", "player", args[0]);
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            msg(player, "self");
            return;
        }
        boolean now = own.add(target.getUniqueId());
        if (!now) own.remove(target.getUniqueId());
        Database db = db();
        String a = player.getUniqueId().toString(), b = target.getUniqueId().toString();
        db.queue("ignore", c -> {
            String sql = now ? db.insertIgnore("ignores", "uuid", "ignored") : "DELETE FROM " + db.table("ignores") + " WHERE uuid = ? AND ignored = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, a);
                ps.setString(2, b);
                ps.executeUpdate();
            }
        });
        msg(player, now ? "ignore-on" : "ignore-off", "player", target.getName());
    }

    @Override
    public void load(UUID player, Connection c) throws SQLException {
        Set<UUID> own = ConcurrentHashMap.newKeySet();
        try (PreparedStatement ps = c.prepareStatement("SELECT ignored FROM " + db().table("ignores") + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        own.add(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException ignoredBadRow) {
                    }
                }
            }
        }
        ignored.put(player, own);
    }

    @Override
    public void unload(UUID player) {
        ignored.remove(player);
        replies.remove(player);
        spies.remove(player);
    }

    /** A /hide player's shared name instead of their real one. */
    private String shown(CommandSender who) {
        return who instanceof Player p && plugin.features().get("hide") instanceof com.vexorstudios.vexcore.features.hide.HideFeature h
                ? h.shownName(p) : who.getName();
    }
}
