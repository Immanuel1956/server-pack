package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Loads each player's data when they join and saves it when they leave.
 *
 * <p>Features keep their per-player data in a {@link Store}. Loads and saves go through the one
 * database queue, so they happen in order. A player counts as <i>loaded</i> only once every store
 * has read their rows; before that, nothing of theirs is saved (it would write empty values
 * over the real ones) and features refuse changes with the "data-loading" message. A load that
 * finishes after its player already left notices and cleans up after itself.
 */
public final class PlayerData implements Listener {

    /** Per-player data of one feature. */
    public interface Store {
        /** Database thread: read this player's rows into memory. */
        void load(UUID player, Connection connection) throws SQLException;

        /**
         * Main/owner thread: snapshot what needs saving and return the write, or null if
         * everything was already written when it changed.
         */
        default Database.Work save(UUID player) {
            return null;
        }

        /** Forget the player. */
        void unload(UUID player);
    }

    private record Entry(Feature owner, Store store) {
        String name() {
            return owner == null ? "core" : owner.id();
        }
    }

    private final VexCore plugin;
    private final List<Entry> stores = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private final Object lock = new Object();
    private Scheduler.Task autosave = Scheduler.NOOP;

    public PlayerData(VexCore plugin) {
        this.plugin = plugin;
    }

    public void register(Feature owner, Store store) {
        stores.add(new Entry(owner, store));
    }

    public void unregister(Feature owner) {
        stores.removeIf(e -> e.owner == owner);
    }

    public boolean isLoaded(UUID player) {
        return loaded.contains(player);
    }

    /** Joined and still loading (or a failed load): their data must not be touched yet. */
    public boolean isLoading(UUID player) {
        return sessions.containsKey(player) && !loaded.contains(player);
    }

    public void startAutosave(int minutes) {
        autosave.cancel();
        if (minutes <= 0) return;
        long period = minutes * 60L * 20L;
        autosave = Scheduler.globalTimer(this::saveAll, period, period);
    }

    public void stopAutosave() {
        autosave.cancel();
        autosave = Scheduler.NOOP;
    }

    // ── Join / quit ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        unload(event.getPlayer());
    }

    public void load(Player player) {
        UUID id = player.getUniqueId();
        long token = sequence.incrementAndGet();
        sessions.put(id, token);
        List<Entry> snapshot = List.copyOf(stores);
        plugin.database().queue("load " + player.getName(), c -> {
            boolean ok = true;
            for (Entry e : snapshot) {
                try {
                    e.store.load(id, c);
                } catch (SQLException | RuntimeException error) {
                    ok = false;
                    plugin.getLogger().log(Level.SEVERE, "Could not load " + e.name() + " data of "
                            + player.getName() + "; their data stays read-only until they rejoin.", error);
                }
            }
            boolean current;
            synchronized (lock) {
                Long active = sessions.get(id);
                current = active != null && active == token;
                if (current && ok) loaded.add(id);
            }
            if (!current) {
                for (Entry e : snapshot) e.store.unload(id);
                return;
            }
            if (ok) {
                Scheduler.entity(player, () -> {
                    for (Feature feature : plugin.features().active()) {
                        try {
                            feature.loaded(player);
                        } catch (Throwable error) {
                            plugin.getLogger().log(Level.SEVERE, feature.id() + " failed after loading " + player.getName(), error);
                        }
                    }
                });
            }
        });
    }

    public void unload(Player player) {
        UUID id = player.getUniqueId();
        boolean wasLoaded;
        synchronized (lock) {
            Long active = sessions.get(id);
            if (active != null) sessions.remove(id, active);
            wasLoaded = loaded.remove(id);
        }
        if (wasLoaded) {
            List<Database.Work> writes = snapshot(id);
            if (!writes.isEmpty()) plugin.database().queue("save " + player.getName(), c -> {
                for (Database.Work w : writes) w.run(c);
            });
        }
        for (Entry e : stores) {
            try {
                e.store.unload(id);
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Unload failed in " + e.name(), error);
            }
        }
    }

    private List<Database.Work> snapshot(UUID id) {
        List<Database.Work> writes = new ArrayList<>();
        for (Entry e : stores) {
            try {
                Database.Work w = e.store.save(id);
                if (w != null) writes.add(w);
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.SEVERE, "Could not snapshot " + e.name() + " data of " + id, error);
            }
        }
        return writes;
    }

    /** Autosave: every loaded player, 50 per transaction. */
    public void saveAll() {
        List<Database.Work> batch = new ArrayList<>();
        int players = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!loaded.contains(player.getUniqueId())) continue;
            batch.addAll(snapshot(player.getUniqueId()));
            if (++players % 50 == 0) {
                flush(batch);
                batch = new ArrayList<>();
            }
        }
        flush(batch);
    }

    private void flush(List<Database.Work> batch) {
        if (batch.isEmpty()) return;
        plugin.database().queue("autosave", c -> {
            for (Database.Work w : batch) w.run(c);
        });
    }

    /** Saves and forgets everyone online (reload, import, shutdown). */
    public void unloadAll() {
        for (Player player : Bukkit.getOnlinePlayers()) unload(player);
    }

    /** Loads everyone online (after a reload or import). */
    public void loadAll() {
        for (Player player : Bukkit.getOnlinePlayers()) load(player);
    }
}
