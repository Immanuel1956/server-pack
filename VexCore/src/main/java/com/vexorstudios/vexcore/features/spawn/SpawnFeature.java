package com.vexorstudios.vexcore.features.spawn;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Pos;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SetupCoreImport;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Named spawns, kept in data/spawns.yml (server-local, even when MySQL is shared).
 * /spawn [name] [player], /setspawn [name], /delspawn &lt;name&gt;, /spawns.
 * Also places first joins, joins and respawns.
 */
public final class SpawnFeature extends Feature implements Listener {

    private final Map<String, Pos> spawns = new ConcurrentSkipListMap<>();
    private File file;

    @Override
    protected void enable() {
        file = plugin.files().data("spawns.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("spawns");
        if (root != null) for (String name : root.getKeys(false)) {
            Pos pos = Pos.read(root.getConfigurationSection(name));
            if (pos != null) spawns.put(name.toLowerCase(Locale.ROOT), pos);
        }
        command("spawn", this::spawn, (s, a) -> a.length == 1 ? new ArrayList<>(spawns.keySet()) : null);
        command("setspawn", this::setSpawn, (s, a) -> new ArrayList<>(spawns.keySet()));
        command("delspawn", this::delSpawn, (s, a) -> new ArrayList<>(spawns.keySet()));
        command("spawns", (sender, label, args) -> list(sender));
        listen(this);
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        spawns.forEach((name, pos) -> pos.write(yml.createSection("spawns." + name)));
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + file + ": " + e.getMessage());
        }
    }

    /** "" = the default spawn, "random" = any spawn, otherwise that name. Null if none. */
    private Pos resolve(String name) {
        if (spawns.isEmpty()) return null;
        if (name == null || name.isBlank()) name = config().getString("default-spawn", "spawn");
        if (name.equalsIgnoreCase("random")) {
            List<Pos> all = new ArrayList<>(spawns.values());
            return all.get(ThreadLocalRandom.current().nextInt(all.size()));
        }
        Pos pos = spawns.get(name.toLowerCase(Locale.ROOT));
        if (pos == null && name.equalsIgnoreCase(config().getString("default-spawn", "spawn"))) {
            pos = spawns.values().iterator().next(); // default missing: any spawn beats none
        }
        return pos;
    }

    private void spawn(CommandSender sender, String label, String[] args) {
        String name = args.length > 0 ? args[0] : config().getString("default-spawn", "spawn");
        if (spawns.isEmpty()) {
            msg(sender, "no-spawns");
            return;
        }
        Pos pos = resolve(name);
        if (pos == null) {
            msg(sender, "spawn-not-found", "name", name);
            return;
        }
        if (args.length > 1) {
            if (!sender.hasPermission("vexcore.spawn.others")) {
                msg(sender, "no-permission", "permission", "vexcore.spawn.others");
                return;
            }
            Player target = target(sender, args[1]);
            if (target == null) return;
            Location location = pos.location();
            if (location == null) {
                msg(sender, "teleport-failed", "name", name);
                return;
            }
            Scheduler.entity(target, () -> target.teleportAsync(location));
            msg(target, "teleport-success", "name", name);
            msg(sender, "sent-other", "player", target.getName(), "name", name);
            return;
        }
        Player player = player(sender);
        if (player == null) return;
        plugin.teleports().start(this, player, pos::location, "vexcore.spawn.bypass", Map.of("name", name), null);
    }

    private void setSpawn(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        String name = (args.length > 0 ? args[0] : config().getString("default-spawn", "spawn")).toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,32}")) { // dots would nest in the YAML file
            msg(player, "invalid-name", "name", name);
            return;
        }
        spawns.put(name, Pos.of(player.getLocation()));
        save();
        msg(player, "set", "name", name);
    }

    private void delSpawn(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, "delspawn");
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        if (spawns.remove(name) == null) {
            msg(sender, "spawn-not-found", "name", name);
            return;
        }
        save();
        msg(sender, "deleted", "name", name);
    }

    private void list(CommandSender sender) {
        if (spawns.isEmpty()) msg(sender, "no-spawns");
        else msg(sender, "list", "spawns", String.join(", ", spawns.keySet()), "amount", spawns.size());
    }

    // ── Joining and respawning ────────────────────────────────────────────

    /** Before the world is sent, so a first join never flickers at the old spot. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        String section = event.isNewPlayer() && config().getBoolean("first-join.enabled", true) ? "first-join"
                : config().getBoolean("join.enabled", false) ? "join" : null;
        if (section == null) return;
        Pos pos = resolve(config().getString(section + ".spawn", ""));
        Location location = pos == null ? null : pos.location();
        if (location != null) event.setSpawnLocation(location);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!config().getBoolean("respawn.enabled", true)) return;
        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) return;
        if (!config().getBoolean("respawn.override-bed", false) && (event.isBedSpawn() || event.isAnchorSpawn())) return;
        Pos pos = resolve(config().getString("respawn.spawn", ""));
        Location location = pos == null ? null : pos.location();
        if (location != null) event.setRespawnLocation(location);
    }

    // ── SetupCore import ──────────────────────────────────────────────────

    @Override
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report) throws SQLException {
        Connection old = source.teleports();
        if (!SetupCoreImport.Source.has(old, "spawn")) return;
        try (Statement st = old.createStatement(); ResultSet rs = st.executeQuery("SELECT world, x, y, z, yaw, pitch FROM spawn WHERE id = 1")) {
            if (!rs.next() || rs.getString(1) == null) return;
            Pos pos = new Pos(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getFloat(5), rs.getFloat(6));
            String name = config().getString("default-spawn", "spawn").toLowerCase(Locale.ROOT);
            Scheduler.global(() -> {
                spawns.put(name, pos);
                save();
            });
            report.add(1, "spawn");
        }
    }
}
