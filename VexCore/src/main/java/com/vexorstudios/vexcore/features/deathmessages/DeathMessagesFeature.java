package com.vexorstudios.vexcore.features.deathmessages;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import com.vexorstudios.vexcore.core.Toggles;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Death messages per cause, in three groups: killed by a player, by a mob, or by the world.
 * Shown only to players who did not turn them off with /deathtoggle.
 */
public final class DeathMessagesFeature extends Feature implements Listener {

    private static final String TOGGLE = "deathmessages";

    @Override
    protected void enable() {
        toggle(TOGGLE, config().getBoolean("default", true), p -> flip(p, TOGGLE, "shown", "hidden"));
        command("deathtoggle", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) flip(player, TOGGLE, "shown", "hidden");
        });
        listen(this);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getPlayer();
        Entity killer = event.getDamageSource().getCausingEntity();
        if (killer == null || killer.equals(dead)) killer = dead.getKiller();
        String group = killer instanceof Player && !killer.equals(dead) ? "by-player"
                : killer instanceof LivingEntity && !killer.equals(dead) ? "by-mob" : "environment";
        EntityDamageEvent last = dead.getLastDamageCause();
        String cause = last == null ? "custom" : last.getCause().name().toLowerCase(Locale.ROOT).replace('_', '-');
        String line = config().getString(group + "." + cause, config().getString(group + ".default", null));
        if (line == null) return; // nothing configured: keep the vanilla message
        event.deathMessage(null);
        if (line.isEmpty()) return;

        Map<String, Object> ph = new HashMap<>();
        ph.put("player", com.vexorstudios.vexcore.core.Visibility.name(dead));
        ph.put("symbol", config().getString("symbols." + group, ""));
        if (killer instanceof Player p) {
            // A vanished killer stays hidden; a /hide one shows their shared name.
            boolean vanished = plugin.features().get("vanish") instanceof com.vexorstudios.vexcore.features.vanish.VanishFeature v && v.isVanished(p.getUniqueId());
            ph.put("killer", vanished ? config().getString("hidden-killer", "Someone") : com.vexorstudios.vexcore.core.Visibility.name(p));
            ItemStack weapon = p.getInventory().getItemInMainHand();
            ph.put("weapon", weapon.getType().isAir() ? Component.text(config().getString("fists", "their fists")) : weapon.effectiveName());
        } else if (killer != null) {
            Component name = killer.customName();
            ph.put("killer", name != null ? name : Component.translatable(killer.getType().translationKey()));
            ph.put("weapon", Component.empty());
        } else {
            ph.put("killer", "");
            ph.put("weapon", Component.empty());
        }
        List<CommandSender> to = new ArrayList<>();
        to.add(Bukkit.getConsoleSender());
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.toggles().isOn(p.getUniqueId(), TOGGLE)) to.add(p);
        plugin.messages().broadcast(to, line, ph, plugin.messages().prefix(this));
    }

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        if (!SetupCoreImport.Source.has(source.main(), "toggle_flags")) return;
        int count = 0;
        try (Statement st = source.main().createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid FROM toggle_flags WHERE flag = 'deathmsg_off'")) {
            while (rs.next()) {
                Toggles.write(db(), target, rs.getString(1).toLowerCase(Locale.ROOT), TOGGLE, false);
                count++;
            }
        }
        report.add(count, "players with death messages hidden");
    }
}
