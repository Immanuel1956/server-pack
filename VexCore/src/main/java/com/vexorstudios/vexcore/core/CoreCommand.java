package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code /vexcore} (renamable in commands.yml): help, reload, features, version, import. */
public final class CoreCommand {

    private final VexCore plugin;

    public CoreCommand(VexCore plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.commands().register(null, "vexcore", this::run, this::complete);
    }

    private void send(CommandSender to, String key, Object... kv) {
        plugin.messages().send(null, to, key, Messages.map(kv));
    }

    private void run(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload", "rl" -> Scheduler.global(() -> plugin.reload(sender));
            case "features", "feature", "list" -> features(sender);
            case "version", "ver" -> send(sender, "core-version",
                    "version", plugin.getPluginMeta().getVersion(), "database", plugin.database().type().name());
            case "import" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("setupcore")) {
                    send(sender, "usage", "usage", "/" + label + " import setupcore");
                } else if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    send(sender, "import-confirm", "command", "/" + label + " import setupcore confirm",
                            "folder", SetupCoreImport.folder(plugin).getPath());
                } else {
                    Scheduler.global(() -> SetupCoreImport.run(plugin, sender));
                }
            }
            default -> send(sender, "core-help", "command", label);
        }
    }

    private void features(CommandSender sender) {
        send(sender, "features-header", "active", plugin.features().active().size(),
                "total", plugin.features().available().size());
        for (String id : plugin.features().available()) {
            Feature f = plugin.features().get(id);
            if (f != null) {
                send(sender, "features-on", "feature", id, "commands", f.commandIds().size(), "menus", f.menus().size());
            } else if (plugin.features().failed().containsKey(id)) {
                send(sender, "features-failed", "feature", id, "error", plugin.features().failed().get(id));
            } else {
                send(sender, "features-off", "feature", id);
            }
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("help", "reload", "features", "version", "import");
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) return List.of("setupcore");
        if (args.length == 3 && args[0].equalsIgnoreCase("import")) return List.of("confirm");
        return List.of();
    }

    /** The reload report. */
    public static void report(VexCore plugin, CommandSender to, long millis, List<String> problems) {
        Messages m = plugin.messages();
        for (Feature f : plugin.features().active()) {
            m.send(null, to, "reload-feature", Messages.map("feature", f.id(),
                    "commands", f.commandIds().size(), "menus", f.menus().size()));
        }
        for (Map.Entry<String, String> e : plugin.features().failed().entrySet()) {
            m.send(null, to, "reload-feature-failed", Messages.map("feature", e.getKey(), "error", e.getValue()));
        }
        for (String problem : problems) m.send(null, to, "reload-problem", Messages.map("problem", problem));
        int commands = plugin.commands().active().size();
        m.send(null, to, "reload-done", Messages.map("time", millis,
                "features", plugin.features().active().size(),
                "disabled", plugin.features().available().size() - plugin.features().active().size(),
                "commands", commands, "problems", problems.size()));
    }
}
