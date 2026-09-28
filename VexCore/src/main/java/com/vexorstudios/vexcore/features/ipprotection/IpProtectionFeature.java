package com.vexorstudios.vexcore.features.ipprotection;

import com.vexorstudios.vexcore.VexCore;
import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stops alt accounts from multiplying grind rewards. Accounts are grouped by the network (IP
 * address) they play from; addresses are only ever stored as a salted hash.
 *
 * <p>Links are all-time and live in the database ({@code ip_accounts}): two accounts that ever
 * played from the same network stay linked, across restarts and after either one changes network
 * (a VPN, mobile data). At join, each account's group is read: every network it ever used, every
 * account ever seen on those, and so on for {@code link.depth} steps. Claims and "who earns" are
 * then checked across the whole group, not only the current network.
 * <ul>
 *   <li>{@link #earns}: of the accounts online from one network, only the first
 *       {@code max-accounts-per-ip} (by join time) earn from invest income, keyall and GG waves.</li>
 *   <li>{@link #claim}: a milestone reward (kill rewards, playtime rewards), the daily reward or an
 *       investment can be taken by one account per network (for daily: per cooldown).</li>
 *   <li>Kill farming: kills of an account on the same network, and repeat kills of the same victim
 *       within a cooldown, don't count (vanilla kill statistic, VexCore stats, kill rewards).</li>
 *   <li>/alts &lt;player&gt;: the accounts that have played from the same network.</li>
 * </ul>
 * Other features call the static helpers; with this feature off every check allows everything.
 */
public final class IpProtectionFeature extends Feature implements Listener {

    public enum Result {ALLOWED, TAKEN, LOADING}

    private record Claim(UUID owner, String ownerName, long time) {
    }

    /** Online accounts: network of each, and the accounts of each network in join order. */
    private final Map<UUID, String> networkOf = new ConcurrentHashMap<>();
    private final Map<String, List<UUID>> online = new ConcurrentHashMap<>();
    /** Claims per network ("feature:reward" -> latest claim), read for every network of a joining account's group. */
    private final Map<String, Map<String, Claim>> claims = new ConcurrentHashMap<>();

    /** An online account's all-time group: every linked network and account (itself included). */
    private record Group(Set<String> networks, Set<UUID> accounts) {
    }

    private final Map<UUID, Group> groups = new ConcurrentHashMap<>();
    /** Join order of online accounts (who came first earns first). */
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong joins = new java.util.concurrent.atomic.AtomicLong();
    private int linkDepth, linkMaxAccounts, linkMaxNetworks;
    /** Kill farming: last counted kill per killer->victim, and victims whose death didn't count. */
    private final Map<String, Long> lastKill = new ConcurrentHashMap<>();
    private final Set<UUID> voided = ConcurrentHashMap.newKeySet();
    private final Set<String> exempt = new HashSet<>();
    private String salt;
    private int maxAccounts;
    private String bypass;

    @Override
    protected void enable() {
        salt = salt();
        maxAccounts = Math.max(1, config().getInt("max-accounts-per-ip", 1));
        bypass = config().getString("bypass-permission", "vexcore.ipprotection.bypass");
        boolean linkAll = config().getBoolean("link.all-time", true);
        linkDepth = linkAll ? Math.max(1, Math.min(5, config().getInt("link.depth", 2))) : 0;
        linkMaxAccounts = Math.max(2, config().getInt("link.max-accounts", 40));
        linkMaxNetworks = Math.max(1, config().getInt("link.max-networks", 60));
        exempt.clear();
        for (String ip : config().getStringList("exempt-ips")) if (!ip.isBlank()) exempt.add(hash(ip.strip()));
        db().schema("ip_accounts", "CREATE TABLE IF NOT EXISTS {t} (ip VARCHAR(64) NOT NULL, uuid VARCHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL, last_seen BIGINT NOT NULL, PRIMARY KEY (ip, uuid))");
        db().index("ip_accounts", "uuid");
        db().schema("ip_claims", "CREATE TABLE IF NOT EXISTS {t} (ip VARCHAR(64) NOT NULL, claim VARCHAR(96) NOT NULL, "
                + "uuid VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, time BIGINT NOT NULL, PRIMARY KEY (ip, claim))");
        listen(this);
        for (Player p : Bukkit.getOnlinePlayers()) joined(p); // after /vexcore reload
        command("alts", this::alts, (s, a) -> a.length == 1 ? null : List.of());
        every(20 * 60 * 5, () -> { // old kill cooldowns
            long cutoff = System.currentTimeMillis() - cooldownMs();
            lastKill.values().removeIf(t -> t < cutoff);
        });
    }

    @Override
    protected void disable() {
        networkOf.clear();
        online.clear();
        claims.clear();
        groups.clear();
        joinedAt.clear();
        lastKill.clear();
        voided.clear();
    }

    // ── Static helpers for other features ─────────────────────────────────

    private static IpProtectionFeature active() {
        VexCore core = VexCore.get();
        return core != null && core.features().get("ipprotection") instanceof IpProtectionFeature f ? f : null;
    }

    /**
     * Whether this account may earn {@code system} rewards right now: true unless another account
     * from the same network joined earlier and already uses the network's allowance.
     */
    public static boolean earns(Player player, String system) {
        IpProtectionFeature f = active();
        return f == null || f.earnsHere(player, system);
    }

    /**
     * Takes {@code reward} of {@code system} for the player's network. TAKEN when another account of
     * the network has it ({@code windowMs} &gt; 0: took it within that long). LOADING when the
     * network's claims aren't read yet; ALLOWED otherwise (and it is recorded).
     */
    public static Result claim(Player player, String system, String reward, long windowMs) {
        IpProtectionFeature f = active();
        return f == null ? Result.ALLOWED : f.claimHere(player, system, reward, windowMs);
    }

    /** Tells the player why a check said no (the matching message of this feature). */
    public static void deny(Player player, Result result, String system) {
        IpProtectionFeature f = active();
        if (f == null || result == Result.ALLOWED) return;
        if (result == Result.LOADING) f.msg(player, "data-loading");
        else f.msg(player, "claimed-elsewhere", "system", f.systemName(system));
    }

    /** Tells the player they don't earn this on this account. */
    public static void notEarning(Player player, String system) {
        IpProtectionFeature f = active();
        if (f != null) f.msg(player, "not-earning", "system", f.systemName(system), "max", f.maxAccounts);
    }

    /** A key for the player's network, or null when nothing is limited for them. */
    public static String network(Player player, String system) {
        IpProtectionFeature f = active();
        if (f == null || !f.protects(system) || f.free(player)) return null;
        return f.networkOf.get(player.getUniqueId());
    }

    /**
     * Whether an account linked to this player (all time, any network) is in {@code used}: TAKEN
     * if so, LOADING while the player's links are still being read, else ALLOWED. The player
     * themself never counts. Giveaway entries use it: they are in the database, so the check
     * holds across restarts.
     */
    public static Result linked(Player player, String system, java.util.Collection<UUID> used) {
        IpProtectionFeature f = active();
        return f == null ? Result.ALLOWED : f.linkedHere(player, system, used);
    }

    /** Whether IP protection is on (IP bans need it). */
    public static boolean available() {
        return active() != null;
    }

    /** The hashed network of an address (for IP bans at login), or null with this feature off. */
    public static String hashAddress(String ip) {
        IpProtectionFeature f = active();
        return f == null || ip == null ? null : f.hash(ip);
    }

    /** The network of an online player, or null (feature off, or unknown). */
    public static String currentNetwork(UUID player) {
        IpProtectionFeature f = active();
        return f == null ? null : f.networkOf.get(player);
    }

    /** Whether the death of {@code victim} was not counted as a kill (kill farming). */
    public static boolean voidedKill(Player victim) {
        IpProtectionFeature f = active();
        return f != null && f.voided.contains(victim.getUniqueId());
    }

    // ── Rules ─────────────────────────────────────────────────────────────

    private boolean protects(String system) {
        return config().getBoolean("protect." + system, true);
    }

    private String systemName(String system) {
        return config().getString("names." + system, system);
    }

    /** Players this feature leaves alone: bypass permission or an exempt network. */
    private boolean free(Player p) {
        if (!bypass.isEmpty() && p.hasPermission(bypass)) return true;
        String net = networkOf.get(p.getUniqueId());
        return net == null || exempt.contains(net);
    }

    private boolean earnsHere(Player p, String system) {
        if (!protects(system) || free(p)) return true;
        UUID me = p.getUniqueId();
        Long mine = joinedAt.get(me);
        if (mine == null) return true;
        // Everyone online who is linked: same network now, or linked in the group (all time).
        Set<UUID> rivals = new HashSet<>();
        List<UUID> here = online.get(networkOf.get(me));
        if (here != null) rivals.addAll(here);
        Group g = groups.get(me);
        if (g != null) rivals.addAll(g.accounts);
        int place = 0;
        for (UUID id : rivals) {
            if (id.equals(me)) continue;
            Long theirs = joinedAt.get(id);
            if (theirs == null || theirs > mine) continue; // offline, or came after me
            Player other = Bukkit.getPlayer(id);
            if (other == null || (!bypass.isEmpty() && other.hasPermission(bypass))) continue; // bypassers don't use up places
            if (++place >= maxAccounts) return false;
        }
        return true;
    }

    private Result linkedHere(Player p, String system, java.util.Collection<UUID> used) {
        if (!protects(system) || free(p) || used.isEmpty()) return Result.ALLOWED;
        UUID me = p.getUniqueId();
        List<UUID> here = online.get(networkOf.get(me));
        if (here != null) for (UUID id : here) if (!id.equals(me) && used.contains(id)) return Result.TAKEN;
        Group g = groups.get(me);
        if (g == null) return Result.LOADING;
        for (UUID id : g.accounts) if (!id.equals(me) && used.contains(id)) return Result.TAKEN;
        return Result.ALLOWED;
    }

    private Result claimHere(Player p, String system, String reward, long windowMs) {
        if (!protects(system) || free(p)) return Result.ALLOWED;
        String net = networkOf.get(p.getUniqueId());
        Group g = groups.get(p.getUniqueId());
        Map<String, Claim> known = claims.get(net);
        if (known == null || g == null) return Result.LOADING;
        String key = system + ":" + reward;
        long now = System.currentTimeMillis();
        synchronized (claims) {
            // Taken by any linked account, on any network the group ever used.
            for (String n : g.networks) {
                Map<String, Claim> there = claims.get(n);
                Claim c = there == null ? null : there.get(key);
                if (c != null && !c.owner.equals(p.getUniqueId()) && (windowMs <= 0 || now - c.time < windowMs)) return Result.TAKEN;
            }
            Claim mine = new Claim(p.getUniqueId(), p.getName(), now);
            known.put(key, mine);
            Database db = db();
            db.queue("ip claim", conn -> {
                try (PreparedStatement ps = conn.prepareStatement(db.upsert("ip_claims", new String[]{"ip", "claim"}, "uuid", "name", "time"))) {
                    ps.setString(1, net);
                    ps.setString(2, key);
                    ps.setString(3, mine.owner.toString());
                    ps.setString(4, mine.ownerName);
                    ps.setLong(5, mine.time);
                    ps.executeUpdate();
                }
            });
        }
        return Result.ALLOWED;
    }

    // ── Tracking ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        joined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        joinedAt.remove(id);
        Group gone = groups.remove(id);
        String net = networkOf.remove(id);
        if (net == null) return;
        List<UUID> list = online.get(net);
        if (list != null) {
            list.remove(id);
            if (list.isEmpty()) online.remove(net, list);
        }
        // Claims of networks nobody online is linked to any more are read again when needed.
        Set<String> check = new HashSet<>();
        check.add(net);
        if (gone != null) check.addAll(gone.networks);
        for (Group g : groups.values()) check.removeAll(g.networks);
        check.removeAll(online.keySet());
        synchronized (claims) {
            for (String n : check) claims.remove(n);
        }
    }

    private void joined(Player p) {
        InetSocketAddress address = p.getAddress();
        if (address == null || address.getAddress() == null) return;
        String net = hash(address.getAddress().getHostAddress());
        UUID id = p.getUniqueId();
        joinedAt.putIfAbsent(id, joins.incrementAndGet());
        networkOf.put(id, net);
        List<UUID> list = online.computeIfAbsent(net, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(id)) list.add(id);
        String name = p.getName();
        long now = System.currentTimeMillis();
        Database db = db();
        db.queue("ip account", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("ip_accounts", new String[]{"ip", "uuid"}, "name", "last_seen"))) {
                ps.setString(1, net);
                ps.setString(2, id.toString());
                ps.setString(3, name);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
        });
        // Queued after the row above, so this account's own networks are already in the table.
        db.query("ip links", c -> {
            Link link = link(c, Set.of(id), exempt.contains(net) ? Set.of() : Set.of(net), linkDepth);
            Map<String, Map<String, Claim>> found = new HashMap<>();
            Set<String> read = new HashSet<>(link.networks);
            read.add(net);
            for (List<String> part : chunks(new ArrayList<>(read))) {
                try (PreparedStatement ps = c.prepareStatement("SELECT ip, claim, uuid, name, time FROM " + db.table("ip_claims")
                        + " WHERE ip IN (" + marks(part.size()) + ")")) {
                    for (int i = 0; i < part.size(); i++) ps.setString(i + 1, part.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) found.computeIfAbsent(rs.getString(1), k -> new ConcurrentHashMap<>())
                                .put(rs.getString(2), new Claim(UUID.fromString(rs.getString(3)), rs.getString(4), rs.getLong(5)));
                    }
                }
            }
            for (String n : read) found.computeIfAbsent(n, k -> new ConcurrentHashMap<>());
            return Map.entry(link, found);
        }).whenComplete((result, error) -> {
            if (result == null || !net.equals(networkOf.get(id))) return; // failed, or already gone
            synchronized (claims) {
                result.getValue().forEach(claims::putIfAbsent);
            }
            Link link = result.getKey();
            Set<String> nets = ConcurrentHashMap.newKeySet();
            nets.addAll(link.networks);
            if (!exempt.contains(net)) nets.add(net);
            Set<UUID> accounts = ConcurrentHashMap.newKeySet();
            accounts.addAll(link.accounts);
            accounts.add(id);
            groups.put(id, new Group(nets, accounts));
            // Accounts already online learn about this one (a brand-new link made just now).
            for (UUID other : accounts) {
                Group theirs = other.equals(id) ? null : groups.get(other);
                if (theirs != null) {
                    theirs.accounts.add(id);
                    theirs.networks.addAll(nets);
                }
            }
        });
    }

    // ── Links (all time) ──────────────────────────────────────────────────

    private record Link(Set<String> networks, Set<UUID> accounts) {
    }

    /**
     * Walks ip_accounts from {@code startAccounts}/{@code startNetworks}: the networks those
     * accounts ever used, the accounts ever seen there, and again, {@code link.depth} times.
     * Exempt networks are never followed. Stops early at the configured caps (a shared network
     * such as a school would otherwise pull in everyone). Database thread.
     */
    private Link link(java.sql.Connection c, Set<UUID> startAccounts, Set<String> startNetworks, int depth) throws java.sql.SQLException {
        Set<UUID> accounts = new java.util.LinkedHashSet<>(startAccounts);
        Set<String> networks = new java.util.LinkedHashSet<>(startNetworks);
        Set<UUID> newAccounts = new HashSet<>(startAccounts);
        Set<String> newNetworks = new HashSet<>(startNetworks);
        Database db = db();
        // Each step: first the networks the newest accounts ever used, then everyone seen on them.
        // Depth 1 = direct alts (shared any network, ever); 2 = alts of alts; 0 = current network only.
        for (int step = 0; step < Math.max(1, depth); step++) {
            if (depth > 0 && !newAccounts.isEmpty()) {
                for (List<String> part : chunks(newAccounts.stream().map(UUID::toString).toList())) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT ip FROM " + db.table("ip_accounts")
                            + " WHERE uuid IN (" + marks(part.size()) + ")")) {
                        for (int i = 0; i < part.size(); i++) ps.setString(i + 1, part.get(i));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String n = rs.getString(1);
                                if (!exempt.contains(n) && networks.size() < linkMaxNetworks && networks.add(n)) newNetworks.add(n);
                            }
                        }
                    }
                }
            }
            newAccounts = new HashSet<>();
            for (List<String> part : chunks(new ArrayList<>(newNetworks))) {
                try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT uuid FROM " + db.table("ip_accounts")
                        + " WHERE ip IN (" + marks(part.size()) + ")")) {
                    for (int i = 0; i < part.size(); i++) ps.setString(i + 1, part.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            UUID a = UUID.fromString(rs.getString(1));
                            if (accounts.size() < linkMaxAccounts && accounts.add(a)) newAccounts.add(a);
                        }
                    }
                }
            }
            newNetworks = new HashSet<>();
            if (newAccounts.isEmpty() || accounts.size() >= linkMaxAccounts || networks.size() >= linkMaxNetworks) break;
        }
        return new Link(networks, accounts);
    }

    private static List<List<String>> chunks(List<String> all) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 500) out.add(all.subList(i, Math.min(all.size(), i + 500)));
        return out;
    }

    private static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    // ── Kill farming ──────────────────────────────────────────────────────

    private long cooldownMs() {
        return Math.max(0, config().getLong("kill-farming.same-victim-cooldown-minutes", 10)) * 60_000L;
    }

    /** Runs first, so StatsFeature (MONITOR) already sees the decision. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        voided.remove(victim.getUniqueId());
        if (killer == null || killer.equals(victim) || !protects("kills")) return;
        if (!bypass.isEmpty() && killer.hasPermission(bypass)) return;
        String reason = null;
        String a = networkOf.get(killer.getUniqueId()), b = networkOf.get(victim.getUniqueId());
        if (!config().getBoolean("kill-farming.count-same-ip-kills", false) && a != null && a.equals(b) && !exempt.contains(a)) {
            reason = "same-network";
        } else {
            long cooldown = cooldownMs();
            String pair = killer.getUniqueId() + ">" + victim.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastKill.get(pair);
            if (cooldown > 0 && last != null && now - last < cooldown) reason = "same-victim";
            else lastKill.put(pair, now);
        }
        if (reason == null) return;
        voided.add(victim.getUniqueId());
        // The game adds the kill to the killer's statistic after this event: take it off again
        // on the killer's next tick, only if it really went up.
        int before = killer.getStatistic(Statistic.PLAYER_KILLS);
        String why = reason;
        Scheduler.entityLater(killer, () -> {
            int now = killer.getStatistic(Statistic.PLAYER_KILLS);
            if (now > before) killer.decrementStatistic(Statistic.PLAYER_KILLS, now - before);
            msg(killer, "kill-not-counted-" + why, "player", victim.getName(),
                    "minutes", config().getLong("kill-farming.same-victim-cooldown-minutes", 10));
        }, 1);
        Scheduler.globalLater(() -> voided.remove(victim.getUniqueId()), 2);
    }

    // ── /alts ─────────────────────────────────────────────────────────────

    private void alts(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, "alts");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (target == null) {
            msg(sender, "unknown-player", "player", args[0]);
            return;
        }
        UUID targetId = target.getUniqueId();
        String shown = target.getName() == null ? args[0] : target.getName();
        Database db = db();
        db.query("alts", c -> {
            // Every account linked all time (shared networks, then alts of alts up to link.depth).
            int depth = Math.max(1, linkDepth);
            Link link = link(c, Set.of(targetId), Set.of(), depth);
            Set<UUID> others = new java.util.LinkedHashSet<>(link.accounts);
            others.remove(targetId);
            // Direct = shared a network with the target itself; the rest came through another alt.
            Set<UUID> direct = depth == 1 ? link.accounts : link(c, Set.of(targetId), Set.of(), 1).accounts;
            List<String[]> out = new ArrayList<>();
            List<String> ids = others.stream().map(UUID::toString).toList();
            for (List<String> part : chunks(ids)) {
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, MAX(last_seen) FROM " + db.table("ip_accounts")
                        + " WHERE uuid IN (" + marks(part.size()) + ") GROUP BY uuid, name")) {
                    for (int i = 0; i < part.size(); i++) ps.setString(i + 1, part.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        Map<String, String[]> latest = new HashMap<>();
                        while (rs.next()) {
                            String[] row = {rs.getString(2), rs.getString(1), String.valueOf(rs.getLong(3)),
                                    String.valueOf(direct.contains(UUID.fromString(rs.getString(1))))};
                            String[] had = latest.get(row[1]);
                            if (had == null || Long.parseLong(had[2]) < Long.parseLong(row[2])) latest.put(row[1], row);
                        }
                        out.addAll(latest.values());
                    }
                }
            }
            out.sort((a, b) -> Long.compare(Long.parseLong(b[2]), Long.parseLong(a[2])));
            return out.size() > 50 ? out.subList(0, 50) : out;
        }).whenComplete((rows, error) -> {
            if (!plugin.isEnabled()) return;
            Scheduler.global(() -> {
                if (rows == null || rows.isEmpty()) {
                    msg(sender, "alts-none", "player", shown);
                    return;
                }
                msg(sender, "alts-header", "player", shown, "count", rows.size());
                for (String[] r : rows) {
                    boolean on = Bukkit.getPlayer(UUID.fromString(r[1])) != null;
                    long ago = Math.max(0, (System.currentTimeMillis() - Long.parseLong(r[2])) / 1000);
                    String how = config().getString(Boolean.parseBoolean(r[3]) ? "words.direct" : "words.indirect", "");
                    msg(sender, on ? "alts-line-online" : "alts-line", "player", r[0], "ago", plugin.messages().time(ago), "link", how);
                }
            });
        });
    }

    // ── Hashing ───────────────────────────────────────────────────────────

    /** A per-server random salt, so the stored hashes can't be matched against a list of IPs. */
    private String salt() {
        File file = plugin.files().data("ipprotection.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String s = yml.getString("salt", "");
        if (s == null || s.length() < 16) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            s = HexFormat.of().formatHex(bytes);
            yml.set("salt", s);
            try {
                yml.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save data/ipprotection.yml: " + e.getMessage());
            }
        }
        return s;
    }

    private String hash(String ip) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] out = sha.digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out, 0, 20); // 40 hex characters
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
