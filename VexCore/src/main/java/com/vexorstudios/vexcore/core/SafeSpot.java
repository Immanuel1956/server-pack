package com.vexorstudios.vexcore.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Finds a random safe spot to stand on (RTP, 1v1). Chunks are loaded in the background and the
 * blocks are checked on the region's own thread, so it works on Folia too. The first
 * {@code generated-attempts} tries only use chunks that already exist.
 */
public final class SafeSpot {

    private static final Set<Material> UNSAFE = Set.of(Material.LAVA, Material.WATER, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW,
            Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE, Material.BEDROCK);

    private SafeSpot() {
    }

    /** A world section: world ("" = first world of the environment below), radius, center-x/z, min-y, attempts. */
    public record Area(World world, int centerX, int centerZ, int radius, int minY, int maxY, int attempts, int generatedAttempts,
                       Set<String> blockedBiomes, int parallel, int timeoutSeconds) {

        /**
         * {@code fallback}: the kind of world to use when the section names none. A section may
         * say {@code environment: NORMAL|NETHER|THE_END} itself (otherwise the key's name decides).
         * {@code search} (may be null): {@code parallel} and {@code timeout-seconds}.
         */
        public static Area of(ConfigurationSection s, World.Environment fallback, List<String> blockedBiomes, int generatedAttempts,
                              ConfigurationSection search) {
            if (s == null) return null;
            String env = s.getString("environment", "");
            if (!env.isEmpty()) {
                try {
                    fallback = World.Environment.valueOf(env.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
            World world = SafeSpot.world(s.getString("world", ""), fallback);
            if (world == null) return null;
            Set<String> biomes = new HashSet<>();
            for (String b : blockedBiomes) biomes.add(b.toLowerCase(Locale.ROOT));
            return new Area(world, s.getInt("center-x", 0), s.getInt("center-z", 0), Math.max(16, s.getInt("radius", 3000)),
                    s.getInt("min-y", 50), s.getInt("max-y", 120), Math.max(1, s.getInt("attempts", 30)), generatedAttempts, biomes,
                    search == null ? 4 : Math.max(1, Math.min(16, search.getInt("parallel", 4))),
                    search == null ? 60 : Math.max(5, search.getInt("timeout-seconds", 60)));
        }
    }

    /** A world by name, or the first world of that environment when the name is empty. */
    public static World world(String name, World.Environment environment) {
        if (name != null && !name.isEmpty()) return Bukkit.getWorld(name);
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == environment) return w;
        return null;
    }

    
    /** Completes with a safe location, or null when every attempt failed. */
    public static CompletableFuture<Location> find(Area area) {
        CompletableFuture<Location> out = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger(area.parallel);
        for (int i = 0; i < area.parallel; i++) attempt(area, next, running, out);
        // Never waits forever (a stuck chunk load): after a minute it counts as "not found".
        return out.completeOnTimeout(null, area.timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void attempt(Area a, java.util.concurrent.atomic.AtomicInteger next,
                                java.util.concurrent.atomic.AtomicInteger running, CompletableFuture<Location> out) {
        int n = next.getAndIncrement();
        if (out.isDone() || n >= a.attempts) {
            if (running.decrementAndGet() == 0) out.complete(null); // the last search gave up
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // A block inside the chunk, never on its edge: the checks look one block around it and
        // must not reach into a neighbouring chunk (unloaded, or another region on Folia).
        int cx = (a.centerX + r.nextInt(-a.radius, a.radius + 1)) >> 4;
        int cz = (a.centerZ + r.nextInt(-a.radius, a.radius + 1)) >> 4;
        int x = (cx << 4) + 1 + r.nextInt(14), z = (cz << 4) + 1 + r.nextInt(14);
        // New terrain is allowed after generated-attempts tries, and never later than halfway,
        // so a fresh map (few chunks made yet) still finds a spot.
        boolean generate = n >= Math.min(a.generatedAttempts, a.attempts / 2);
        a.world.getChunkAtAsync(cx, cz, generate).whenComplete((chunk, error) -> {
            if (chunk == null || error != null) {
                attempt(a, next, running, out);
                return;
            }
            Scheduler.region(new Location(a.world, x, 64, z), () -> {
                Location ok;
                try {
                    ok = out.isDone() ? null : check(a, x, z);
                } catch (RuntimeException error2) {
                    ok = null; // a bad block read is just a failed try
                }
                if (ok != null) {
                    out.complete(ok);
                    running.decrementAndGet();
                } else {
                    attempt(a, next, running, out);
                }
            });
        });
    }

    public static CompletableFuture<Location> at(Area area, int x, int z) {
        CompletableFuture<Location> out = new CompletableFuture<>();
        // check() inspects neighbours: stay inside a chunk to avoid cross-region reads.
        int safeX = (x & ~15) + Math.max(1, Math.min(14, x & 15));
        int safeZ = (z & ~15) + Math.max(1, Math.min(14, z & 15));
        area.world().getChunkAtAsync(safeX >> 4, safeZ >> 4, true).whenComplete((chunk, error) -> {
            if (chunk == null || error != null) { out.complete(null); return; }
            Scheduler.region(new Location(area.world(), safeX, 64, safeZ), () -> {
                try { out.complete(check(area, safeX, safeZ)); }
                catch (RuntimeException bad) { out.complete(null); }
            });
        });
        return out.completeOnTimeout(null, area.timeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Checks a spot found earlier again (terrain changes): true if it is still safe. Loads no new terrain. */
    public static CompletableFuture<Boolean> recheck(Area a, Location spot) {
        CompletableFuture<Boolean> out = new CompletableFuture<>();
        int x = spot.getBlockX(), z = spot.getBlockZ();
        a.world.getChunkAtAsync(x >> 4, z >> 4, false).whenComplete((chunk, error) -> {
            if (chunk == null || error != null) {
                out.complete(false);
                return;
            }
            Scheduler.region(spot, () -> {
                try {
                    Location now = check(a, x, z);
                    out.complete(now != null && now.getBlockY() == spot.getBlockY());
                } catch (RuntimeException bad) {
                    out.complete(false);
                }
            });
        });
        return out.completeOnTimeout(false, Math.max(5, a.timeoutSeconds / 2), java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Blocks that hurt, trap or slow down a player standing in them. */
    private static final Set<Material> HAZARD = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
            Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW, Material.COBWEB,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POINTED_DRIPSTONE, Material.NETHER_PORTAL,
            Material.END_PORTAL, Material.END_GATEWAY);

    /** Region thread: a solid, safe block with two clear blocks above it and no danger around, or null. */
    public static Location check(Area a, int x, int z) {
        Block ground;
        if (a.world.getEnvironment() == World.Environment.NETHER) {
            // The Nether's highest block is its bedrock roof: look for ground below it instead.
            ground = null;
            for (int y = a.maxY; y >= a.minY; y--) {
                Block b = a.world.getBlockAt(x, y, z);
                if (standable(b)) {
                    ground = b;
                    break;
                }
            }
            if (ground == null) return null;
        } else {
            ground = a.world.getHighestBlockAt(x, z);
            if (ground.getY() < a.minY || !standable(ground)) return null;
        }
        if (a.blockedBiomes.contains(ground.getBiome().getKey().getKey())) return null;
        // Nothing dangerous right next to the feet or the ground (lava beside you, a cactus...).
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 2; dy++) {
            if (HAZARD.contains(ground.getRelative(dx, dy, dz).getType())) return null;
        }
        Location spot = ground.getLocation().add(0.5, 1, 0.5);
        return a.world.getWorldBorder().isInside(spot) ? spot : null;
    }

    /** Solid, not dangerous, not leaves (tree tops), with room for a player above that is truly clear. */
    private static boolean standable(Block ground) {
        Material type = ground.getType();
        if (!type.isSolid() || UNSAFE.contains(type) || HAZARD.contains(type) || org.bukkit.Tag.LEAVES.isTagged(type)) return false;
        return clear(ground.getRelative(0, 1, 0)) && clear(ground.getRelative(0, 2, 0));
    }

    private static boolean clear(Block b) {
        return b.isEmpty() || (b.isPassable() && !b.isLiquid() && !HAZARD.contains(b.getType()));
    }
}
