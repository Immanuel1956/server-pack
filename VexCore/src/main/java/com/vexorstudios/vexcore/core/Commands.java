package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Registers every command from commands.yml. Each command has a fixed id (what the code uses)
 * and a configurable name, aliases, permission, description and usage; {@code enabled: false}
 * keeps it off the server. A disabled feature registers nothing. Names taken from another plugin
 * are handed back when ours are removed. Permissions are checked by VexCore (the configured
 * no-permission message); with hide-commands-without-permission they are also given to Paper,
 * which then hides the command from players without it.
 */
public final class Commands {

    /** What a command does. */
    @FunctionalInterface
    public interface Handler {
        void run(CommandSender sender, String label, String[] args);
    }

    /** Tab completion. Returning null lists online players. */
    @FunctionalInterface
    public interface Completer {
        List<String> complete(CommandSender sender, String[] args);
    }

    private static final String NAMESPACE = "vexcore";

    private final VexCore plugin;
    private YamlConfiguration yml = new YamlConfiguration();
    private final Map<String, Registered> active = new LinkedHashMap<>();
    private final Map<String, Command> displaced = new HashMap<>();

    public Commands(VexCore plugin) {
        this.plugin = plugin;
    }

    public void load(YamlConfiguration yml) {
        this.yml = yml;
    }

    public ConfigurationSection section(String id) {
        return yml.getConfigurationSection("commands." + id);
    }

    /** A string from commands.yml; missing keys use the jar's commands.yml, then {@code fallback}. */
    private static String value(ConfigurationSection s, String key, String fallback) {
        String v = s == null ? null : s.getString(key);
        return v == null ? fallback : v;
    }

    /** The name a command is typed with right now (after renaming). */
    public String name(String id) {
        Registered r = active.get(id);
        if (r != null) return r.getName();
        return value(section(id), "name", id);
    }

    /** The configured usage line, with %command% replaced by the current name. */
    public String usage(String id) {
        return value(section(id), "usage", "/%command%").replace("%command%", name(id));
    }

    public boolean isActive(String id) {
        return active.containsKey(id);
    }

    /** The command word of a typed command ("/Home set" -> "home"), without splitting the rest. */
    public static String label(String message) {
        int start = message.startsWith("/") ? 1 : 0;
        int end = message.indexOf(' ', start);
        return (end < 0 ? message.substring(start) : message.substring(start, end)).toLowerCase(java.util.Locale.ROOT);
    }

    public Collection<Registered> active() {
        return active.values();
    }

    /** Registers command {@code id} for {@code owner} (null = the core). False if disabled. */
    public boolean register(Feature owner, String id, Handler handler, Completer completer) {
        ConfigurationSection s = section(id);
        if (s != null && s.contains("enabled") && !s.getBoolean("enabled")) return false;
        String name = value(s, "name", id).toLowerCase(Locale.ROOT).trim();
        if (name.isEmpty() || name.contains(" ")) {
            plugin.getLogger().warning("commands.yml: '" + id + "' has an invalid name '" + name + "', using '" + id + "'.");
            name = id;
        }
        List<String> aliases = new ArrayList<>();
        if (s != null) for (String a : s.getStringList("aliases")) {
            String alias = a.toLowerCase(Locale.ROOT).trim();
            if (!alias.isEmpty() && !alias.contains(" ") && !alias.equals(name)) aliases.add(alias);
        }
        String permission = value(s, "permission", "vexcore." + id);
        String description = value(s, "description", "");
        Registered cmd = new Registered(owner, id, name, description, aliases, permission, handler, completer);
        Registered old = active.put(id, cmd);
        if (old != null) remove(old);
        put(cmd);
        return true;
    }

    /** Shortcuts: new names that run an existing command with fixed arguments. */
    public void registerShortcuts() {
        ConfigurationSection shortcuts = yml.getConfigurationSection("shortcuts");
        if (shortcuts == null) return;
        for (String key : shortcuts.getKeys(false)) {
            String target = shortcuts.getString(key, "").trim();
            if (target.startsWith("/")) target = target.substring(1);
            if (target.isEmpty()) continue;
            String run = target;
            Registered cmd = new Registered(null, "shortcut:" + key, key.toLowerCase(Locale.ROOT), "Runs /" + run,
                    List.of(), "", (sender, label, args) -> {
                String line = run + (args.length == 0 ? "" : " " + String.join(" ", args));
                if (sender instanceof Player player) player.chat("/" + line); // fires command events (combat)
                else Bukkit.dispatchCommand(sender, line);
            }, null);
            Registered old = active.put(cmd.id, cmd);
            if (old != null) remove(old);
            put(cmd);
        }
    }

    public void unregister(Feature owner) {
        active.values().removeIf(cmd -> {
            if (cmd.owner != owner) return false;
            remove(cmd);
            return true;
        });
    }

    public void unregisterAll() {
        for (Registered cmd : active.values()) remove(cmd);
        active.clear();
    }

    private void put(Registered cmd) {
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        cmd.register(map);
        for (String label : cmd.labels()) {
            Command previous = known.put(label, cmd);
            if (previous != null && previous != cmd && !(previous instanceof Registered)) {
                displaced.putIfAbsent(label, previous);
            }
            known.put(NAMESPACE + ":" + label, cmd);
        }
    }

    private void remove(Registered cmd) {
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        for (String label : cmd.labels()) {
            known.remove(label, cmd);
            known.remove(NAMESPACE + ":" + label, cmd);
            Command previous = displaced.remove(label);
            if (previous == null) previous = namespaced(known, label);
            if (previous != null && !known.containsKey(label)) known.put(label, previous);
        }
        cmd.unregister(map);
    }

    /** Another plugin's "plugin:label" command, for plugins that loaded after VexCore took the label. */
    private static Command namespaced(Map<String, Command> known, String label) {
        for (Map.Entry<String, Command> e : known.entrySet()) {
            if (e.getKey().endsWith(":" + label) && !e.getKey().startsWith(NAMESPACE + ":")
                    && !(e.getValue() instanceof Registered)) return e.getValue();
        }
        return null;
    }

    /** Sends the new command list to every player. Only needed after the server has started. */
    public void sync() {
        try {
            Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException | RuntimeException e) {
            for (Player player : Bukkit.getOnlinePlayers()) player.updateCommands();
        }
    }

    /** A command as the server sees it. */
    public final class Registered extends Command {
        final Feature owner;
        final String id;
        final String node;
        final Handler handler;
        final Completer completer;

        Registered(Feature owner, String id, String name, String description, List<String> aliases,
                   String permission, Handler handler, Completer completer) {
            super(name, description, "/" + name, aliases);
            this.owner = owner;
            this.id = id;
            this.node = permission == null ? "" : permission.trim();
            this.handler = handler;
            this.completer = completer;
            // hide-commands-without-permission: Paper then leaves the command out of the player's
            // command list and tab (and answers "Unknown command"); off, it shows and VexCore sends
            // the configured no-permission message.
            if (!this.node.isEmpty() && plugin.settings().getBoolean("hide-commands-without-permission", true)) setPermission(this.node);
        }

        public String id() {
            return id;
        }

        public Feature owner() {
            return owner;
        }

        private boolean wantsPlayer(int index) {
            String[] parts = usage(id).trim().split("\\s+");
            int at = index + 1; // parts[0] is the command itself
            return index >= 0 && at < parts.length && parts[at].toLowerCase(Locale.ROOT).contains("player");
        }

        List<String> labels() {
            List<String> labels = new ArrayList<>(getAliases().size() + 1);
            labels.add(getName());
            labels.addAll(getAliases());
            return labels;
        }

        boolean allowed(CommandSender sender) {
            return node.isEmpty() || sender.hasPermission(node);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!allowed(sender)) {
                plugin.messages().send(owner, sender, "no-permission", Messages.map("permission", node));
                return true;
            }
            try {
                handler.run(sender, label, args);
            } catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE, "Command /" + label + " failed", error);
                plugin.messages().send(owner, sender, "command-error", Map.of());
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!allowed(sender)) return List.of();
            List<String> options = null;
            try {
                if (completer != null) options = completer.complete(sender, args);
            } catch (RuntimeException ignored) {
            }
            if (options == null) {
                options = new ArrayList<>();
                // Player names only where the usage line asks for a player ("/msg <player> ...").
                if (wantsPlayer(args.length - 1)) for (Player p : Bukkit.getOnlinePlayers()) {
                    if (Visibility.listed(sender, p)) options.add(p.getName());
                }
            }
            String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(last)) out.add(option);
            return out;
        }
    }
}
