package com.vexorstudios.vexcore.features.nightvision;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import com.vexorstudios.vexcore.core.Toggles;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * /nightvision: permanent night vision until turned off. Survives milk, death, relogs and
 * restarts. A night vision potion the player drank themselves is never removed.
 */
public final class NightVisionFeature extends Feature implements Listener {

    private static final String TOGGLE = "nightvision";

    @Override
    protected void enable() {
        toggle(TOGGLE, config().getBoolean("default", false), this::toggle);
        command("nightvision", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null) toggle(player);
        });
        listen(this);
    }

    @Override
    protected void disable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            onPlayerThread(player, () -> apply(player, false)); // data is already unloaded here
        }
    }

    private boolean on(Player player) {
        return plugin.toggles().isOn(player.getUniqueId(), TOGGLE);
    }

    private void toggle(Player player) {
        Boolean now = flip(player, TOGGLE, "enabled", "disabled");
        if (now != null) apply(player, now);
    }

    private void apply(Player player, boolean on) {
        if (on) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION,
                    0, false, config().getBoolean("show-particles", false), config().getBoolean("show-icon", false)));
        } else {
            PotionEffect current = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
            if (current != null && current.isInfinite()) player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    @Override
    protected void loaded(Player player) {
        if (on(player)) apply(player, true);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (on(player)) Scheduler.entityLater(player, () -> apply(player, true), 1);
    }

    /** Milk, totems and /effect clear would take it away; keep it. */
    @EventHandler(ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getModifiedType() != PotionEffectType.NIGHT_VISION) return;
        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.REMOVED && action != EntityPotionEffectEvent.Action.CLEARED) return;
        EntityPotionEffectEvent.Cause cause = event.getCause();
        if (cause == EntityPotionEffectEvent.Cause.PLUGIN || cause == EntityPotionEffectEvent.Cause.DEATH) return;
        if (on(player)) event.setCancelled(true);
    }

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        if (!SetupCoreImport.Source.has(source.main(), "toggle_flags")) return;
        int count = 0;
        try (Statement st = source.main().createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid FROM toggle_flags WHERE flag = 'nightvision_on'")) {
            while (rs.next()) {
                Toggles.write(db(), target, rs.getString(1).toLowerCase(Locale.ROOT), TOGGLE, true);
                count++;
            }
        }
        report.add(count, "players with night vision on");
    }
}
