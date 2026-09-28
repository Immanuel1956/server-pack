package com.vexorstudios.vexcore.features.milestones;

import com.vexorstudios.vexcore.core.Claims;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.gui.Actions;
import com.vexorstudios.vexcore.gui.Slots;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time rewards unlocked by a growing number (time played, player kills). Each reward is
 * claimed once, recorded at once, and only after the player's data loaded. The reward key is
 * what gets recorded, so renaming a key makes that reward claimable again.
 */
public abstract class MilestoneFeature extends Feature {

    record Reward(String key, long required, double money, List<String> commands, List<String> display) {
    }

    private Claims claims;
    private List<Reward> rewards = List.of();
    private final Map<UUID, Integer> notified = new ConcurrentHashMap<>();

    /** The player's current value (seconds played, kills...). Player's thread. */
    protected abstract long progress(Player player);

    /** The requirement of a reward section, in the same unit. -1 if invalid. */
    protected abstract long required(ConfigurationSection reward);

    /** A value written out for players. */
    protected abstract String show(long value);

    @Override
    protected void enable() {
        List<Reward> list = new ArrayList<>();
        ConfigurationSection root = config().getConfigurationSection("rewards");
        if (root != null) for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            long required = s == null ? -1 : required(s);
            if (required < 0) {
                problems().add("features/" + id() + "/config.yml: rewards." + key + " has no valid requirement");
                continue;
            }
            list.add(new Reward(key, required, Math.max(0, s.getDouble("money", 0)), ownList(s, "commands"), ownList(s, "display")));
        }
        list.sort((a, b) -> Long.compare(a.required, b.required));
        rewards = List.copyOf(list);
        if (menu(id()) != null) {
            int slots = Slots.parse(menu(id()).yml().get("reward-slots")).size();
            if (rewards.size() > slots) problems().add("features/" + id() + "/gui/" + id() + ".yml: " + rewards.size()
                    + " rewards but only " + slots + " reward-slots; the rest can't be seen or claimed");
        }
        claims = new Claims(this, id());
        store(claims);
        command(id(), this::command, (s, a) -> !s.hasPermission("vexcore." + id() + ".admin") ? List.of() : a.length == 1 ? List.of("reset") : a.length == 2 && a[0].equalsIgnoreCase("reset") ? playerNames(s) : List.of());
        if (config().getBoolean("notify.enabled", true)) {
            every(Math.max(10, config().getInt("notify.interval-seconds", 60)) * 20L, this::notifyReady);
        }
    }

    private List<Reward> claimable(Player player) {
        long have = progress(player);
        List<Reward> out = new ArrayList<>();
        for (Reward r : rewards) if (have >= r.required && !claims.of(player.getUniqueId()).contains(r.key)) out.add(r);
        return out;
    }

    private void notifyReady() {
        notified.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.data().isLoaded(player.getUniqueId())) continue;
            Scheduler.entity(player, () -> {
                int ready = claimable(player).size();
                Integer before = notified.put(player.getUniqueId(), ready);
                if (ready > 0 && (before == null || ready > before)) msg(player, "notify", "ready", ready);
            });
        }
    }

    @Override
    protected void loaded(Player player) {
        notified.remove(player.getUniqueId());
    }

    private void command(CommandSender sender, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            if (!sender.hasPermission("vexcore." + id() + ".admin")) {
                msg(sender, "no-permission", "permission", "vexcore." + id() + ".admin");
                return;
            }
            if (args.length < 2) {
                usage(sender, id());
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target == null) {
                msg(sender, "unknown-player", "player", args.length < 2 ? "?" : args[1]);
                return;
            }
            claims.reset(target.getUniqueId());
            msg(sender, "reset", "player", target.getName());
            return;
        }
        Player player = player(sender);
        if (player != null) openMenu(player);
    }

    private void claim(Player player, Reward r) {
        if (!claimable(player).contains(r)) return;
        // One account per network gets each reward (alts can't claim it again).
        var network = com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.claim(player, id(), r.key, 0);
        if (network != com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.ALLOWED) {
            com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.deny(player, network, id());
            return;
        }
        if (!claims.claim(player, r.key)) {
            msg(player, "data-loading");
            return;
        }
        if (r.money > 0 && !plugin.money().deposit(player, r.money)) {
            plugin.getLogger().warning(player.getName() + " claimed " + id() + " reward '" + r.key + "' but "
                    + r.money + " could not be paid (no economy, frozen or at the maximum balance).");
            msg(player, "economy-missing");
        }
        Map<String, Object> ph = Map.of("player", player.getName(), "required", show(r.required), "reward", r.key);
        Actions.run(player, r.commands, ph, null);
        msg(player, "claimed", ph);
    }

    private void openMenu(Player player) {
        if (!plugin.data().isLoaded(player.getUniqueId())) {
            msg(player, "data-loading");
            return;
        }
        open(player, id(), menu -> {
            long have = progress(player);
            List<Integer> slots = Slots.parse(menu.file().yml().get("reward-slots"));
            int claimed = 0;
            int readyCount = 0;
            Reward next = null;
            for (Reward r : rewards) {
                if (claims.of(player.getUniqueId()).contains(r.key)) claimed++;
                else if (have >= r.required) readyCount++;
                else if (next == null) next = r;
            }
            menu.with("progress", show(have)).with("claimed", claimed).with("total", rewards.size()).with("ready", readyCount)
                    .with("next", next == null ? config().getString("no-next", "-") : show(next.required - have));
            for (int i = 0; i < Math.min(slots.size(), rewards.size()); i++) {
                Reward r = rewards.get(i);
                boolean taken = claims.of(player.getUniqueId()).contains(r.key);
                String state = taken ? "claimed" : have >= r.required ? "ready" : "locked";
                Map<String, Object> ph = new HashMap<>();
                ph.put("required", show(r.required));
                ph.put("left", show(Math.max(0, r.required - have)));
                ph.put("rewards", String.join("\n", r.display.isEmpty() ? List.of(config().getString("no-rewards", "&f- &7none")) : r.display));
                menu.place(state, slots.get(i), ph, c -> {
                    switch (state) {
                        case "ready" -> {
                            claim(player, r);
                            menu.refresh();
                        }
                        case "locked" -> msg(player, "locked", "left", show(Math.max(0, r.required - progress(player))));
                        default -> msg(player, "already-claimed");
                    }
                });
            }
        });
    }
}
