package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.TimeUnit;

/**
 * One scheduler for Paper and Folia. Paper's region/entity/async schedulers exist on both: on
 * Paper they all run on the main thread, on Folia each task runs on the thread that owns it.
 *
 * <ul>
 *   <li>{@code global*} - server-wide work (broadcasts, console commands, timers).</li>
 *   <li>{@code entity*} - work on one player (their inventory, effects, countdowns). Skipped
 *       automatically once the player has left.</li>
 *   <li>{@code region*} - block or entity changes at a location.</li>
 *   <li>{@code async*}  - file and network work that never touches the world.</li>
 * </ul>
 * All times are in ticks. Folia forbids a delay of 0, so every delay is at least 1.
 */
public final class Scheduler {

    private Scheduler() {
    }

    /** A handle that can be cancelled any number of times. */
    @FunctionalInterface
    public interface Task {
        void cancel();
    }

    public static final Task NOOP = () -> {
    };

    /** True on Folia, where some Bukkit APIs (scoreboards, world-wide entity lists) don't work. */
    public static final boolean FOLIA = folia();

    private static boolean folia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Task wrap(ScheduledTask task) {
        if (task == null) return NOOP;
        return () -> {
            try {
                task.cancel();
            } catch (Throwable ignored) {
            }
        };
    }

    // ── Global ────────────────────────────────────────────────────────────

    public static void global(Runnable run) {
        Bukkit.getGlobalRegionScheduler().execute(VexCore.get(), run);
    }

    public static Task globalLater(Runnable run, long delay) {
        return wrap(Bukkit.getGlobalRegionScheduler().runDelayed(VexCore.get(), t -> run.run(), Math.max(1, delay)));
    }

    public static Task globalTimer(Runnable run, long delay, long period) {
        return wrap(Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(VexCore.get(), t -> run.run(), Math.max(1, delay), Math.max(1, period)));
    }

    // ── Entity ────────────────────────────────────────────────────────────

    public static void entity(Entity entity, Runnable run) {
        entity.getScheduler().run(VexCore.get(), t -> run.run(), null);
    }

    /**
     * Like {@link #entity(Entity, Runnable)}, but {@code retired} runs instead if the player has
     * left (or leaves before it runs). For work that must not vanish: payouts, returned items.
     */
    public static void entity(Entity entity, Runnable run, Runnable retired) {
        if (entity.getScheduler().run(VexCore.get(), t -> run.run(), () -> global(retired)) == null) global(retired);
    }

    public static Task entityLater(Entity entity, Runnable run, long delay) {
        return wrap(entity.getScheduler().runDelayed(VexCore.get(), t -> run.run(), null, Math.max(1, delay)));
    }

    /** {@code retired} runs instead (any thread) if the entity is gone before the delay ends. */
    public static void entityLater(Entity entity, Runnable run, Runnable retired, long delay) {
        if (entity.getScheduler().runDelayed(VexCore.get(), t -> run.run(), retired, Math.max(1, delay)) == null) retired.run();
    }

    public static Task entityTimer(Entity entity, Runnable run, long delay, long period) {
        return wrap(entity.getScheduler()
                .runAtFixedRate(VexCore.get(), t -> run.run(), null, Math.max(1, delay), Math.max(1, period)));
    }

    // ── Region ────────────────────────────────────────────────────────────

    public static void region(Location location, Runnable run) {
        Bukkit.getRegionScheduler().execute(VexCore.get(), location, run);
    }

    // ── Async ─────────────────────────────────────────────────────────────

    public static void async(Runnable run) {
        Bukkit.getAsyncScheduler().runNow(VexCore.get(), t -> run.run());
    }

    public static Task asyncTimer(Runnable run, long delay, long period) {
        return wrap(Bukkit.getAsyncScheduler().runAtFixedRate(VexCore.get(), t -> run.run(),
                Math.max(1, delay) * 50L, Math.max(1, period) * 50L, TimeUnit.MILLISECONDS));
    }
}
