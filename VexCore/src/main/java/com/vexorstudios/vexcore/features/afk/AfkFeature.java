package com.vexorstudios.vexcore.features.afk;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Pos;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/** /afk teleports to the afk area, /setafk sets it. Kept in data/afk.yml. */
public final class AfkFeature extends Feature {

    private volatile Pos area;
    private File file;

    @Override
    protected void enable() {
        file = plugin.files().data("afk.yml");
        area = Pos.read(YamlConfiguration.loadConfiguration(file).getConfigurationSection("afk"));
        command("afk", (sender, label, args) -> {
            Player player = player(sender);
            if (player == null) return;
            Pos pos = area;
            if (pos == null) {
                msg(player, "not-set");
                return;
            }
            plugin.teleports().start(this, player, pos::location, "vexcore.afk.bypass", Map.of(), null);
        });
        command("setafk", (sender, label, args) -> {
            Player player = player(sender);
            if (player == null) return;
            set(Pos.of(player.getLocation()));
            msg(player, "set");
        });
    }

    private void set(Pos pos) {
        area = pos;
        YamlConfiguration yml = new YamlConfiguration();
        pos.write(yml.createSection("afk"));
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + file + ": " + e.getMessage());
        }
    }

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        Connection old = source.teleports();
        if (!SetupCoreImport.Source.has(old, "afk")) return;
        try (Statement st = old.createStatement(); ResultSet rs = st.executeQuery("SELECT world, x, y, z, yaw, pitch FROM afk WHERE id = 1")) {
            if (!rs.next() || rs.getString(1) == null) return;
            Pos pos = new Pos(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getFloat(5), rs.getFloat(6));
            Scheduler.global(() -> set(pos));
            report.add(1, "afk area");
        }
    }
}
