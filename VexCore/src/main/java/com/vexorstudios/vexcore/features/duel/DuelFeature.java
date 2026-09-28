package com.vexorstudios.vexcore.features.duel;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.SafeSpot;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.stats.StatsFeature;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /1v1 &lt;player&gt; [wager] [world] challenges a player. On /1v1 accept both wagers are taken,
 * both players are dropped at a random safe spot a few blocks apart and a countdown runs
 * (no damage, pearls or elytra until it ends). The first death ends it: the winner gets both
 * wagers. Leaving counts as losing.
 */
public final class DuelFeature extends Feature implements Listener {

    record Challenge(UUID from, String world, double wager, long expires) {
    }

    static final class Fight {
        final UUID a, b;
        final double wager;
        volatile boolean started;
        volatile boolean placed;

        Fight(UUID a, UUID b, double wager) {
            this.a = a;
            this.b = b;
            this.wager = wager;
        }

        UUID other(UUID id) {
            return id.equals(a) ? b : a;
        }
    }

    private final Map<UUID, Challenge> challenges = new ConcurrentHashMap<>(); // target -> challenge
    private final Map<UUID, Fight> fights = new ConcurrentHashMap<>();

    @Override
    protected void enable() {
        listen(this);
        restriction(p -> fights.containsKey(p.getUniqueId()), "restricted");
        command("duel", this::command, (s, a) -> {
            if (a.length == 1) {
                List<String> out = new ArrayList<>(List.of("accept", "deny"));
                out.addAll(playerNames(s));
                return out;
            }
            return a.length == 2 || a.length == 3 ? worlds() : List.of();
        });
    }

    @Override
    protected void prepareShutdown() { disable(); }

    @Override
    protected void disable() {
        // A reload ends running fights without a winner: both get their wager back.
        for (Fight f : new ArrayList<>(fights.values())) end(f, null);
    }

    private List<String> worlds() {
        ConfigurationSection w = config().getConfigurationSection("worlds");
        return w == null ? List.of() : new ArrayList<>(w.getKeys(false));
    }

    private void command(CommandSender sender, String label, String[] args) {
        Player p = player(sender);
        if (p == null) return;
        if (args.length == 0) {
            msg(p, "usage");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> accept(p);
            case "deny" -> {
                Challenge c = challenges.remove(p.getUniqueId());
                if (c == null) {
                    msg(p, "no-challenge");
                    return;
                }
                msg(p, "denied");
                Player from = Bukkit.getPlayer(c.from);
                if (from != null) msg(from, "denied-by", "player", p.getName());
            }
            default -> challenge(p, args);
        }
    }

    private void challenge(Player p, String[] args) {
        Player target = target(p, args[0]);
        if (target == null) return;
        if (target.equals(p)) {
            msg(p, "self");
            return;
        }
        if (fights.containsKey(p.getUniqueId()) || fights.containsKey(target.getUniqueId())) {
            msg(p, "already-fighting");
            return;
        }
        if (plugin.restrictions().check(p) != null) {
            msg(p, "restricted");
            return;
        }
        // After the name: a wager, a world, or both, in any order ("/1v1 Steve end", "/1v1 Steve 500 end").
        double wager = 0;
        String world = config().getString("default-world", "overworld");
        for (int i = 1; i < Math.min(args.length, 3); i++) {
            String arg = args[i].toLowerCase(Locale.ROOT);
            if (config().getConfigurationSection("worlds." + arg) != null) {
                world = arg;
                continue;
            }
            if (arg.equals("0")) continue; // no wager
            wager = Numbers.amount(args[i], 2);
            if (Double.isNaN(wager)) {
                msg(p, "wager-invalid");
                return;
            }
        }
        if (wager > 0) {
            if (!plugin.money().available()) {
                msg(p, "no-economy");
                return;
            }
            if (!plugin.money().has(p, wager)) {
                msg(p, "cannot-afford", "amount", plugin.money().format(wager));
                return;
            }
        }
        if (config().getConfigurationSection("worlds." + world) == null) {
            msg(p, "world-missing");
            return;
        }
        challenges.put(target.getUniqueId(), new Challenge(p.getUniqueId(), world, wager,
                System.currentTimeMillis() + config().getLong("expire-seconds", 60) * 1000));
        String cmd = "/" + plugin.commands().name("duel") + " accept";
        msg(p, "sent", "player", target.getName());
        msg(target, "received", "player", p.getName(), "command", cmd,
                "wager", wager > 0 ? plugin.money().format(wager) : config().getString("no-wager", "&7none"),
                "world", config().getString("worlds." + world + ".display", world));
    }

    private void accept(Player p) {
        Challenge c = challenges.remove(p.getUniqueId());
        Player from = c == null ? null : Bukkit.getPlayer(c.from);
        if (c == null || c.expires < System.currentTimeMillis()) {
            msg(p, c == null ? "no-challenge" : "expired");
            return;
        }
        if (from == null) {
            msg(p, "target-offline");
            return;
        }
        if (fights.containsKey(p.getUniqueId()) || fights.containsKey(from.getUniqueId())) {
            msg(p, "already-fighting");
            return;
        }
        if (plugin.restrictions().check(p) != null || plugin.restrictions().check(from) != null) {
            msg(p, "target-restricted", "player", plugin.restrictions().check(p) != null ? p.getName() : from.getName());
            return;
        }
        // Escrow: both wagers are taken now; a failed second one gives the first back.
        if (c.wager > 0) {
            if (!plugin.money().withdraw(from, c.wager)) {
                msg(p, "cannot-afford", "amount", plugin.money().format(c.wager));
                return;
            }
            if (!plugin.money().withdraw(p, c.wager)) {
                plugin.money().deposit(from, c.wager);
                msg(p, "cannot-afford", "amount", plugin.money().format(c.wager));
                return;
            }
        }
        Fight fight = new Fight(from.getUniqueId(), p.getUniqueId(), c.wager);
        fights.put(fight.a, fight);
        fights.put(fight.b, fight);
        // A fight can't last forever (someone hiding would trap the other): after max-seconds it
        // ends with nobody winning and both wagers go back.
        long max = config().getLong("max-seconds", 600);
        if (max > 0) track(Scheduler.globalLater(() -> {
            if (fights.get(fight.a) != fight) return;
            for (UUID id : List.of(fight.a, fight.b)) {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) msg(pl, "timed-out");
            }
            end(fight, null);
        }, max * 20));
        ConfigurationSection w = config().getConfigurationSection("worlds." + c.world);
        World.Environment env = c.world.contains("end") ? World.Environment.THE_END
                : c.world.contains("nether") ? World.Environment.NETHER : World.Environment.NORMAL;
        SafeSpot.Area area = SafeSpot.Area.of(w, env, config().getStringList("blocked-biomes"), config().getInt("generated-attempts", 20), config().getConfigurationSection("search"));
        if (area == null) {
            msg(p, "world-missing");
            end(fight, null);
            return;
        }
        msg(p, "accepted");
        msg(from, "accepted");
        msg(p, "searching");
        msg(from, "searching");
        SafeSpot.find(area).thenCompose(base -> {
            if (base == null) return java.util.concurrent.CompletableFuture.<Location[]>completedFuture(null);
            return findPartner(area, base, 0).thenApply(other -> other == null ? null : new Location[]{base, other});
        }).whenComplete((spots, error) -> {
            if (fights.get(fight.a) != fight || !isEnabled()) return;
            if (spots == null || error != null) {
                msg(p, "not-found"); msg(from, "not-found"); end(fight, null); return;
            }
            Scheduler.global(() -> start(fight, spots[0], spots[1]));
        });
    }

    private java.util.concurrent.CompletableFuture<Location> findPartner(SafeSpot.Area area, Location base, int attempt) {
        if (attempt >= 16 || !isEnabled()) return java.util.concurrent.CompletableFuture.completedFuture(null);
        int min = Math.max(2, Math.min(128, config().getInt("start.min-distance", 6)));
        int distance = Math.max(min, Math.min(128, config().getInt("start.distance", 20)));
        double angle = (attempt % 8) * Math.PI / 4;
        int radius = attempt < 8 ? distance : min + 2;
        int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
        int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
        return SafeSpot.at(area, x, z).thenCompose(other -> {
            if (other != null) {
                double dx = other.getX() - base.getX(), dz = other.getZ() - base.getZ();
                if (dx * dx + dz * dz >= min * min) return java.util.concurrent.CompletableFuture.completedFuture(other);
            }
            return findPartner(area, base, attempt + 1);
        });
    }

    private void start(Fight fight, Location base, Location other) {
        try {
            begin(fight, base, other);
        } catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "1v1 could not start; wagers returned", error);
            end(fight, null);
        }
    }

    private void begin(Fight fight, Location base, Location other) {
        if (fights.get(fight.a) != fight) return; // called off while the spot was searched (quit, reload, timeout)
        Player a = Bukkit.getPlayer(fight.a), b = Bukkit.getPlayer(fight.b);
        if (a == null || b == null) {
            end(fight, a == null ? fight.b : fight.a);
            return;
        }
        // Tagged in combat (or frozen...) while the spot was searched: no escape through a 1v1.
        for (Player pl : List.of(a, b)) {
            var rule = plugin.restrictions().checkOthers(pl, this);
            if (rule != null) {
                rule.owner().msg(pl, rule.message());
                msg(pl == a ? b : a, "cancelled");
                end(fight, null);
                return;
            }
        }
        // Under the Nether's roof there's only room right above the ground.
        int height = base.getWorld().getEnvironment() == World.Environment.NETHER ? 0 : config().getInt("start.spawn-height", 5);
        Location la = base.clone().add(0, height, 0), lb = other.clone().add(0, height, 0);
        la.setDirection(lb.toVector().subtract(la.toVector()));
        lb.setDirection(la.toVector().subtract(lb.toVector()));
        int seconds = Math.max(0, Math.min(60, config().getInt("start.countdown-seconds", 5)));
        java.util.concurrent.CompletableFuture<Boolean> ta = place(fight, a, b, la, seconds);
        java.util.concurrent.CompletableFuture<Boolean> tb = place(fight, b, a, lb, seconds);
        ta.thenCombine(tb, (okA, okB) -> okA && okB).whenComplete((ok, error) -> {
            if (fights.get(fight.a) != fight || !isEnabled()) return;
            if (error != null || !Boolean.TRUE.equals(ok)) {
                msg(a, "cancelled"); msg(b, "cancelled"); end(fight, null); return;
            }
            fight.placed = true;
            Scheduler.global(() -> countdown(fight, seconds));
        });
    }

    private java.util.concurrent.CompletableFuture<Boolean> place(Fight fight, Player p, Player enemy, Location to, int seconds) {
        var result = new java.util.concurrent.CompletableFuture<Boolean>();
        Scheduler.entity(p, () -> {
            if (fights.get(fight.a) != fight) { result.complete(false); return; }
            p.teleportAsync(to).whenComplete((ok, error) -> {
                if (error != null || !Boolean.TRUE.equals(ok)) { result.complete(false); return; }
                Scheduler.entity(p, () -> {
                    if (fights.get(fight.a) != fight) { result.complete(false); return; }
                    if (config().getBoolean("start.slow-falling", true)) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                            (seconds + Math.max(1, config().getInt("start.slow-falling-extra-seconds", 3))) * 20, 0, false, false, true));
                    msg(p, "started", "player", enemy.getName());
                    result.complete(true);
                }, () -> result.complete(false));
            });
        }, () -> result.complete(false));
        return result.completeOnTimeout(false, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void countdown(Fight fight, int seconds) {
        int[] left = {seconds};
        Scheduler.Task[] task = {Scheduler.NOOP};
        task[0] = track(Scheduler.globalTimer(() -> {
            if (fights.get(fight.a) != fight) { task[0].cancel(); return; }
            int remaining = left[0]--;
            boolean go = remaining <= 0;
            for (UUID id : List.of(fight.a, fight.b)) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                Scheduler.entity(p, () -> {
                    if (fights.get(fight.a) != fight) return;
                    Map<String, Object> ph = Map.of("seconds", remaining);
                    p.showTitle(Title.title(Text.parse(config().getString(go ? "start.go-title" : "start.title", "&#FFD900&l%seconds%"), p, ph),
                            Text.parse(config().getString(go ? "start.go-subtitle" : "start.subtitle", ""), p, ph),
                            com.vexorstudios.vexcore.core.Messages.times(config().getConfigurationSection("start.title-times"))));
                    var sound = com.vexorstudios.vexcore.core.SoundSpec.of(config().get(go ? "start.go-sound" : "start.countdown-sound"));
                    // The countdown ticks only on its first second unless sounds.ticking is on.
                    if (sound != null && (go || remaining == seconds || com.vexorstudios.vexcore.core.SoundSpec.ticking())) sound.play(p);
                });
            }
            if (go) { fight.started = true; task[0].cancel(); }
        }, 1, 20));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
        Fight f = fights.get(event.getPlayer().getUniqueId());
        if (f == null || !f.placed || f.started || !config().getBoolean("start.freeze-movement", true)) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
            Location to = event.getTo().clone();
            to.setX(event.getFrom().getX()); to.setZ(event.getFrom().getZ()); event.setTo(to);
        }
    }

    /** Ends a fight. winner null = nobody won: both wagers go back. */
    private synchronized void end(Fight fight, UUID winner) {
        if (fights.get(fight.a) != fight || fights.get(fight.b) != fight) return;
        fights.remove(fight.a, fight); fights.remove(fight.b, fight);
        if (winner == null) {
            if (fight.wager > 0) for (UUID id : List.of(fight.a, fight.b)) give(id, fight.wager);
            return;
        }
        UUID loser = fight.other(winner);
        double pot = fight.wager * 2;
        if (pot > 0) give(winner, pot);
        Player w = Bukkit.getPlayer(winner), l = Bukkit.getPlayer(loser);
        if (w != null) msg(w, pot > 0 ? "won-wager" : "won", "amount", plugin.money().format(pot));
        if (l != null) msg(l, "lost");
        if (plugin.features().get("stats") instanceof StatsFeature stats) {
            stats.add(winner, StatsFeature.Stat.DUEL_WINS, 1);
            stats.add(loser, StatsFeature.Stat.DUEL_LOSSES, 1);
        }
    }

    /** Pays out escrow; a payment that can't go through is logged so staff can give it by hand. */
    private void give(UUID player, double amount) {
        if (!plugin.money().deposit(Bukkit.getOfflinePlayer(player), amount)) {
            plugin.getLogger().severe("1v1: could not pay " + plugin.money().format(amount) + " to " + player
                    + " (frozen, at the balance limit, or no economy). Give it with /eco give.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) // a revived player didn't lose
    public void onDeath(PlayerDeathEvent event) {
        Fight f = fights.get(event.getEntity().getUniqueId());
        if (f != null) end(f, f.other(event.getEntity().getUniqueId()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Fight f = fights.get(event.getPlayer().getUniqueId());
        if (f != null) end(f, f.started ? f.other(event.getPlayer().getUniqueId()) : null);
        challenges.remove(event.getPlayer().getUniqueId());
    }

    private boolean waiting(Player p) {
        Fight f = fights.get(p.getUniqueId());
        return f != null && !f.started;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!config().getBoolean("start.block-damage", true)) return;
        if (event.getEntity() instanceof Player p && waiting(p)) event.setCancelled(true);
        // Nor can a waiting player hurt anyone while they can't be hurt themselves.
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent e) {
            org.bukkit.entity.Entity d = e.getDamager();
            Player attacker = d instanceof Player pl ? pl
                    : d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player pl ? pl : null;
            if (attacker != null && waiting(attacker)) event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearl(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl && event.getEntity().getShooter() instanceof Player p && waiting(p)
                && config().getBoolean("start.block-pearls", true)) {
            event.setCancelled(true);
            msg(p, "start-blocked");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && event.getEntity() instanceof Player p && waiting(p) && config().getBoolean("start.block-elytra", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!fights.containsKey(event.getPlayer().getUniqueId())) return;
        String label = com.vexorstudios.vexcore.core.Commands.label(event.getMessage());
        // The duel command under any of its names (so "/duel accept" works as well as "/1v1 accept").
        var section = plugin.commands().section("duel");
        boolean duelCommand = label.equals(plugin.commands().name("duel"))
                || (section != null && section.getStringList("aliases").stream().anyMatch(a -> a.equalsIgnoreCase(label)));
        if (duelCommand || config().getStringList("start.allowed-commands").contains(label)) return;
        event.setCancelled(true);
        msg(event.getPlayer(), "restricted");
    }
}
