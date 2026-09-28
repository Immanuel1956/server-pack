package com.vexorstudios.vexcore.features.events;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Messages;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.StyledText;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.core.Time;
import com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature;
import com.vexorstudios.vexcore.gui.Actions;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server events, one at a time, started by hand (/event start &lt;type&gt;) or automatically every
 * auto.interval:
 * <ul>
 *   <li>CHATGAME: first to type the answer (unscramble a word, solve a sum, copy a code) wins.</li>
 *   <li>MINING / MOBS / FISHING / PVP: most blocks mined (blocks placed during the event don't
 *       count), mobs killed (spawner mobs optional), fish caught or players killed (kill farming
 *       doesn't count) within the time; the top places win.</li>
 *   <li>KOTH: stand alone on the hill (/event koth set [radius]) for capture-seconds.</li>
 * </ul>
 * A boss bar shows the event while it runs.
 */
public final class EventsFeature extends Feature implements Listener {

    public enum Type {CHATGAME, MINING, MOBS, FISHING, PVP, KOTH}

    /** The event that is running. */
    private static final class Running {
        final Type type;
        final long started = System.currentTimeMillis();
        final long ends;
        final Map<UUID, Integer> scores = new ConcurrentHashMap<>();
        final Map<UUID, String> names = new ConcurrentHashMap<>();
        final Set<String> placed = ConcurrentHashMap.newKeySet();
        final AtomicBoolean won = new AtomicBoolean();
        String answer;
        String question;
        UUID capturer;
        int progress;

        Running(Type type, long ends) {
            this.type = type;
            this.ends = ends;
        }
    }

    private volatile Running running;
    private volatile long nextAuto;
    private BossBar bar;
    private String barTitle;
    private File file;
    /** Read once per reload (and when /event koth set changes the hill). */
    private Set<Material> miningBlocks = Set.of();
    private volatile Location hill;
    private volatile double radius = 5;

    @Override
    protected void enable() {
        listen(this);
        file = plugin.files().data("events.yml");
        loadHill();
        Set<Material> blocks = new HashSet<>();
        for (String name : typeConfig(Type.MINING).getStringList("blocks")) {
            Material m = Material.matchMaterial(name);
            if (m == null) problems().add(com.vexorstudios.vexcore.core.Files.configPath(id()) + ": types.mining.blocks: unknown block '" + name + "'");
            else blocks.add(m);
        }
        miningBlocks = Set.copyOf(blocks);
        scheduleAuto();
        every(20, this::tick);
        command("event", this::command, (s, a) -> {
            if (!s.hasPermission("vexcore.events.admin")) return List.of();
            if (a.length == 1) return List.of("start", "stop", "koth");
            if (a.length == 2 && a[0].equalsIgnoreCase("start")) {
                List<String> out = new ArrayList<>();
                for (Type t : Type.values()) out.add(t.name().toLowerCase(Locale.ROOT));
                return out;
            }
            return a.length == 2 && a[0].equalsIgnoreCase("koth") ? List.of("set") : List.of();
        });
        placeholder("event_name", (p, a) -> running == null ? config().getString("words.none", "None") : typeName(running.type));
        placeholder("event_time", (p, a) -> running == null ? "-" : plugin.messages().time(Math.max(0, (running.ends - System.currentTimeMillis()) / 1000)));
        placeholder("event_next", (p, a) -> nextAuto <= 0 ? "-" : plugin.messages().time(Math.max(0, (nextAuto - System.currentTimeMillis()) / 1000)));
    }

    @Override
    protected void disable() {
        hideBar();
        running = null;
    }

    private String typeName(Type t) {
        return config().getString("types." + t.name().toLowerCase(Locale.ROOT) + ".name", t.name());
    }

    private ConfigurationSection typeConfig(Type t) {
        ConfigurationSection s = config().getConfigurationSection("types." + t.name().toLowerCase(Locale.ROOT));
        return s != null ? s : config().createSection("types." + t.name().toLowerCase(Locale.ROOT));
    }

    // ── Starting and ending ───────────────────────────────────────────────

    private void scheduleAuto() {
        long interval = Time.seconds(config().getString("auto.interval", "45m"));
        nextAuto = config().getBoolean("auto.enabled", true) && interval > 0 ? System.currentTimeMillis() + interval * 1000 : 0;
    }

    private void autoStart() {
        scheduleAuto();
        if (running != null || Bukkit.getOnlinePlayers().size() < config().getInt("auto.minimum-players", 3)) return;
        List<Type> types = new ArrayList<>();
        for (String raw : config().getStringList("auto.types")) {
            try {
                Type t = Type.valueOf(raw.toUpperCase(Locale.ROOT));
                if (t != Type.KOTH || hill() != null) types.add(t);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!types.isEmpty()) start(types.get(ThreadLocalRandom.current().nextInt(types.size())), Bukkit.getConsoleSender());
    }

    private boolean start(Type type, CommandSender by) {
        if (running != null) {
            msg(by, "already-running", "event", typeName(running.type));
            return false;
        }
        if (type == Type.KOTH && hill() == null) {
            msg(by, "no-hill");
            return false;
        }
        ConfigurationSection t = typeConfig(type);
        long seconds = Math.max(10, Time.seconds(t.getString("duration", type == Type.CHATGAME ? "60s" : "10m")));
        Running r = new Running(type, System.currentTimeMillis() + seconds * 1000);
        Map<String, Object> ph = new HashMap<>();
        ph.put("event", typeName(type));
        ph.put("time", plugin.messages().time(seconds));
        if (type == Type.CHATGAME) {
            chatQuestion(r);
            ph.put("question", r.question);
        }
        if (type == Type.KOTH) {
            Location hill = hill();
            ph.put("x", hill.getBlockX());
            ph.put("y", hill.getBlockY());
            ph.put("z", hill.getBlockZ());
            ph.put("world", hill.getWorld().getName());
            ph.put("capture", plugin.messages().time(captureSeconds()));
        }
        running = r;
        broadcast(Messages.everyone(), "start-" + type.name().toLowerCase(Locale.ROOT), ph);
        return true;
    }

    /** Ends the running event and pays the winners. */
    private void finish(Running r, boolean cancelled) {
        if (running != r) return;
        running = null;
        hideBar();
        if (cancelled) {
            broadcast(Messages.everyone(), "stopped", Map.of("event", typeName(r.type)));
            return;
        }
        if (r.type == Type.CHATGAME || r.type == Type.KOTH) {
            if (!r.won.get()) broadcast(Messages.everyone(), "no-winner", Map.of("event", typeName(r.type), "answer", r.answer == null ? "-" : r.answer));
            return;
        }
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(r.scores.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (top.isEmpty()) {
            broadcast(Messages.everyone(), "no-winner", Map.of("event", typeName(r.type), "answer", "-"));
            return;
        }
        List<String> lines = new ArrayList<>();
        int places = Math.min(top.size(), Math.max(1, config().getInt("places", 3)));
        for (int i = 0; i < places; i++) {
            Map.Entry<UUID, Integer> e = top.get(i);
            lines.add(Text.fill(config().getString("result-line", "&#FFD900#%place% &f%player% &7▷ &#FFD900%score%"),
                    Map.of("place", i + 1, "player", r.names.getOrDefault(e.getKey(), "?"), "score", e.getValue())));
            pay(e.getKey(), r.names.getOrDefault(e.getKey(), "?"), r.type, i + 1);
        }
        broadcast(Messages.everyone(), "results", Map.of("event", typeName(r.type), "results", String.join("\n", lines)));
    }

    /** Rewards for a place: the type's own rewards.<place>, else the shared rewards.<place>. */
    private void pay(UUID id, String name, Type type, int place) {
        ConfigurationSection s = typeConfig(type).getConfigurationSection("rewards." + place);
        if (s == null) s = config().getConfigurationSection("rewards." + place);
        if (s == null) return;
        ConfigurationSection reward = s;
        Player p = Bukkit.getPlayer(id);
        Map<String, Object> ph = Map.of("player", name, "place", place, "event", typeName(type));
        double money = Math.max(0, reward.getDouble("money", 0));
        if (p != null) {
            Scheduler.entity(p, () -> {
                if (money > 0) plugin.money().deposit(p, money);
                Actions.run(p, ownList(reward, "commands"), ph, null);
                msg(p, "you-won", "place", place, "money", plugin.money().format(money), "event", typeName(type));
            });
        } else {
            if (money > 0) plugin.money().deposit(Bukkit.getOfflinePlayer(id), money);
            for (String line : ownList(reward, "console-if-offline")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), Text.fill(line, ph));
            }
        }
    }

    // ── Every second ──────────────────────────────────────────────────────

    private void tick() {
        Running r = running;
        long now = System.currentTimeMillis();
        if (r == null) {
            if (nextAuto > 0 && now >= nextAuto) autoStart();
            return;
        }
        if (r.type == Type.KOTH) kothTick(r);
        if (running != r) return; // KOTH may have been won
        if (now >= r.ends) {
            finish(r, false);
            return;
        }
        updateBar(r);
    }

    private void updateBar(Running r) {
        if (!config().getBoolean("bossbar.enabled", true)) return;
        long left = Math.max(0, (r.ends - System.currentTimeMillis()) / 1000);
        Map<String, Object> ph = new HashMap<>();
        ph.put("event", typeName(r.type));
        ph.put("time", plugin.messages().time(left));
        Map.Entry<UUID, Integer> lead = null;
        for (Map.Entry<UUID, Integer> e : r.scores.entrySet()) if (lead == null || e.getValue() > lead.getValue()) lead = e;
        ph.put("leader", lead == null ? config().getString("words.nobody", "nobody") : r.names.getOrDefault(lead.getKey(), "?"));
        ph.put("score", lead == null ? 0 : lead.getValue());
        float progress = (float) Math.max(0, Math.min(1, (double) (r.ends - System.currentTimeMillis()) / Math.max(1, r.ends - r.started)));
        if (r.type == Type.KOTH) {
            ph.put("capturer", r.capturer == null ? config().getString("words.nobody", "nobody") : r.names.getOrDefault(r.capturer, "?"));
            ph.put("progress", r.progress * 100 / Math.max(1, captureSeconds()));
            progress = (float) Math.min(1, (double) r.progress / Math.max(1, captureSeconds()));
        }
        String title = Text.fill(config().getString("bossbar.titles." + r.type.name().toLowerCase(Locale.ROOT),
                config().getString("bossbar.titles.default", "&#FFD900&l%event% &7▷ &f%time%")), ph);
        if (bar == null) {
            BossBar.Color color;
            try {
                color = BossBar.Color.valueOf(config().getString("bossbar.color", "PURPLE").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                color = BossBar.Color.PURPLE;
            }
            bar = BossBar.bossBar(Component.empty(), 1f, color, BossBar.Overlay.PROGRESS);
            barTitle = null;
        }
        if (!title.equals(barTitle)) {
            barTitle = title;
            bar.name(Text.parse(title));
        }
        bar.progress(progress);
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
    }

    private void hideBar() {
        if (bar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        bar = null;
    }

    // ── Chat games ────────────────────────────────────────────────────────

    private void chatQuestion(Running r) {
        ConfigurationSection t = typeConfig(Type.CHATGAME);
        List<String> kinds = t.getStringList("games");
        String kind = kinds.isEmpty() ? "unscramble" : kinds.get(ThreadLocalRandom.current().nextInt(kinds.size())).toLowerCase(Locale.ROOT);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        switch (kind) {
            case "math" -> {
                int a = rnd.nextInt(5, 60), b = rnd.nextInt(2, 25);
                if (rnd.nextBoolean()) {
                    r.question = Text.fill(t.getString("questions.math-plus", "What is %a% + %b%?"), Map.of("a", a, "b", b));
                    r.answer = String.valueOf(a + b);
                } else {
                    int small = rnd.nextInt(2, 13);
                    r.question = Text.fill(t.getString("questions.math-times", "What is %a% x %b%?"), Map.of("a", a, "b", small));
                    r.answer = String.valueOf(a * small);
                }
            }
            case "type" -> {
                String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
                StringBuilder code = new StringBuilder();
                for (int i = 0; i < 8; i++) code.append(chars.charAt(rnd.nextInt(chars.length())));
                r.answer = code.toString();
                r.question = Text.fill(t.getString("questions.type", "Type %code%"), Map.of("code", r.answer));
            }
            default -> {
                List<String> words = t.getStringList("words");
                String word = words.isEmpty() ? "diamond" : words.get(rnd.nextInt(words.size())).toLowerCase(Locale.ROOT);
                String shuffled = word;
                for (int tries = 0; tries < 10 && shuffled.equals(word); tries++) {
                    List<Character> letters = new ArrayList<>();
                    for (char c : word.toCharArray()) letters.add(c);
                    java.util.Collections.shuffle(letters);
                    StringBuilder sb = new StringBuilder();
                    for (char c : letters) sb.append(c);
                    shuffled = sb.toString();
                }
                r.answer = word;
                r.question = Text.fill(t.getString("questions.unscramble", "Unscramble %word%"), Map.of("word", shuffled));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Running r = running;
        if (r == null || r.type != Type.CHATGAME || r.answer == null) return;
        String typed = StyledText.plain(Text.plain(event.message())).strip();
        if (!typed.equalsIgnoreCase(r.answer) || !r.won.compareAndSet(false, true)) return;
        Player p = event.getPlayer();
        long took = (System.currentTimeMillis() - r.started) / 100;
        Scheduler.global(() -> {
            broadcast(Messages.everyone(), "chatgame-won", Map.of("player", p.getName(), "answer", r.answer,
                    "seconds", took / 10.0));
            pay(p.getUniqueId(), p.getName(), Type.CHATGAME, 1);
            finish(r, false);
        });
    }

    // ── Competitions ──────────────────────────────────────────────────────

    private void score(Running r, Player p, int by) {
        r.names.put(p.getUniqueId(), p.getName());
        r.scores.merge(p.getUniqueId(), by, Integer::sum);
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Running r = running;
        if (r != null && r.type == Type.MINING) r.placed.add(key(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Running r = running;
        if (r == null || r.type != Type.MINING || event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        if (r.placed.remove(key(event.getBlock()))) return; // placed during the event: not mined
        if (!miningBlocks.isEmpty() && !miningBlocks.contains(event.getBlock().getType())) return;
        score(r, event.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        Running r = running;
        if (r == null || r.type != Type.MOBS || event.getEntity() instanceof Player) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (!typeConfig(Type.MOBS).getBoolean("count-spawner-mobs", false)
                && event.getEntity().getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        score(r, killer, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Running r = running;
        if (r != null && r.type == Type.FISHING && event.getState() == PlayerFishEvent.State.CAUGHT_FISH) score(r, event.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(PlayerDeathEvent event) {
        Running r = running;
        if (r == null || r.type != Type.PVP) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.equals(event.getEntity()) || IpProtectionFeature.voidedKill(event.getEntity())) return;
        score(r, killer, 1);
    }

    // ── KOTH ──────────────────────────────────────────────────────────────

    private int captureSeconds() {
        return (int) Math.max(5, Time.seconds(typeConfig(Type.KOTH).getString("capture", "60s")));
    }

    /** The hill, or null when none is set or its world isn't loaded (worlds load after VexCore). */
    private Location hill() {
        Location h = hill;
        if (h == null) {
            loadHill();
            h = hill;
        }
        return h;
    }

    private void loadHill() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        World w = y.getString("koth.world") == null ? null : Bukkit.getWorld(y.getString("koth.world"));
        hill = w == null ? null : new Location(w, y.getDouble("koth.x"), y.getDouble("koth.y"), y.getDouble("koth.z"));
        radius = Math.max(1, y.getDouble("koth.radius", 5));
    }

    private void kothTick(Running r) {
        Location hill = hill();
        if (hill == null) return;
        double radius = this.radius;
        List<Player> on = new ArrayList<>();
        for (Player p : hill.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
            if (p.hasPermission("vexcore.events.ignore")) continue;
            Location l = p.getLocation();
            double dx = l.getX() - hill.getX(), dz = l.getZ() - hill.getZ();
            if (dx * dx + dz * dz <= radius * radius && Math.abs(l.getY() - hill.getY()) <= radius) on.add(p);
        }
        if (on.size() != 1) return; // empty or contested: the clock stops
        Player holder = on.getFirst();
        r.names.put(holder.getUniqueId(), holder.getName());
        if (!holder.getUniqueId().equals(r.capturer)) {
            r.capturer = holder.getUniqueId();
            r.progress = 0;
            broadcast(Messages.everyone(), "koth-capturing", Map.of("player", holder.getName()));
        }
        r.progress++;
        if (r.progress >= captureSeconds() && r.won.compareAndSet(false, true)) {
            broadcast(Messages.everyone(), "koth-won", Map.of("player", holder.getName()));
            pay(holder.getUniqueId(), holder.getName(), Type.KOTH, 1);
            finish(r, false);
        }
    }

    // ── /event ────────────────────────────────────────────────────────────

    private void command(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("vexcore.events.admin");
        if (admin && sub.equals("start") && args.length > 1) {
            try {
                if (start(Type.valueOf(args[1].toUpperCase(Locale.ROOT)), sender)) msg(sender, "started", "event", typeName(Type.valueOf(args[1].toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException e) {
                msg(sender, "unknown-type");
            }
            return;
        }
        if (admin && sub.equals("stop")) {
            Running r = running;
            if (r == null) msg(sender, "none-running");
            else finish(r, true);
            return;
        }
        if (admin && sub.equals("koth") && args.length > 1 && args[1].equalsIgnoreCase("set")) {
            Player p = player(sender);
            if (p == null) return;
            double radius = args.length > 2 ? Math.max(1, Math.min(50, parse(args[2]))) : 5;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            Location l = p.getLocation();
            y.set("koth.world", l.getWorld().getName());
            y.set("koth.x", l.getBlockX() + 0.5);
            y.set("koth.y", l.getBlockY());
            y.set("koth.z", l.getBlockZ() + 0.5);
            y.set("koth.radius", radius);
            try {
                y.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save data/events.yml: " + e.getMessage());
            }
            loadHill();
            msg(p, "hill-set", "radius", radius);
            return;
        }
        Running r = running;
        if (r == null) {
            msg(sender, "status-none", "next", nextAuto <= 0 ? "-" : plugin.messages().time(Math.max(0, (nextAuto - System.currentTimeMillis()) / 1000)));
            return;
        }
        Map<String, Object> ph = new HashMap<>();
        ph.put("event", typeName(r.type));
        ph.put("time", plugin.messages().time(Math.max(0, (r.ends - System.currentTimeMillis()) / 1000)));
        ph.put("question", r.question == null ? "-" : r.question);
        Integer mine = sender instanceof Player p ? r.scores.get(p.getUniqueId()) : null;
        ph.put("score", mine == null ? 0 : mine);
        msg(sender, "status", ph);
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 5;
        }
    }
}
