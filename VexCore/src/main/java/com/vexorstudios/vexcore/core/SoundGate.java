package com.vexorstudios.vexcore.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One sound per action ({@code sounds.one-per-action} in config.yml). A menu click that gives a
 * boost, opens another menu or fails used to play two or three sounds on top of each other: the
 * click, then the result. Now the sounds a player is sent within one tick are collected and only
 * the most important one plays, a tick later:
 * <pre>
 *   ERROR   (4)  error messages ("you can't afford that")
 *   NORMAL  (3)  every other message and feature sound (the boost, the reward, the link)
 *   MENU    (2)  a menu opening, closing, turning a page
 *   CLICK   (1)  the click of a menu button, the message click
 * </pre>
 * Equal ones: the first wins. For {@link #WINDOW_MS} after a sound played, sounds that aren't
 * more important are dropped too (the result that arrives a tick late, a double click).
 *
 * <p>Only the decisions live here; {@link SoundSpec#play(org.bukkit.entity.Player, int)} does
 * the scheduling and the playing.
 */
public final class SoundGate {

    public static final int CLICK = 1, MENU = 2, NORMAL = 3, ERROR = 4;
    static final long WINDOW_MS = 150;

    private record Pending(SoundSpec sound, int priority) {
    }

    private record Played(long at, int priority) {
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Played> played = new ConcurrentHashMap<>();

    /**
     * A sound for a player. True when this is the first one of the tick: the caller then plays
     * {@link #take} a tick later. False when it was merged into one already waiting, or dropped.
     */
    public boolean offer(UUID player, SoundSpec sound, int priority, long now) {
        Played last = played.get(player);
        if (last != null && now - last.at < WINDOW_MS && last.priority >= priority) return false;
        boolean[] first = {false};
        pending.compute(player, (k, old) -> {
            if (old == null) {
                first[0] = true;
                return new Pending(sound, priority);
            }
            return priority > old.priority ? new Pending(sound, priority) : old;
        });
        return first[0];
    }

    /** The sound that won for this player (null if none), remembered as just played. */
    public SoundSpec take(UUID player, long now) {
        Pending p = pending.remove(player);
        if (p == null) return null;
        if (played.size() > 1024) played.values().removeIf(x -> now - x.at >= WINDOW_MS);
        played.put(player, new Played(now, p.priority));
        return p.sound;
    }

    /** The player left before the tick came: forget them. */
    public void drop(UUID player) {
        pending.remove(player);
        played.remove(player);
    }

    public void clear() {
        pending.clear();
        played.clear();
    }
}
