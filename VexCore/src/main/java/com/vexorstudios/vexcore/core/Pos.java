package com.vexorstudios.vexcore.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A saved position. The world is kept by name and looked up only when the position is used,
 * so positions in worlds that load late (or were removed) never break loading.
 */
public record Pos(String world, double x, double y, double z, float yaw, float pitch) {

    public static Pos of(Location l) {
        return new Pos(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** The location, or null if the world is not loaded. */
    public Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public void write(ConfigurationSection s) {
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("yaw", (double) yaw);
        s.set("pitch", (double) pitch);
    }

    public static Pos read(ConfigurationSection s) {
        if (s == null || s.getString("world") == null) return null;
        return new Pos(s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }
}
