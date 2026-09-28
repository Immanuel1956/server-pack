package com.vexorstudios.vexcore.features.joincounter;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.SoundSpec;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Welcomes a player the very first time they join, with their number: "Welcome X! [#6,822]". */
public final class JoinCounterFeature extends Feature implements Listener {

    private File file;
    private volatile long counter;

    @Override
    protected void enable() {
        file = plugin.files().data("joincounter.yml");
        counter = YamlConfiguration.loadConfiguration(file).getLong("counter", 0);
        listen(this);
        placeholder("joincount", (p, a) -> format(counter));
        command("joincounter", (sender, label, args) -> {
            if (args.length == 0) {
                msg(sender, "current", "counter", format(counter));
                return;
            }
            switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
                case "reset", "clear" -> {
                    save(0);
                    msg(sender, args[0].equalsIgnoreCase("clear") ? "clear" : "reset");
                }
                case "set" -> {
                    long n;
                    try {
                        n = Long.parseLong(args.length > 1 ? args[1] : "x");
                    } catch (NumberFormatException e) {
                        msg(sender, "invalid-number");
                        return;
                    }
                    save(Math.max(0, n));
                    msg(sender, "set", "counter", format(counter));
                }
                default -> msg(sender, "usage");
            }
        }, (s, a) -> a.length == 1 ? List.of("reset", "clear", "set") : List.of());
    }

    private String format(long n) {
        return config().getBoolean("format-number", true) ? Numbers.full(n, 0, config().getString("thousands-separator", ",")) : String.valueOf(n);
    }

    /** Sets the counter; the file is written off the main thread (always the latest value). */
    private synchronized void save(long value) {
        counter = value;
        com.vexorstudios.vexcore.core.Scheduler.async(this::write);
    }

    private void write() {
        if (file == null) return;
        synchronized (file) {
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("counter", counter);
            try {
                yml.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save " + file + ": " + e.getMessage());
            }
        }
    }

    @Override
    protected void disable() {
        write(); // an async write still queued at shutdown may never run
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;
        long number;
        synchronized (this) {
            save(counter + 1);
            number = counter;
        }
        plugin.messages().broadcast(Bukkit.getOnlinePlayers(), config().getString("message", "Welcome %player%! [#%counter%]"),
                Map.of("player", player.getName(), "counter", format(number)), plugin.messages().prefix(this));
        SoundSpec sound = SoundSpec.of(config().get("sound"));
        if (sound != null) {
            if (config().getBoolean("sound.everyone", false)) for (Player p : Bukkit.getOnlinePlayers()) sound.play(p);
            else sound.play(player);
        }
    }
}
