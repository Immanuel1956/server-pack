package com.vexorstudios.vexcore.features.tpa;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import com.vexorstudios.vexcore.core.Toggles;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teleport requests: /tpa, /tpahere, /tpahereall, /tpaccept, /tpdeny, /tpacancel,
 * /tpatoggle [tpa|tpahere], /tpauto. Requests run out after {@code expire-seconds}.
 */
public final class TpaFeature extends Feature implements Listener {

    private enum Kind {TPA, HERE}

    private record Request(UUID from, String fromName, UUID to, Kind kind, long expires) {
    }

    /** Open requests by the player who has to answer them, oldest first. */
    private final Map<UUID, Map<UUID, Request>> incoming = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        toggle("tpa", config().getBoolean("defaults.tpa", true), p -> toggleOne(p, "tpa"));
        toggle("tpahere", config().getBoolean("defaults.tpahere", true), p -> toggleOne(p, "tpahere"));
        toggle("tpauto", config().getBoolean("defaults.tpauto", false), p -> flip(p, "tpauto", "auto-on", "auto-off"));

        command("tpa", (s, l, a) -> request(s, a, Kind.TPA, "tpa"));
        command("tpahere", (s, l, a) -> request(s, a, Kind.HERE, "tpahere"));
        command("tpahereall", (s, l, a) -> hereAll(s));
        command("tpaccept", (s, l, a) -> answer(s, a, true), this::requesters);
        command("tpdeny", (s, l, a) -> answer(s, a, false), this::requesters);
        command("tpacancel", this::cancelOwn);
        command("tpatoggle", this::toggleCommand, (s, a) -> a.length == 1 ? List.of("tpa", "tpahere") : List.of());
        command("tpauto", (s, l, a) -> {
            Player p = player(s);
            if (p != null) flip(p, "tpauto", "auto-on", "auto-off");
        });
        every(20, this::expire);
        listen(this);
    }

    @Override
    protected void disable() {
        incoming.clear();
    }

    private Map<UUID, Request> inbox(UUID player) {
        return incoming.computeIfAbsent(player, k -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    private Map<String, Object> ph(String player) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("player", player);
        ph.put("tpaccept", plugin.commands().name("tpaccept"));
        ph.put("tpdeny", plugin.commands().name("tpdeny"));
        return ph;
    }

    // ── Sending ───────────────────────────────────────────────────────────

    private void request(CommandSender sender, String[] args, Kind kind, String commandId) {
        Player from = player(sender);
        if (from == null) return;
        if (args.length == 0) {
            usage(from, commandId);
            return;
        }
        Player to = target(from, args[0]);
        if (to != null) send(from, to, kind, false);
    }

    /** Returns true if the request was made. {@code quiet} skips the per-player "sent" message. */
    private boolean send(Player from, Player to, Kind kind, boolean quiet) {
        if (from.equals(to)) {
            if (!quiet) msg(from, "self");
            return false;
        }
        if (!plugin.toggles().isOn(to.getUniqueId(), kind == Kind.TPA ? "tpa" : "tpahere")) {
            if (!quiet) msg(from, "target-blocked", ph(to.getName()));
            return false;
        }
        if (kind == Kind.TPA && plugin.restrictions().deny(from)) return false;
        Map<UUID, Request> box = inbox(to.getUniqueId());
        if (box.containsKey(from.getUniqueId())) {
            if (!quiet) msg(from, "already-sent", ph(to.getName()));
            return false;
        }
        long expires = System.currentTimeMillis() + Math.max(5, config().getInt("expire-seconds", 60)) * 1000L;
        Request request = new Request(from.getUniqueId(), from.getName(), to.getUniqueId(), kind, expires);
        box.put(from.getUniqueId(), request);
        if (!quiet) msg(from, kind == Kind.TPA ? "sent" : "sent-here", ph(to.getName()));
        msg(to, kind == Kind.TPA ? "received" : "received-here", ph(from.getName()));
        // Not while the one who'd be teleported to is in combat, a 1v1 or a screenshare.
        if (plugin.toggles().isOn(to.getUniqueId(), "tpauto") && plugin.restrictions().check(to) == null) {
            msg(from, "auto-sender", ph(to.getName()));
            msg(to, "auto-target", ph(from.getName()));
            accept(to, request);
        }
        return true;
    }

    private void hereAll(CommandSender sender) {
        Player from = player(sender);
        if (from == null) return;
        int sent = 0;
        for (Player to : Bukkit.getOnlinePlayers()) {
            if (com.vexorstudios.vexcore.core.Visibility.knows(from, to) && send(from, to, Kind.HERE, true)) sent++;
        }
        msg(from, "hereall", "amount", sent);
    }

    // ── Answering ─────────────────────────────────────────────────────────

    private List<String> requesters(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p) || args.length != 1) return List.of();
        List<String> names = new ArrayList<>();
        Map<UUID, Request> box = incoming.get(p.getUniqueId());
        if (box != null) synchronized (box) {
            for (Request r : box.values()) names.add(r.fromName);
        }
        return names;
    }

    private void answer(CommandSender sender, String[] args, boolean accept) {
        Player me = player(sender);
        if (me == null) return;
        Map<UUID, Request> box = incoming.get(me.getUniqueId());
        Request request = null;
        if (box != null) synchronized (box) {
            for (Request r : box.values()) {
                if (args.length == 0 || r.fromName.equalsIgnoreCase(args[0])) request = r; // newest wins
            }
            if (request != null) box.remove(request.from);
        }
        if (request == null) {
            msg(me, "no-request");
            return;
        }
        Player from = Bukkit.getPlayer(request.from);
        if (from == null) {
            msg(me, "player-offline", ph(request.fromName));
            return;
        }
        if (!accept) {
            msg(me, "denied-target", ph(from.getName()));
            msg(from, "denied-sender", ph(me.getName()));
            return;
        }
        msg(from, "accepted-sender", ph(me.getName()));
        msg(me, "accepted-target", ph(from.getName()));
        accept(me, request);
    }

    private void accept(Player target, Request request) {
        Player from = Bukkit.getPlayer(request.from);
        if (from == null) return;
        Map<UUID, Request> box = incoming.get(target.getUniqueId());
        if (box != null) box.remove(request.from);
        Player mover = request.kind == Kind.TPA ? from : target;
        Player anchor = request.kind == Kind.TPA ? target : from;
        if (plugin.restrictions().check(mover) != null) {
            plugin.restrictions().deny(mover);
            msg(anchor, "target-restricted", ph(mover.getName()));
            return;
        }
        // Read when the countdown ends: no following someone who got into a duel/combat meanwhile.
        plugin.teleports().start(this, mover, () -> anchor.isOnline() && plugin.restrictions().check(anchor) == null ? anchor.getLocation() : null,
                "vexcore.tpa.bypass", ph(anchor.getName()), null);
    }

    private void cancelOwn(CommandSender sender, String label, String[] args) {
        Player me = player(sender);
        if (me == null) return;
        int removed = 0;
        for (Map.Entry<UUID, Map<UUID, Request>> e : incoming.entrySet()) {
            Request r = e.getValue().get(me.getUniqueId());
            if (r == null) continue;
            Player to = Bukkit.getPlayer(r.to);
            if (args.length > 0 && (to == null || !to.getName().equalsIgnoreCase(args[0]))) continue;
            e.getValue().remove(me.getUniqueId());
            removed++;
            if (to != null) msg(to, "cancelled-target", ph(me.getName()));
        }
        msg(me, removed > 0 ? "cancelled" : "no-request");
    }

    // ── Toggles ───────────────────────────────────────────────────────────

    private void toggleCommand(CommandSender sender, String label, String[] args) {
        Player p = player(sender);
        if (p == null) return;
        if (args.length > 0) {
            String which = args[0].toLowerCase(Locale.ROOT);
            if (which.equals("tpa") || which.equals("tpahere")) {
                toggleOne(p, which);
                return;
            }
        }
        boolean anyOn = plugin.toggles().isOn(p.getUniqueId(), "tpa") || plugin.toggles().isOn(p.getUniqueId(), "tpahere");
        if (!plugin.toggles().set(p, "tpa", !anyOn) || !plugin.toggles().set(p, "tpahere", !anyOn)) {
            msg(p, "data-loading");
            return;
        }
        msg(p, anyOn ? "toggle-off" : "toggle-on");
    }

    private void toggleOne(Player p, String id) {
        flip(p, id, id + "-toggle-on", id + "-toggle-off");
    }

    // ── Housekeeping ──────────────────────────────────────────────────────

    private void expire() {
        long now = System.currentTimeMillis();
        for (Map<UUID, Request> box : incoming.values()) {
            List<Request> gone = new ArrayList<>();
            synchronized (box) {
                box.values().removeIf(r -> {
                    if (r.expires > now) return false;
                    gone.add(r);
                    return true;
                });
            }
            for (Request r : gone) {
                Player from = Bukkit.getPlayer(r.from);
                Player to = Bukkit.getPlayer(r.to);
                if (from != null && to != null) {
                    msg(from, "expired-sender", ph(to.getName()));
                    msg(to, "expired-target", ph(from.getName()));
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        incoming.remove(id);
        for (Map<UUID, Request> box : incoming.values()) box.remove(id);
    }

    // ── SetupCore import ──────────────────────────────────────────────────

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        Connection old = source.main();
        if (!SetupCoreImport.Source.has(old, "tpa_toggles")) return;
        int count = 0;
        try (Statement st = old.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, tpauto, tpa, tpahere FROM tpa_toggles")) {
            while (rs.next()) {
                String uuid = rs.getString(1).toLowerCase(Locale.ROOT);
                count++;
                if (rs.getInt(2) == 1) Toggles.write(db(), target, uuid, "tpauto", true);
                if (rs.getInt(3) == 0) Toggles.write(db(), target, uuid, "tpa", false);
                if (rs.getInt(4) == 0) Toggles.write(db(), target, uuid, "tpahere", false);
            }
        }
        report.add(count, "players' request settings");
    }
}
