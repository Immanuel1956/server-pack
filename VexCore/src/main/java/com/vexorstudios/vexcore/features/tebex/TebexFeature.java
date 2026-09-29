package com.vexorstudios.vexcore.features.tebex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SoundSpec;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.ggwave.GgWaveFeature;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Store goals and purchase announcements from Tebex.
 * <ul>
 *   <li>Every few seconds the Tebex Plugin API is asked for the latest payments (needs the
 *       server's secret key). A new payment is announced with the buyer's head drawn in chat and
 *       starts a GG wave that pays everyone who says gg.</li>
 *   <li>A boss bar shows the store's progress towards the current goal: either VexCore's own
 *       goals (money counted from payments seen, several goals with rewards) or a Tebex
 *       community goal.</li>
 *   <li>/tebexpurchase &lt;player&gt; &lt;amount&gt; [package] announces a purchase straight away, for a
 *       Tebex package command instead of waiting for the next check.</li>
 *   <li>/goal shows the goal; /goal set|add|reset|refresh for admins.</li>
 * </ul>
 */
public final class TebexFeature extends Feature implements Listener {

    private static final String API = "https://plugin.tebex.io";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** A goal of VexCore's own list. */
    private record Goal(String id, String name, double target, List<String> commands) {
    }

    // State kept in data/tebex.yml.
    private final Object lock = new Object();
    private double total;                                     // money counted towards own goals
    private final Set<String> reached = new LinkedHashSet<>();  // own goals already rewarded
    private final Set<String> seen = new LinkedHashSet<>();     // payment ids already handled
    private boolean baseline;                                 // the first check only learns what exists
    private int communityAchieved = -1;                       // community goal: times achieved last seen

    // Community goal as last fetched.
    private volatile double communityCurrent;
    private volatile double communityTarget;
    private volatile String communityName = "";

    private BossBar bar;
    private volatile long flashUntil;
    private List<Goal> goals = List.of();        // own goals, read once, sorted by target
    private boolean communitySource;
    private String lastTitle;                    // the boss bar is only touched when it changes
    private float lastProgress = -1;
    private File file;

    @Override
    protected void enable() {
        listen(this);
        communitySource = config().getString("goals.source", "own").trim().equalsIgnoreCase("community");
        goals = readGoals();
        file = plugin.files().data("tebex.yml");
        loadState();

        if (config().getBoolean("bossbar.enabled", true)) {
            bar = BossBar.bossBar(Component.empty(), 0f, color(), overlay());
            for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
            updateBar();
            every(20, this::updateBar); // the flash text times out, placeholders move
        }

        String secret = secret();
        if (!secret.isEmpty()) {
            long seconds = Math.max(15, config().getLong("poll-seconds", 30));
            track(Scheduler.asyncTimer(this::poll, 40, seconds * 20));
        } else if (community()) {
            problems().add(com.vexorstudios.vexcore.core.Files.configPath(id()) + ": goals.source is community but there is no secret key.");
        }

        command("tebexpurchase", this::purchaseCommand, (s, a) -> a.length == 1 ? null : List.of());
        command("goal", this::goalCommand, (s, a) -> a.length == 1 && s.hasPermission(adminPermission())
                ? List.of("set", "add", "reset", "refresh") : List.of());

        placeholder("goal_current", (p, a) -> Numbers.full(current(), 2, ""));
        placeholder("goal_current_formatted", (p, a) -> money(current()));
        placeholder("goal_target", (p, a) -> Numbers.full(target(), 2, ""));
        placeholder("goal_target_formatted", (p, a) -> money(target()));
        placeholder("goal_percent", (p, a) -> String.valueOf(percent()));
        placeholder("goal_name", (p, a) -> goalName());
    }

    @Override
    protected void disable() {
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        bar = null;
        saveNow();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        BossBar b = bar;
        if (b != null) event.getPlayer().showBossBar(b);
    }

    private String secret() {
        String s = config().getString("secret", "");
        return s == null ? "" : s.trim();
    }

    private String adminPermission() {
        return config().getString("admin-permission", "vexcore.goal.admin");
    }

    private boolean community() {
        return communitySource;
    }

    // ── Tebex ─────────────────────────────────────────────────────────────

    private JsonElement get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Tebex-Secret", secret())
                .header("User-Agent", "VexCore")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 403 || response.statusCode() == 401) throw new IOException("Tebex refused the secret key (" + response.statusCode() + ")");
        if (response.statusCode() >= 300) throw new IOException("Tebex answered " + response.statusCode());
        return JsonParser.parseString(response.body());
    }

    private volatile long lastError;

    /** Async: new payments, then the community goal. */
    private void poll() {
        if (!isEnabled()) return;
        try {
            if (config().getBoolean("poll-payments", true)) pollPayments();
            if (community()) pollCommunity();
        } catch (Exception e) {
            if (System.currentTimeMillis() - lastError > 10 * 60_000L) { // don't flood the console
                lastError = System.currentTimeMillis();
                plugin.getLogger().warning("Tebex: " + e.getMessage());
            }
        }
    }

    private void pollPayments() throws IOException, InterruptedException {
        int limit = Math.max(1, Math.min(100, config().getInt("payments-per-check", 25)));
        JsonElement root = get("/payments?limit=" + limit);
        JsonArray list = root.isJsonArray() ? root.getAsJsonArray()
                : root.isJsonObject() && root.getAsJsonObject().has("data") ? root.getAsJsonObject().getAsJsonArray("data") : new JsonArray();
        List<JsonObject> fresh = new ArrayList<>();
        boolean first;
        synchronized (lock) {
            first = !baseline;
            for (JsonElement e : list) {
                if (!e.isJsonObject()) continue;
                JsonObject payment = e.getAsJsonObject();
                String id = string(payment, "id");
                if (id.isEmpty() || !seen.add(id)) continue;
                fresh.add(payment);
            }
            while (seen.size() > 500) seen.remove(seen.iterator().next());
            baseline = true;
        }
        if (first && !config().getBoolean("count-existing-on-first-run", false)) {
            // The first check learns which payments already exist; they are not new.
            saveLater();
            return;
        }
        // Oldest first, the way they happened.
        java.util.Collections.reverse(fresh);
        for (JsonObject payment : fresh) {
            JsonObject player = payment.has("player") && payment.get("player").isJsonObject() ? payment.getAsJsonObject("player") : new JsonObject();
            // What the buyer typed at checkout: it goes into console commands and a broadcast.
            String name = Text.safeName(string(player, "name"));
            UUID uuid = uuid(string(player, "uuid"));
            double amount = number(payment, "amount");
            String currency = payment.has("currency") && payment.get("currency").isJsonObject()
                    ? string(payment.getAsJsonObject("currency"), "symbol") : "";
            String pack = "";
            if (payment.has("packages") && payment.get("packages").isJsonArray()) {
                List<String> names = new ArrayList<>();
                for (JsonElement p : payment.getAsJsonArray("packages")) {
                    if (p.isJsonObject()) names.add(string(p.getAsJsonObject(), "name"));
                }
                pack = String.join(", ", names);
            }
            String finalPack = pack;
            Scheduler.global(() -> purchase(name.isEmpty() ? "Someone" : name, uuid, amount, currency, finalPack));
        }
        saveLater();
    }

    private void pollCommunity() throws IOException, InterruptedException {
        JsonElement root = get("/community_goals");
        JsonArray goals = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
        if (root.isJsonObject()) goals.add(root);
        String wanted = config().getString("goals.community-goal-id", "").trim();
        JsonObject pick = null;
        for (JsonElement e : goals) {
            if (!e.isJsonObject()) continue;
            JsonObject g = e.getAsJsonObject();
            if (!wanted.isEmpty() && !wanted.equals("0")) {
                if (wanted.equals(string(g, "id"))) pick = g;
            } else if (pick == null && !"disabled".equalsIgnoreCase(string(g, "status"))) {
                pick = g;
            }
        }
        if (pick == null) return;
        communityCurrent = number(pick, "current");
        communityTarget = number(pick, "target");
        communityName = string(pick, "name");
        int achieved = (int) number(pick, "times_achieved");
        boolean reachedNow;
        synchronized (lock) {
            reachedNow = communityAchieved >= 0 && achieved > communityAchieved;
            communityAchieved = achieved;
        }
        if (reachedNow) {
            String name = communityName;
            double target = communityTarget;
            Scheduler.global(() -> goalReached(new Goal("community", name, target,
                    config().getStringList("goals.community-commands"))));
        }
        saveLater();
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static double number(JsonObject o, String key) {
        try {
            return Double.parseDouble(string(o, key).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.replace("-", "");
        if (s.length() != 32) return null;
        try {
            return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                    + "-" + s.substring(16, 20) + "-" + s.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── A purchase ────────────────────────────────────────────────────────

    /** Global thread. */
    private void purchase(String name, UUID uuid, double amount, String currency, String pack) {
        if (!isEnabled()) return;
        if (!community() && amount > 0) {
            synchronized (lock) {
                total += amount;
            }
            saveLater();
        }
        flashUntil = System.currentTimeMillis() + Math.max(0, config().getLong("bossbar.purchase-flash-seconds", 6)) * 1000;
        updateBar();
        checkGoals();

        ConfigurationSection s = config().getConfigurationSection("purchase");
        if (s == null || !s.getBoolean("enabled", true)) return;
        double ggMoney = Math.max(0, s.getDouble("gg-wave.money", 50000));
        long ggSeconds = Math.max(0, com.vexorstudios.vexcore.core.Time.seconds(s.getString("gg-wave.duration", "30s")));
        Map<String, Object> ph = new HashMap<>();
        ph.put("player", name);
        ph.put("amount", (currency == null || currency.isEmpty() ? config().getString("currency-symbol", "$") : currency) + Numbers.money(amount, 2, ","));
        ph.put("package", pack == null || pack.isBlank() ? s.getString("unknown-package", "a package") : pack);
        ph.put("money", plugin.money().format(ggMoney));
        ph.put("money_short", plugin.money().shortFormat(ggMoney));
        ph.put("time", plugin.messages().time(ggSeconds));
        ph.put("goal_current", money(current()));
        ph.put("goal_target", money(target()));
        ph.put("goal_percent", percent());

        List<String> lines = s.getStringList("lines");
        String pixel = s.getString("pixel", "█");
        boolean head = s.getBoolean("show-head", true);
        HeadArt.face(uuid, name).whenComplete((face, error) -> {
            if (!plugin.isEnabled()) return;
            Scheduler.global(() -> announce(head ? face : null, pixel, lines, ph));
        });
        for (String command : s.getStringList("commands")) {
            String line = Text.fill(command, ph);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
        if (s.getBoolean("gg-wave.enabled", true) && ggSeconds > 0
                && plugin.features().get("ggwave") instanceof GgWaveFeature wave) {
            wave.start(name, ggSeconds, ggMoney, s.getStringList("gg-wave.rewards"), null);
        }
    }

    /** The buyer's head (8 rows of coloured blocks) with a line of text next to each row. */
    private void announce(int[][] face, String pixel, List<String> lines, Map<String, Object> ph) {
        int rows = face == null ? lines.size() : Math.max(8, lines.size());
        List<Component> out = new ArrayList<>(rows);
        for (int y = 0; y < rows; y++) {
            Component row = Component.empty();
            if (face != null) {
                if (y < 8) {
                    for (int x = 0; x < 8; x++) row = row.append(Component.text(pixel, TextColor.color(face[y][x])));
                } else {
                    row = row.append(Component.text(" ".repeat(8 * 2))); // keeps text lined up under the head
                }
                row = row.append(Component.text("  "));
            }
            if (y < lines.size()) row = row.append(Text.parse(lines.get(y), null, ph));
            out.add(row);
        }
        SoundSpec sound = SoundSpec.of(config().get("purchase.sound"));
        String blank = config().getBoolean("purchase.blank-lines", true) ? "" : null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Scheduler.entity(p, () -> {
                if (blank != null) p.sendMessage(Component.empty());
                for (Component c : out) p.sendMessage(c);
                if (blank != null) p.sendMessage(Component.empty());
                if (sound != null) sound.play(p);
            });
        }
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (String line : lines) if (!line.isBlank()) console.sendMessage(Text.parse(line, null, ph));
    }

    // ── Goals ─────────────────────────────────────────────────────────────

    private List<Goal> ownGoals() {
        return goals;
    }

    private List<Goal> readGoals() {
        List<Goal> out = new ArrayList<>();
        ConfigurationSection s = config().getConfigurationSection("goals.list");
        if (s == null) return out;
        for (String key : s.getKeys(false)) {
            ConfigurationSection g = s.getConfigurationSection(key);
            if (g == null) continue;
            double target = g.getDouble("target", 0);
            if (target <= 0) continue;
            out.add(new Goal(key, g.getString("name", key), target, g.getStringList("commands")));
        }
        out.sort(java.util.Comparator.comparingDouble(Goal::target));
        return List.copyOf(out);
    }

    /** The goal being worked on: the first own goal not reached yet, or the last one. */
    private Goal currentGoal() {
        List<Goal> goals = ownGoals();
        if (goals.isEmpty()) return null;
        synchronized (lock) {
            for (Goal g : goals) if (!reached.contains(g.id)) return g;
        }
        return goals.getLast();
    }

    double current() {
        if (community()) return communityCurrent;
        synchronized (lock) {
            return total;
        }
    }

    double target() {
        if (community()) return communityTarget;
        Goal g = currentGoal();
        return g == null ? 0 : g.target;
    }

    String goalName() {
        if (community()) return communityName;
        Goal g = currentGoal();
        return g == null ? config().getString("goals.none-name", "Store Goal") : g.name;
    }

    int percent() {
        double t = target();
        return t <= 0 ? 0 : (int) Math.min(100, Math.floor(current() / t * 100));
    }

    private String money(double v) {
        return config().getString("currency-symbol", "$") + Numbers.money(v, 2, ",");
    }

    /** Own goals: rewards every goal the total has passed, once. */
    private void checkGoals() {
        if (community()) return;
        List<Goal> hit = new ArrayList<>();
        List<Goal> goals = ownGoals();
        boolean resetAfter = false;
        synchronized (lock) {
            for (Goal g : goals) if (total >= g.target && reached.add(g.id)) hit.add(g);
            if (!goals.isEmpty() && reached.size() >= goals.size()
                    && config().getString("goals.when-all-reached", "reset").equalsIgnoreCase("reset")) {
                total = Math.max(0, total - goals.getLast().target);
                reached.clear();
                resetAfter = true;
            }
        }
        for (Goal g : hit) goalReached(g);
        if (!hit.isEmpty() || resetAfter) {
            saveLater();
            updateBar();
        }
    }

    private void goalReached(Goal g) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("goal", g.name);
        ph.put("target", money(g.target));
        broadcast(com.vexorstudios.vexcore.core.Messages.everyone(), "goal-reached", ph);
        for (String command : g.commands) {
            String line = Text.fill(command, ph);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
    }

    // ── Boss bar ──────────────────────────────────────────────────────────

    private BossBar.Color color() {
        try {
            return BossBar.Color.valueOf(config().getString("bossbar.color", "GREEN").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.GREEN;
        }
    }

    private BossBar.Overlay overlay() {
        try {
            return BossBar.Overlay.valueOf(config().getString("bossbar.overlay", "NOTCHED_10").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Overlay.NOTCHED_10;
        }
    }

    private void updateBar() {
        BossBar b = bar;
        if (b == null) return;
        Map<String, Object> ph = new HashMap<>();
        ph.put("current", money(current()));
        ph.put("target", money(target()));
        ph.put("percent", percent());
        ph.put("goal", goalName());
        GgWaveFeature wave = plugin.features().get("ggwave") instanceof GgWaveFeature w ? w : null;
        String path = System.currentTimeMillis() < flashUntil ? "bossbar.purchase-title"
                : wave != null && wave.running() ? "bossbar.gg-title" : "bossbar.title";
        String title = Text.fill(config().getString(path, config().getString("bossbar.title", "%goal% %current%/%target%")), ph);
        double t = target();
        float progress = t <= 0 ? 0f : (float) Math.max(0, Math.min(1, current() / t));
        synchronized (this) {
            if (!title.equals(lastTitle)) {
                lastTitle = title;
                b.name(Text.parse(title));
            }
            if (progress != lastProgress) {
                lastProgress = progress;
                b.progress(progress);
            }
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────

    /** /tebexpurchase <player> <amount> [package...]: for a Tebex package command, or to test. */
    private void purchaseCommand(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, "tebexpurchase");
            return;
        }
        double amount = Numbers.parse(args[1].replace("$", "").replace("€", "").replace("£", ""));
        if (!Double.isFinite(amount) || amount < 0) {
            msg(sender, "invalid-amount");
            return;
        }
        String name = args[0];
        Player online = Bukkit.getPlayerExact(name);
        OfflinePlayer cached = online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
        UUID uuid = cached == null ? null : cached.getUniqueId();
        String pack = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        String shown = cached != null && cached.getName() != null ? cached.getName() : name;
        Scheduler.global(() -> purchase(shown, uuid, amount, "", pack)); // console commands run from here
        if (!(sender instanceof ConsoleCommandSender)) msg(sender, "purchase-sent", "player", name);
    }

    private void goalCommand(CommandSender sender, String label, String[] args) {
        if (args.length == 0 || !sender.hasPermission(adminPermission())) {
            msg(sender, "goal", "goal", goalName(), "current", money(current()), "target", money(target()), "percent", percent());
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "refresh" -> {
                Scheduler.async(this::poll);
                msg(sender, "refreshed");
            }
            case "reset" -> {
                synchronized (lock) {
                    total = 0;
                    reached.clear();
                }
                saveLater();
                updateBar();
                msg(sender, "goal-changed", "current", money(0));
            }
            case "set", "add" -> {
                if (community()) {
                    msg(sender, "community-only");
                    return;
                }
                if (args.length < 2) {
                    msg(sender, "goal-usage", "command", plugin.commands().name("goal"));
                    return;
                }
                double v = Numbers.parse(args[1]);
                if (!Double.isFinite(v) || v < 0 && sub.equals("set")) {
                    msg(sender, "invalid-amount");
                    return;
                }
                synchronized (lock) {
                    total = sub.equals("set") ? v : Math.max(0, total + v);
                    if (sub.equals("set")) reached.removeIf(id -> ownGoals().stream().noneMatch(g -> g.id.equals(id) && g.target <= total));
                }
                checkGoals();
                saveLater();
                updateBar();
                msg(sender, "goal-changed", "current", money(current()));
            }
            default -> msg(sender, "goal-usage", "command", plugin.commands().name("goal"));
        }
    }

    // ── Saved state ───────────────────────────────────────────────────────

    private void loadState() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        synchronized (lock) {
            total = y.getDouble("total", 0);
            reached.clear();
            reached.addAll(y.getStringList("reached"));
            seen.clear();
            seen.addAll(y.getStringList("seen"));
            baseline = y.getBoolean("baseline", false);
            communityAchieved = y.getInt("community-achieved", -1);
        }
    }

    private volatile boolean dirty;

    private void saveLater() {
        dirty = true;
        if (plugin.isEnabled()) Scheduler.async(this::saveNow);
        else saveNow();
    }

    private void saveNow() {
        YamlConfiguration y = new YamlConfiguration();
        synchronized (lock) {
            if (!dirty) return;
            dirty = false;
            y.set("total", total);
            y.set("reached", new ArrayList<>(reached));
            y.set("seen", new ArrayList<>(seen));
            y.set("baseline", baseline);
            y.set("community-achieved", communityAchieved);
            try {
                file.getParentFile().mkdirs();
                y.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save data/tebex.yml: " + e.getMessage());
            }
        }
    }
}
