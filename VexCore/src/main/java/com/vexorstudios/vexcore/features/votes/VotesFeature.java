package com.vexorstudios.vexcore.features.votes;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Messages;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature;
import com.vexorstudios.vexcore.gui.Actions;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vote rewards. Votes arrive from NuVotifier (found at runtime, no hard dependency). Each vote
 * pays money, runs commands and may roll bonus rewards; votes for offline players wait until they
 * join. Every vote counts towards the vote party, which rewards everyone online when it fills.
 * /vote: the vote sites (click for the link; each shows when you can vote there again), your
 * totals and the party. /votetop: this month's top voters. /voteparty: progress (admin: start).
 * /fakevote &lt;player&gt; [site]: a test vote.
 */
public final class VotesFeature extends Feature {

    private static final String VOTIFIER_EVENT = "com.vexsoftware.votifier.model.VotifierEvent";
    private static final java.util.regex.Pattern SERVICE_UNSAFE = java.util.regex.Pattern.compile("[^A-Za-z0-9 ._:-]");

    private final AtomicInteger party = new AtomicInteger();
    private File file;

    @Override
    protected void enable() {
        db().schema("votes", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, "
                + "total INT NOT NULL, month VARCHAR(7) NOT NULL, month_votes INT NOT NULL, last_vote BIGINT NOT NULL)");
        db().schema("vote_sites", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, site VARCHAR(64) NOT NULL, "
                + "last_vote BIGINT NOT NULL, PRIMARY KEY (uuid, site))");
        db().schema("vote_queue", "CREATE TABLE IF NOT EXISTS {t} (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, "
                + "service VARCHAR(64) NOT NULL, time BIGINT NOT NULL)");
        db().index("vote_queue", "name");
        db().index("votes", "month");
        file = plugin.files().data("votes.yml");
        party.set(YamlConfiguration.loadConfiguration(file).getInt("party", 0));
        hookVotifier();

        command("vote", (s, l, a) -> {
            Player p = player(s);
            if (p != null) openVote(p);
        });
        command("votetop", (s, l, a) -> top(s));
        command("voteparty", (s, l, a) -> {
            if (a.length > 0 && a[0].equalsIgnoreCase("start") && s.hasPermission("vexcore.votes.admin")) {
                startParty();
                return;
            }
            msg(s, "party-status", "current", Numbers.format(party.get()), "needed", Numbers.format(partyNeeded()),
                    "left", Numbers.format(Math.max(0, partyNeeded() - party.get())));
        }, (s, a) -> a.length == 1 && s.hasPermission("vexcore.votes.admin") ? List.of("start") : List.of());
        command("fakevote", (s, l, a) -> {
            if (a.length == 0) {
                usage(s, "fakevote");
                return;
            }
            vote(a[0], a.length > 1 ? a[1] : "test");
            msg(s, "fake-sent", "player", a[0]);
        }, (s, a) -> a.length == 1 ? null : List.of());

        placeholder("voteparty_current", (p, a) -> count(party.get(), a));
        placeholder("voteparty_needed", (p, a) -> count(partyNeeded(), a));
    }

    /** Listens for NuVotifier's event by name, so VexCore needs no Votifier jar to build or run. */
    @SuppressWarnings("unchecked")
    private void hookVotifier() {
        Class<?> event;
        try {
            event = Class.forName(VOTIFIER_EVENT);
        } catch (ClassNotFoundException missing) {
            problems().add("features/votes: NuVotifier is not installed; only /fakevote gives votes.");
            return;
        }
        Listener hook = new Listener() {
        };
        listen(hook); // unregistered with the feature
        Bukkit.getPluginManager().registerEvent((Class<? extends Event>) event, hook, EventPriority.NORMAL, (listener, e) -> {
            if (!event.isInstance(e)) return;
            try {
                Object vote = e.getClass().getMethod("getVote").invoke(e);
                String user = String.valueOf(vote.getClass().getMethod("getUsername").invoke(vote)).strip();
                String service = SERVICE_UNSAFE.matcher(String.valueOf(vote.getClass().getMethod("getServiceName").invoke(vote))).replaceAll("").strip();
                // Vote sites send whatever was typed in their name box: a name no player can have
                // could carry clickable tags, placeholders or extra command arguments.
                if (user.isEmpty() || !Text.safeName(user).equals(user)) {
                    plugin.getLogger().warning("Ignored a vote from " + service + " for an invalid name: " + Text.safeName(user));
                    return;
                }
                Scheduler.global(() -> vote(user, service.length() > 64 ? service.substring(0, 64) : service));
            } catch (ReflectiveOperationException | RuntimeException bad) {
                plugin.getLogger().warning("Could not read a vote from NuVotifier: " + bad);
            }
        }, plugin, false);
    }

    // ── A vote ────────────────────────────────────────────────────────────

    /** Global thread. The player gets it now if online, otherwise when they next join. */
    void vote(String name, String service) {
        if (!isEnabled()) return;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            Scheduler.entity(online, () -> reward(online, service));
        } else if (config().getBoolean("offline.queue", true)) {
            String table = db().table("vote_queue");
            long id = System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
            db().queue("vote queue", c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (id, name, service, time) VALUES (?, ?, ?, ?)")) {
                    ps.setLong(1, id);
                    ps.setString(2, name.toLowerCase(Locale.ROOT));
                    ps.setString(3, service);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            });
        }
        broadcast(Messages.everyone(), "voted-broadcast", Map.of("player", name, "site", siteName(service)));
        if (party.incrementAndGet() >= partyNeeded()) startParty();
        else {
            int left = partyNeeded() - party.get();
            if (config().getIntegerList("party.remind-at").contains(left)) broadcast(Messages.everyone(), "party-soon", Map.of("left", left));
        }
        saveParty();
    }

    /** Player's thread: pays one vote and records it. */
    private void reward(Player p, String service) {
        ConfigurationSection r = config().getConfigurationSection("rewards");
        Map<String, Object> ph = new HashMap<>(Map.of("player", p.getName(), "site", siteName(service)));
        double money = r == null ? 0 : Math.max(0, r.getDouble("money", 0));
        if (money > 0 && plugin.money().deposit(p, money)) ph.put("money", plugin.money().format(money));
        else ph.put("money", plugin.money().format(0));
        if (r != null) Actions.run(p, ownList(r, "commands"), ph, null);
        msg(p, "voted", ph);
        ConfigurationSection bonus = r == null ? null : r.getConfigurationSection("bonus");
        if (bonus != null) for (String key : bonus.getKeys(false)) {
            ConfigurationSection b = bonus.getConfigurationSection(key);
            if (b == null || ThreadLocalRandom.current().nextDouble(100) >= b.getDouble("chance", 0)) continue;
            Actions.run(p, ownList(b, "commands"), ph, null);
            Map<String, Object> bph = new HashMap<>(ph);
            bph.put("bonus", b.getString("name", key));
            msg(p, "bonus", bph);
            if (b.getBoolean("announce", false)) broadcast(Messages.everyone(), "bonus-broadcast", bph);
        }
        record(p.getUniqueId(), p.getName(), service);
    }

    private void record(UUID uuid, String name, String service) {
        String month = YearMonth.now().toString();
        long now = System.currentTimeMillis();
        String votes = db().table("votes");
        db().queue("vote record", c -> {
            int total = 0, monthVotes = 0;
            String had = "";
            try (PreparedStatement ps = c.prepareStatement("SELECT total, month, month_votes FROM " + votes + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getInt(1);
                        had = rs.getString(2);
                        monthVotes = rs.getInt(3);
                    }
                }
            }
            if (!month.equals(had)) monthVotes = 0; // a new month starts from zero
            try (PreparedStatement ps = c.prepareStatement(db().upsert("votes", new String[]{"uuid"}, "name", "total", "month", "month_votes", "last_vote"))) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setInt(3, total + 1);
                ps.setString(4, month);
                ps.setInt(5, monthVotes + 1);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(db().upsert("vote_sites", new String[]{"uuid", "site"}, "last_vote"))) {
                ps.setString(1, uuid.toString());
                ps.setString(2, service.toLowerCase(Locale.ROOT));
                ps.setLong(3, now);
                ps.executeUpdate();
            }
        });
    }

    /** Votes that came in while the player was away. */
    @Override
    protected void loaded(Player p) {
        String table = db().table("vote_queue");
        String name = p.getName().toLowerCase(Locale.ROOT);
        db().query("vote queue take", c -> {
            // Each queued vote is taken by deleting its own row, and only rewarded if this server
            // deleted it: servers sharing one database never both pay it, and a vote queued while
            // this runs isn't deleted unpaid.
            Map<Long, String> queued = new java.util.LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, service FROM " + table + " WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) queued.put(rs.getLong(1), rs.getString(2));
                }
            }
            List<String> services = new ArrayList<>();
            if (!queued.isEmpty()) try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
                for (Map.Entry<Long, String> e : queued.entrySet()) {
                    ps.setLong(1, e.getKey());
                    if (ps.executeUpdate() == 1) services.add(e.getValue());
                }
            }
            return services;
        }).whenComplete((services, error) -> {
            if (services == null || services.isEmpty() || !plugin.isEnabled()) return;
            Scheduler.entity(p, () -> {
                msg(p, "queued", "count", services.size());
                for (String service : services) reward(p, service);
            });
        });
    }

    // ── Vote party ────────────────────────────────────────────────────────

    private int partyNeeded() {
        return Math.max(1, config().getInt("party.votes", 50));
    }

    private void startParty() {
        party.set(0);
        saveParty();
        if (!config().getBoolean("party.enabled", true)) return;
        broadcast(Messages.everyone(), "party-start", Map.of());
        List<String> commands = config().getStringList("party.commands");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!IpProtectionFeature.earns(p, "voteparty")) {
                IpProtectionFeature.notEarning(p, "voteparty");
                continue;
            }
            Scheduler.entity(p, () -> {
                Actions.run(p, commands, Map.of("player", p.getName()), null);
                double money = config().getDouble("party.money", 0);
                if (money > 0) plugin.money().deposit(p, money);
            });
        }
    }

    private void saveParty() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("party", party.get());
        Scheduler.async(() -> {
            try {
                yml.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save data/votes.yml: " + e.getMessage());
            }
        });
    }

    // ── /vote menu ────────────────────────────────────────────────────────

    private String siteName(String service) {
        ConfigurationSection sites = config().getConfigurationSection("sites");
        if (sites != null) for (String key : sites.getKeys(false)) {
            if (service.equalsIgnoreCase(sites.getString(key + ".service", ""))) return sites.getString(key + ".name", key);
        }
        return service;
    }

    private void openVote(Player p) {
        String votes = db().table("votes"), sites = db().table("vote_sites");
        UUID uuid = p.getUniqueId();
        db().query("vote info", c -> {
            Map<String, Long> last = new HashMap<>();
            int[] counts = new int[2];
            try (PreparedStatement ps = c.prepareStatement("SELECT site, last_vote FROM " + sites + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) last.put(rs.getString(1), rs.getLong(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT total, month, month_votes FROM " + votes + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        counts[0] = rs.getInt(1);
                        counts[1] = YearMonth.now().toString().equals(rs.getString(2)) ? rs.getInt(3) : 0;
                    }
                }
            }
            return Map.entry(last, counts);
        }).whenComplete((info, error) -> {
            if (info == null || !plugin.isEnabled()) return;
            Scheduler.entity(p, () -> open(p, "vote", menu -> {
                menu.with("total", Numbers.format(info.getValue()[0])).with("month", Numbers.format(info.getValue()[1]))
                        .with("party", Numbers.format(party.get())).with("party_needed", Numbers.format(partyNeeded()));
                ConfigurationSection sitesCfg = config().getConfigurationSection("sites");
                List<String> keys = sitesCfg == null ? List.of() : new ArrayList<>(sitesCfg.getKeys(false));
                menu.paginate(keys, (key, slot) -> {
                    ConfigurationSection s = sitesCfg.getConfigurationSection(key);
                    if (s == null) return;
                    long lastVote = info.getKey().getOrDefault(s.getString("service", key).toLowerCase(Locale.ROOT), 0L);
                    long wait = lastVote + Math.max(0, s.getLong("cooldown-hours", 24)) * 3_600_000L - System.currentTimeMillis();
                    boolean ready = wait <= 0;
                    String url = s.getString("url", "");
                    Map<String, Object> ph = new HashMap<>();
                    ph.put("site", s.getString("name", key));
                    ph.put("url", url);
                    ph.put("material", s.getString("material", "PAPER"));
                    ph.put("status", ready ? config().getString("words.ready", "&#97F900Ready to vote!")
                            : Text.fill(config().getString("words.wait", "&#FF3B3BVote again in %time%"), Map.of("time", plugin.messages().time(wait / 1000))));
                    menu.place(ready ? "site-ready" : "site-waiting", slot, ph, click -> {
                        click.player().closeInventory();
                        click.player().sendMessage(Text.parse(config().getString("link", "{prefix}&fClick: &#FFD900&n%url%")
                                .replace("{prefix}", plugin.messages().prefix(this)), click.player(), ph)
                                .clickEvent(ClickEvent.openUrl(url.startsWith("http") ? url : "https://" + url)));
                    });
                });
            }));
        });
    }

    private void top(CommandSender sender) {
        String table = db().table("votes");
        String month = YearMonth.now().toString();
        int size = Math.max(1, Math.min(20, config().getInt("top-size", 10)));
        db().query("vote top", c -> {
            List<Object[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, month_votes FROM " + table + " WHERE month = ? ORDER BY month_votes DESC")) {
                ps.setString(1, month);
                ps.setMaxRows(size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[]{rs.getString(1), rs.getInt(2)});
                }
            }
            return rows;
        }).whenComplete((rows, error) -> {
            if (rows == null || !plugin.isEnabled()) return;
            Scheduler.global(() -> {
                if (rows.isEmpty()) {
                    msg(sender, "top-empty");
                    return;
                }
                msg(sender, "top-header", "month", month);
                int place = 1;
                for (Object[] r : rows) msg(sender, "top-line", "place", place++, "player", r[0], "votes", Numbers.format((Integer) r[1]));
            });
        });
    }
}
