package com.vexorstudios.vexcore.features.joinmessages;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import com.vexorstudios.vexcore.core.Toggles;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom join, first-join and leave messages, shown only to players who did not turn them off
 * with /jointoggle. {@code vexcore.joinmessages.silent} joins and leaves without a message.
 */
public final class JoinMessagesFeature extends Feature implements Listener {

    private static final String TOGGLE = "joinmessages";

    @Override
    protected void enable() {
        toggle(TOGGLE, config().getBoolean("default", true), p -> flip(p, TOGGLE, "shown", "hidden"));
        command("jointoggle", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) flip(player, TOGGLE, "shown", "hidden");
        });
        listen(this);
    }

    private void announce(Player about, String section, boolean joining) {
        if (!config().getBoolean(section + ".enabled", true) || about.hasPermission("vexcore.joinmessages.silent")) return;
        Object raw = config().get(section + ".message");
        // Counted like the scoreboard: vanished staff don't count.
        int online = (plugin.features().get("vanish") instanceof com.vexorstudios.vexcore.features.vanish.VanishFeature v
                ? v.visibleCount() : Bukkit.getOnlinePlayers().size()) - (joining ? 0 : 1);
        String shown = com.vexorstudios.vexcore.core.Visibility.name(about);
        // A /hide player's display name would give their real name away.
        Map<String, Object> ph = Map.of("player", shown, "displayname", shown.equals(about.getName()) ? about.displayName() : shown, "online", online);
        List<CommandSender> to = new ArrayList<>();
        to.add(Bukkit.getConsoleSender());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(about) || joining) {
                if (plugin.toggles().isOn(p.getUniqueId(), TOGGLE)) to.add(p);
            }
        }
        plugin.messages().broadcast(to, raw, ph, plugin.messages().prefix(this));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        Player player = event.getPlayer();
        if (vanished(player)) return;
        boolean first = !player.hasPlayedBefore() && config().getBoolean("first-join.enabled", true);
        announce(player, first ? "first-join" : "join", true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        if (vanished(event.getPlayer())) return;
        announce(event.getPlayer(), "leave", false);
    }

    /** Vanished staff come and go silently. */
    private boolean vanished(Player player) {
        return plugin.features().get("vanish") instanceof com.vexorstudios.vexcore.features.vanish.VanishFeature v
                && v.isVanished(player.getUniqueId());
    }

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        if (!SetupCoreImport.Source.has(source.main(), "toggle_flags")) return;
        int count = 0;
        try (Statement st = source.main().createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid FROM toggle_flags WHERE flag = 'join-messages'")) {
            while (rs.next()) {
                Toggles.write(db(), target, rs.getString(1).toLowerCase(Locale.ROOT), TOGGLE, false);
                count++;
            }
        }
        report.add(count, "players with join messages hidden");
    }
}
