package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import com.vexorstudios.vexcore.gui.Menu;
import com.vexorstudios.vexcore.gui.MenuFile;
import com.vexorstudios.vexcore.gui.MenuListener;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A feature: one folder under features/ (or one file in a shared folder, see {@link Files}),
 * one toggle in config.yml.
 *
 * <p>Everything a feature registers through these helpers (listeners, timers, commands,
 * toggles, placeholders, player data, teleport restrictions) is removed again when it is
 * disabled, so turning a feature off in config.yml and reloading takes it off the server
 * completely. A reload creates a fresh instance; nothing survives from the old one.
 */
public abstract class Feature {

    protected VexCore plugin;
    private String id;
    private YamlConfiguration config = new YamlConfiguration();
    private final Map<String, MenuFile> menus = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Scheduler.Task> tasks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> commandIds = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private volatile boolean enabled;

    // ── Lifecycle (called by FeatureManager) ──────────────────────────────

    final void start(VexCore plugin, String id) {
        this.plugin = plugin;
        this.id = id;
        this.config = plugin.files().settings(Files.configPath(id));
        for (String name : plugin.files().menus(id)) {
            String path = Files.menuPath(id, name);
            MenuFile file = new MenuFile(path, plugin.files().menu(path));
            problems.addAll(file.problems());
            menus.put(name, file);
        }
        enabled = true;
        enable();
    }

    final void stop() {
        enabled = false;
        try {
            disable();
        } finally {
            MenuListener.closeAll(this, !plugin.isEnabled());
            for (Listener l : listeners) HandlerList.unregisterAll(l);
            listeners.clear();
            for (Scheduler.Task t : tasks) t.cancel();
            tasks.clear();
            plugin.commands().unregister(this);
            plugin.toggles().clear(this);
            plugin.placeholders().clear(this);
            plugin.restrictions().clear(this);
            plugin.teleports().cancelAll(this);
            plugin.data().unregister(this);
        }
    }

    /** Register everything here. The feature's files are already loaded. */
    protected abstract void enable();

    /** Undo anything the helpers do not undo themselves (effects on players, open requests...). */
    protected void disable() {
    }

    /** Settle payouts before player stores are snapshotted for shutdown/reload. */
    protected void prepareShutdown() {}

    /** A player's data finished loading (runs on their thread). */
    protected void loaded(Player player) {
    }

    /**
     * {@code /vexcore import setupcore}: copy this feature's SetupCore data. Runs on the database
     * thread with every online player's data saved and unloaded; {@code target} is VexCore's
     * database, inside the import transaction.
     */
    protected void importSetupCore(SetupCoreImport.Source source, Connection target, SetupCoreImport.Report report)
            throws SQLException {
    }

    // ── Info ──────────────────────────────────────────────────────────────

    public final String id() {
        return id;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    public final YamlConfiguration config() {
        return config;
    }

    public final List<String> problems() {
        return problems;
    }

    public final List<String> commandIds() {
        return Collections.unmodifiableList(commandIds);
    }

    public final Map<String, MenuFile> menus() {
        return Collections.unmodifiableMap(menus);
    }

    protected final Database db() {
        return plugin.database();
    }

    // ── Registration helpers ──────────────────────────────────────────────

    protected final void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    /** Keeps a task so it is cancelled when the feature is disabled. */
    protected final Scheduler.Task track(Scheduler.Task task) {
        tasks.add(task);
        return task;
    }

    /** Runs on the player's thread; right here during shutdown, when nothing can be scheduled. */
    protected final void onPlayerThread(Player player, Runnable run) {
        if (plugin.isEnabled()) Scheduler.entity(player, run);
        else run.run();
    }

    /** A server-wide timer, in ticks. */
    protected final void every(long periodTicks, Runnable run) {
        track(Scheduler.globalTimer(run, periodTicks, periodTicks));
    }

    protected final void command(String commandId, Commands.Handler handler) {
        command(commandId, handler, null);
    }

    protected final void command(String commandId, Commands.Handler handler, Commands.Completer completer) {
        if (plugin.commands().register(this, commandId, handler, completer)) commandIds.add(commandId);
    }

    /** A command not in commands.yml, with its name, aliases and permission given here. */
    protected final void command(String commandId, String name, List<String> aliases, String permission, String description,
                                 Commands.Handler handler) {
        if (plugin.commands().register(this, commandId, name, aliases, permission, description, handler, null)) commandIds.add(commandId);
    }

    protected final void store(PlayerData.Store store) {
        plugin.data().register(this, store);
    }

    /** A toggle for the settings menu; {@code action} is what a click does (usually the command). */
    protected final void toggle(String toggleId, boolean defaultOn, Consumer<Player> action) {
        plugin.toggles().define(this, toggleId, defaultOn, action);
    }

    protected final void placeholder(String key, Placeholders.Resolver resolver) {
        plugin.placeholders().add(this, key, resolver);
    }

    /** Blocks teleports while {@code blocks} holds; tells the player {@code messageKey}. */
    protected final void restriction(Predicate<Player> blocks, String messageKey) {
        plugin.restrictions().add(this, blocks, messageKey);
    }

    // ── Messages ──────────────────────────────────────────────────────────

    public final void msg(CommandSender to, String key, Object... placeholders) {
        plugin.messages().send(this, to, key, Messages.map(placeholders));
    }

    public final void msg(CommandSender to, String key, Map<String, ?> placeholders) {
        plugin.messages().send(this, to, key, placeholders);
    }

    /** Sends message {@code key} (with its sound) to each receiver. */
    public final void broadcast(Iterable<? extends CommandSender> to, String key, Map<String, ?> placeholders) {
        plugin.messages().send(this, to, key, placeholders);
    }

    /** Sends the configured usage line of a command. */
    protected final void usage(CommandSender to, String commandId) {
        msg(to, "usage", "usage", plugin.commands().usage(commandId));
    }

    /** The sender as a player, or null after telling them only players can do this. */
    protected final Player player(CommandSender sender) {
        if (sender instanceof Player p) return p;
        msg(sender, "players-only");
        return null;
    }

    /** An online player by name, or null after telling the sender. Respects vanish. */
    protected final Player target(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) target = Bukkit.getPlayer(name);
        if (target == null || !Visibility.knows(sender, target)) {
            msg(sender, "player-not-found", "player", name);
            return null;
        }
        return target;
    }

    /**
     * A list the server owner wrote in this entry, or empty. Unlike getStringList it never falls
     * back to the jar's copy, so deleting a reward's commands really removes them.
     */
    protected static List<String> ownList(org.bukkit.configuration.ConfigurationSection s, String path) {
        return s.get(path, null) instanceof List<?> ? s.getStringList(path) : List.of();
    }

    /** Online player names the sender can see, for tab completion. */
    protected static List<String> playerNames(CommandSender sender) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (Visibility.listed(sender, p)) out.add(p.getName());
        return out;
    }

    /** True when the player's data is loaded; otherwise tells them to wait. */
    protected final boolean ready(Player player) {
        if (plugin.data().isLoaded(player.getUniqueId())) return true;
        msg(player, "data-loading");
        return false;
    }

    /** Flips a toggle and tells the player {@code onKey}/{@code offKey}. Null while loading. */
    protected final Boolean flip(Player player, String toggleId, String onKey, String offKey) {
        Boolean now = plugin.toggles().flip(player, toggleId);
        if (now == null) msg(player, "data-loading");
        else msg(player, now ? onKey : offKey);
        return now;
    }

    // ── Menus ─────────────────────────────────────────────────────────────

    public final MenuFile menu(String name) {
        return menus.get(name);
    }

    /** Opens gui/&lt;name&gt;.yml for the player, filled in by {@code builder}. */
    public final Menu open(Player player, String name, Consumer<Menu> builder) {
        MenuFile file = menus.get(name);
        if (file == null) {
            msg(player, "menu-missing", "menu", Files.menuPath(id, name));
            return null;
        }
        Menu menu = new Menu(this, file, player, builder);
        menu.open();
        return menu;
    }
}
