package com.vexorstudios.vexcore.features.playerhide;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Visibility;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import com.vexorstudios.vexcore.core.Toggles;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * /playerhide: the other players disappear from your own view (never the other way round).
 * Only in the configured worlds; an empty list means everywhere. Uses per-plugin hiding, so it
 * never undoes a vanish plugin.
 */
public final class PlayerHideFeature extends Feature implements Listener {

    private static final String TOGGLE = "playerhide";

    @Override
    protected void enable() {
        toggle(TOGGLE, config().getBoolean("default", false), this::toggle);
        command("playerhide", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) toggle(player);
        });
        listen(this);
    }

    @Override
    protected void disable() {
        // Turned off: show everyone again, except players vanish still hides.
        for (Player viewer : Bukkit.getOnlinePlayers()) onPlayerThread(viewer, () -> {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(viewer) && !Visibility.hidden(viewer, other)) viewer.showPlayer(plugin, other);
            }
        });
    }

    private boolean allowedWorld(Player player) {
        var worlds = config().getStringList("worlds");
        if (worlds.isEmpty()) return true;
        for (String w : worlds) if (w.equalsIgnoreCase(player.getWorld().getName())) return true;
        return false;
    }

    /** Whether this viewer has /playerhide on (and may use it in their world). */
    public boolean hiding(Player viewer) {
        return plugin.toggles().isOn(viewer.getUniqueId(), TOGGLE) && allowedWorld(viewer);
    }

    private void toggle(Player player) {
        if (!plugin.toggles().isOn(player.getUniqueId(), TOGGLE) && !allowedWorld(player)) {
            msg(player, "wrong-world");
            return;
        }
        if (flip(player, TOGGLE, "enabled", "disabled") != null) apply(player);
    }

    private void apply(Player viewer) {
        for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(viewer)) Visibility.update(viewer, other);
    }

    @Override
    protected void loaded(Player player) {
        if (hiding(player)) apply(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        if (joined.hasPermission("vexcore.playerhide.exempt")) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(joined) && hiding(viewer)) Scheduler.entity(viewer, () -> viewer.hidePlayer(plugin, joined));
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!plugin.toggles().isOn(player.getUniqueId(), TOGGLE)) return;
        if (!allowedWorld(player) && config().getBoolean("reset-on-leave", false)) {
            plugin.toggles().set(player, TOGGLE, false);
        }
        apply(player);
    }

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        if (!SetupCoreImport.Source.has(source.main(), "toggle_flags")) return;
        int count = 0;
        try (Statement st = source.main().createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid FROM toggle_flags WHERE flag = 'playerhide_on'")) {
            while (rs.next()) {
                Toggles.write(db(), target, rs.getString(1).toLowerCase(Locale.ROOT), TOGGLE, true);
                count++;
            }
        }
        report.add(count, "players hiding others");
    }
}
