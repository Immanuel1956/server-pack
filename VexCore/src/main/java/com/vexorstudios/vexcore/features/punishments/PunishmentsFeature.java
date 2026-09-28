package com.vexorstudios.vexcore.features.punishments;

import com.vexorstudios.vexcore.core.Dates;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Messages;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.core.Time;
import com.vexorstudios.vexcore.core.Webhook;
import com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Punishments: /ban, /ipban, /mute, /kick, /warn (a duration makes it temporary: /ban Steve 7d
 * hacking; -s keeps it silent), /unban, /unmute, /unwarn, /history &lt;player&gt; (menu; click an
 * active one to lift it) and /punish &lt;player&gt; (menu of reasons, each with a ladder of durations
 * that climbs with every offence). Bans are checked at login, IP bans by network (needs IP
 * protection), mutes block chat and the muted-commands. Warnings can trigger automatic
 * punishments. Active bans and mutes are kept in memory and re-read every sync-seconds, so several
 * servers on one MySQL database see each other's.
 */
public final class PunishmentsFeature extends Feature implements Listener {

    public enum Type {BAN, IPBAN, MUTE, KICK, WARN}

    record Punishment(int id, Type type, UUID uuid, String name, String network, String reason, String template,
                      String staff, long created, long expires, boolean active, String removedBy, long removedAt) {

        boolean permanent() {
            return expires <= 0;
        }

        /** In force right now: not lifted and not run out. Kicks never are. */
        boolean inForce() {
            return active && type != Type.KICK && (expires <= 0 || expires > System.currentTimeMillis());
        }
    }

    private static final String COLUMNS = "id, type, uuid, name, network, reason, template, staff, created, expires, active, removed_by, removed_at";

    private final Map<UUID, Punishment> bans = new ConcurrentHashMap<>();
    private final Map<String, Punishment> networkBans = new ConcurrentHashMap<>();
    private final Map<UUID, Punishment> mutes = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    @Override
    protected void enable() {
        listen(this);
        db().schema("punishments", "CREATE TABLE IF NOT EXISTS {t} (id INT NOT NULL PRIMARY KEY, type VARCHAR(8) NOT NULL, "
                + "uuid VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, network VARCHAR(64) NOT NULL, reason TEXT NOT NULL, "
                + "template VARCHAR(32) NOT NULL, staff VARCHAR(32) NOT NULL, created BIGINT NOT NULL, expires BIGINT NOT NULL, "
                + "active INT NOT NULL, removed_by VARCHAR(32), removed_at BIGINT)");
        db().index("punishments", "uuid");
        db().index("punishments", "active");
        sync();
        long seconds = config().getLong("sync-seconds", 60);
        if (seconds > 0) every(seconds * 20, this::sync);
        every(20 * 10, this::expire);

        for (Type t : new Type[]{Type.BAN, Type.IPBAN, Type.MUTE, Type.KICK, Type.WARN}) {
            String id = t.name().toLowerCase(Locale.ROOT);
            command(id, (s, l, a) -> punishCommand(s, t, a), (s, a) -> a.length == 1 ? null : a.length == 2 && t != Type.KICK && t != Type.WARN
                    ? List.of("1h", "1d", "7d", "30d", "perm") : List.of());
        }
        command("unban", (s, l, a) -> liftCommand(s, a, Type.BAN), (s, a) -> a.length == 1 ? bannedNames() : List.of());
        command("unmute", (s, l, a) -> liftCommand(s, a, Type.MUTE), (s, a) -> a.length == 1 ? null : List.of());
        command("unwarn", (s, l, a) -> liftCommand(s, a, Type.WARN), (s, a) -> a.length == 1 ? null : List.of());
        command("history", this::historyCommand, (s, a) -> a.length == 1 ? null : List.of());
        command("punish", this::punishMenuCommand, (s, a) -> a.length == 1 ? null : List.of());
        placeholder("punish_muted", (p, a) -> String.valueOf(activeMute(p.getUniqueId()) != null));
    }

    @Override
    protected void disable() {
        bans.clear();
        networkBans.clear();
        mutes.clear();
    }

    // ── Cache ─────────────────────────────────────────────────────────────

    /** Re-reads the active bans and mutes (other servers on the same database add some too). */
    private void sync() {
        String table = db().table("punishments");
        db().query("punishments active", c -> {
            List<Punishment> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM " + table
                    + " WHERE active = 1 AND type IN ('BAN', 'IPBAN', 'MUTE') AND (expires = 0 OR expires > ?)")) {
                ps.setLong(1, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
            return out;
        }).whenComplete((list, error) -> {
            if (list == null) return;
            Map<UUID, Punishment> b = new HashMap<>(), m = new HashMap<>();
            Map<String, Punishment> n = new HashMap<>();
            for (Punishment p : list) {
                switch (p.type) {
                    case BAN -> b.put(p.uuid, p);
                    case IPBAN -> {
                        b.put(p.uuid, p);
                        if (!p.network.isEmpty()) n.put(p.network, p);
                    }
                    case MUTE -> m.put(p.uuid, p);
                    default -> {
                    }
                }
            }
            bans.keySet().retainAll(b.keySet());
            bans.putAll(b);
            networkBans.keySet().retainAll(n.keySet());
            networkBans.putAll(n);
            mutes.keySet().retainAll(m.keySet());
            mutes.putAll(m);
            loaded = true;
        });
    }

    /** Drops bans and mutes that ran out; tells players whose mute ended. */
    private void expire() {
        bans.values().removeIf(p -> !p.inForce());
        networkBans.values().removeIf(p -> !p.inForce());
        for (Punishment p : List.copyOf(mutes.values())) {
            if (p.inForce()) continue;
            mutes.remove(p.uuid, p);
            Player online = Bukkit.getPlayer(p.uuid);
            if (online != null) msg(online, "mute-expired");
        }
    }

    private static Punishment read(ResultSet rs) throws SQLException {
        return new Punishment(rs.getInt(1), Type.valueOf(rs.getString(2)), UUID.fromString(rs.getString(3)), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getLong(10),
                rs.getInt(11) == 1, rs.getString(12), rs.getLong(13));
    }

    private Punishment activeMute(UUID id) {
        Punishment p = mutes.get(id);
        return p != null && p.inForce() ? p : null;
    }

    private Punishment activeBan(UUID id) {
        Punishment p = bans.get(id);
        return p != null && p.inForce() ? p : null;
    }

    private List<String> bannedNames() {
        List<String> out = new ArrayList<>();
        for (Punishment p : bans.values()) if (p.inForce()) out.add(p.name);
        return out;
    }

    // ── Enforcement ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        Punishment ban = activeBan(event.getUniqueId());
        if (ban == null && event.getAddress() != null) {
            String network = IpProtectionFeature.hashAddress(event.getAddress().getHostAddress());
            Punishment byNetwork = network == null ? null : networkBans.get(network);
            if (byNetwork != null && byNetwork.inForce()) ban = byNetwork;
        }
        if (ban != null) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen(ban));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Punishment mute = activeMute(event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        msg(event.getPlayer(), "you-are-muted", placeholders(mute));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Punishment mute = activeMute(event.getPlayer().getUniqueId());
        if (mute == null) return;
        String label = com.vexorstudios.vexcore.core.Commands.label(event.getMessage());
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!config().getStringList("muted-commands").contains(label)) return;
        event.setCancelled(true);
        msg(event.getPlayer(), "you-are-muted", placeholders(mute));
    }

    /** The disconnect screen of a ban or kick. */
    private Component screen(Punishment p) {
        String key = p.type == Type.KICK ? "kick" : p.permanent() ? "ban" : "tempban";
        List<String> lines = config().getStringList("screens." + key);
        Map<String, Object> ph = placeholders(p);
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(Text.parse(lines.get(i), null, ph));
        }
        return out;
    }

    // ── Commands ──────────────────────────────────────────────────────────

    /** Target, silent flag, duration (seconds, -1 = permanent, 0 = none given) and reason. */
    private record Parsed(String target, boolean silent, long seconds, String reason) {
    }

    private Parsed parse(String[] args, boolean timed) {
        List<String> rest = new ArrayList<>();
        boolean silent = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-s")) silent = true;
            else rest.add(args[i]);
        }
        long seconds = 0;
        if (timed && !rest.isEmpty()) {
            String first = rest.getFirst().toLowerCase(Locale.ROOT);
            if (List.of("perm", "permanent", "forever").contains(first)) {
                seconds = -1;
                rest.removeFirst();
            } else if (!first.chars().allMatch(Character::isDigit) && Time.seconds(first) > 0) {
                seconds = Time.seconds(first);
                rest.removeFirst();
            }
        }
        String reason = String.join(" ", rest).strip();
        if (reason.isEmpty()) reason = config().getString("default-reason", "Breaking the rules");
        return new Parsed(args[0], silent, seconds, clean(reason));
    }

    /** Plain text: no colour codes, tags or placeholders from what staff typed. */
    private static String clean(String s) {
        String out = s.replace("&", "").replace("§", "").replace("<", "").replace(">", "").replace("%", "").strip();
        return out.length() > 200 ? out.substring(0, 200) : out;
    }

    private String staffName(CommandSender sender) {
        return sender instanceof Player p ? p.getName() : config().getString("console-name", "Console");
    }

    private void punishCommand(CommandSender sender, Type type, String[] args) {
        if (args.length == 0) {
            usage(sender, type.name().toLowerCase(Locale.ROOT));
            return;
        }
        Parsed in = parse(args, type == Type.BAN || type == Type.IPBAN || type == Type.MUTE);
        long seconds = in.seconds == 0 ? -1 : in.seconds; // no duration: permanent
        apply(sender, type, in.target, seconds, in.reason, "", in.silent);
    }

    /**
     * Punishes {@code targetName}. {@code seconds} -1 = permanent (bans and mutes). Checks rights,
     * exemption and existing punishments, saves, then announces and enforces.
     */
    void apply(CommandSender sender, Type type, String targetName, long seconds, String reason, String template, boolean silent) {
        if (!loaded) {
            msg(sender, "loading");
            return;
        }
        if ((type == Type.BAN || type == Type.IPBAN || type == Type.MUTE) && seconds < 0
                && !sender.hasPermission(config().getString("permissions.permanent", "vexcore.punish.permanent"))) {
            msg(sender, "no-permanent");
            return;
        }
        long max = Time.seconds(config().getString("max-temporary", ""));
        if (seconds > 0 && max > 0 && seconds > max && !sender.hasPermission(config().getString("permissions.permanent", "vexcore.punish.permanent"))) {
            msg(sender, "too-long", "max", plugin.messages().time(max));
            return;
        }
        Player online = Bukkit.getPlayerExact(targetName);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null || target.getName() == null) {
            msg(sender, "unknown-player", "player", targetName);
            return;
        }
        if (type == Type.KICK && online == null) {
            msg(sender, "player-not-found", "player", targetName);
            return;
        }
        if (sender instanceof Player && online != null && !online.equals(sender)
                && online.hasPermission(config().getString("permissions.exempt", "vexcore.punish.exempt"))) {
            msg(sender, "exempt", "player", online.getName());
            return;
        }
        UUID uuid = target.getUniqueId();
        if ((type == Type.BAN || type == Type.IPBAN) && activeBan(uuid) != null) {
            msg(sender, "already-banned", "player", target.getName());
            return;
        }
        if (type == Type.MUTE && activeMute(uuid) != null) {
            msg(sender, "already-muted", "player", target.getName());
            return;
        }
        if (type == Type.IPBAN && !IpProtectionFeature.available()) {
            msg(sender, "ipban-needs-ipprotection");
            return;
        }
        String name = target.getName();
        String staff = staffName(sender);
        long now = System.currentTimeMillis();
        long expires = type == Type.KICK || seconds < 0 ? 0 : now + seconds * 1000;
        if (type == Type.WARN) {
            long days = config().getLong("warnings.expire-days", 30);
            expires = days > 0 ? now + days * 86_400_000L : 0;
        }
        String network = type == Type.IPBAN ? IpProtectionFeature.currentNetwork(uuid) : null;
        long finalExpires = expires;
        String table = db().table("punishments");
        String ipTable = db().table("ip_accounts");
        db().query("punish", c -> {
            String net = network;
            if (type == Type.IPBAN && net == null) net = lastNetwork(c, ipTable, uuid);
            if (type == Type.IPBAN && net == null) return null; // never seen: nothing to ban
            int id = nextId(c, table);
            Punishment p = new Punishment(id, type, uuid, name, net == null ? "" : net, reason, template == null ? "" : template,
                    staff, now, finalExpires, type != Type.KICK, null, 0);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (" + COLUMNS
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0)")) {
                ps.setInt(1, p.id);
                ps.setString(2, p.type.name());
                ps.setString(3, p.uuid.toString());
                ps.setString(4, p.name);
                ps.setString(5, p.network);
                ps.setString(6, p.reason);
                ps.setString(7, p.template);
                ps.setString(8, p.staff);
                ps.setLong(9, p.created);
                ps.setLong(10, p.expires);
                ps.setInt(11, p.active ? 1 : 0);
                ps.executeUpdate();
            }
            return p;
        }).whenComplete((p, error) -> {
            if (!plugin.isEnabled()) return;
            Scheduler.global(() -> {
                if (!isEnabled()) return;
                if (p == null) {
                    msg(sender, error == null ? "ipban-no-network" : "failed", "player", name);
                    return;
                }
                applied(sender, p, silent);
            });
        });
    }

    private static String lastNetwork(Connection c, String ipTable, UUID uuid) {
        try (PreparedStatement ps = c.prepareStatement("SELECT ip FROM " + ipTable + " WHERE uuid = ? ORDER BY last_seen DESC")) {
            ps.setString(1, uuid.toString());
            ps.setMaxRows(1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException noTable) {
            return null;
        }
    }

    private static int nextId(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(id) FROM " + table)) {
            return rs.next() && rs.getObject(1) != null ? rs.getInt(1) + 1 : 1;
        }
    }

    /** Global thread: cache, enforce, announce, webhook, warning ladder. */
    private void applied(CommandSender sender, Punishment p, boolean silent) {
        switch (p.type) {
            case BAN -> bans.put(p.uuid, p);
            case IPBAN -> {
                bans.put(p.uuid, p);
                networkBans.put(p.network, p);
            }
            case MUTE -> mutes.put(p.uuid, p);
            default -> {
            }
        }
        Map<String, Object> ph = placeholders(p);
        // Kick everyone the punishment covers: the player, and for an IP ban their whole network.
        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean covered = online.getUniqueId().equals(p.uuid)
                    || (p.type == Type.IPBAN && p.network.equals(IpProtectionFeature.currentNetwork(online.getUniqueId())));
            if (!covered) continue;
            switch (p.type) {
                case BAN, IPBAN, KICK -> Scheduler.entity(online, () -> online.kick(screen(p)));
                case MUTE -> msg(online, "you-muted", ph);
                case WARN -> msg(online, "you-warned", ph);
            }
        }
        announce(p.type.name().toLowerCase(Locale.ROOT) + (p.permanent() || p.type == Type.KICK || p.type == Type.WARN ? "" : "-temp"), ph, silent);
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) msg(sender, "done", ph);
        Webhook.send(config().getConfigurationSection("webhook"), discord(ph, p));
        if (p.type == Type.WARN) warnLadder(p);
    }

    /** Public, or only to staff with -s. */
    private void announce(String key, Map<String, Object> ph, boolean silent) {
        Map<String, Object> all = new HashMap<>(ph);
        all.put("silent", silent ? config().getString("silent-tag", "&7[Silent] ") : "");
        if (!silent) {
            broadcast(Messages.everyone(), "announce." + key, all);
            return;
        }
        List<CommandSender> staff = new ArrayList<>();
        for (Player s : Bukkit.getOnlinePlayers()) if (s.hasPermission(config().getString("permissions.notify", "vexcore.punish.notify"))) staff.add(s);
        staff.add(Bukkit.getConsoleSender());
        broadcast(staff, "announce." + key, all);
    }

    /** warnings.actions: N active warnings run these console commands (usually a mute or ban). */
    private void warnLadder(Punishment warn) {
        ConfigurationSection actions = config().getConfigurationSection("warnings.actions");
        if (actions == null) return;
        String table = db().table("punishments");
        db().query("warn count", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table
                    + " WHERE uuid = ? AND type = 'WARN' AND active = 1 AND (expires = 0 OR expires > ?)")) {
                ps.setString(1, warn.uuid.toString());
                ps.setLong(2, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).whenComplete((count, error) -> {
            if (count == null || !plugin.isEnabled()) return;
            List<String> commands = actions.getStringList(String.valueOf(count));
            if (commands.isEmpty()) return;
            Map<String, Object> ph = Map.of("player", warn.name, "warnings", count);
            Scheduler.global(() -> {
                for (String line : commands) {
                    String command = Text.fill(line, ph).strip();
                    if (command.startsWith("/")) command = command.substring(1);
                    if (!command.isEmpty()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
            });
        });
    }

    // ── Lifting ───────────────────────────────────────────────────────────

    private void liftCommand(CommandSender sender, String[] args, Type type) {
        if (args.length == 0) {
            usage(sender, "un" + type.name().toLowerCase(Locale.ROOT));
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (target == null) {
            // Banned players are often not in the server's name cache any more.
            for (Punishment p : bans.values()) if (p.name.equalsIgnoreCase(args[0])) target = Bukkit.getOfflinePlayer(p.uuid);
        }
        if (target == null) {
            msg(sender, "unknown-player", "player", args[0]);
            return;
        }
        UUID uuid = target.getUniqueId();
        String name = target.getName() == null ? args[0] : target.getName();
        boolean silent = List.of(args).contains("-s");
        String staff = staffName(sender);
        String table = db().table("punishments");
        String types = type == Type.BAN ? "'BAN', 'IPBAN'" : "'" + type.name() + "'";
        db().query("lift", c -> {
            // Warnings: the newest one. Bans and mutes: every one in force.
            String where = " WHERE uuid = ? AND type IN (" + types + ") AND active = 1 AND (expires = 0 OR expires > ?)";
            List<Integer> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + table + where + " ORDER BY id DESC")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getInt(1));
                        if (type == Type.WARN) break;
                    }
                }
            }
            markLifted(c, table, ids, staff);
            return ids.size();
        }).whenComplete((count, error) -> {
            if (!plugin.isEnabled()) return;
            Scheduler.global(() -> {
                if (count == null || count == 0) {
                    msg(sender, switch (type) {
                        case BAN, IPBAN -> "not-banned";
                        case MUTE -> "not-muted";
                        default -> "no-warnings";
                    }, "player", name);
                    return;
                }
                lifted(uuid, type);
                Map<String, Object> ph = Map.of("player", name, "staff", staff);
                announce("un" + type.name().toLowerCase(Locale.ROOT), ph, silent);
                if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) msg(sender, "lifted", ph);
                Webhook.send(config().getConfigurationSection("webhook-lifted"), Map.of("player", name, "staff", staff,
                        "type", type.name().toLowerCase(Locale.ROOT)));
            });
        });
    }

    private static void markLifted(Connection c, String table, List<Integer> ids, String staff) throws SQLException {
        if (ids.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET active = 0, removed_by = ?, removed_at = ? WHERE id = ?")) {
            for (int id : ids) {
                ps.setString(1, staff);
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void lifted(UUID uuid, Type type) {
        if (type == Type.BAN) {
            Punishment old = bans.remove(uuid);
            if (old != null && !old.network.isEmpty()) networkBans.remove(old.network);
        } else if (type == Type.MUTE) {
            mutes.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) msg(online, "you-unmuted");
        }
    }

    /** Lifts one punishment by id (the history menu). */
    private void liftOne(Player staff, Punishment p, Runnable then) {
        String table = db().table("punishments");
        db().queue("lift one", c -> markLifted(c, table, List.of(p.id), staff.getName()));
        if (p.type == Type.BAN || p.type == Type.IPBAN) {
            Punishment current = bans.get(p.uuid);
            if (current != null && current.id == p.id) bans.remove(p.uuid);
            if (!p.network.isEmpty()) networkBans.remove(p.network);
        }
        if (p.type == Type.MUTE) lifted(p.uuid, Type.MUTE);
        msg(staff, "lifted-one", placeholders(p));
        then.run();
    }

    // ── Menus ─────────────────────────────────────────────────────────────

    private void loadHistory(UUID uuid, java.util.function.Consumer<List<Punishment>> then) {
        String table = db().table("punishments");
        db().query("history", c -> {
            List<Punishment> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM " + table + " WHERE uuid = ? ORDER BY id DESC")) {
                ps.setString(1, uuid.toString());
                ps.setMaxRows(500);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
            return out;
        }).whenComplete((list, error) -> {
            if (list != null && plugin.isEnabled()) Scheduler.global(() -> then.accept(list));
        });
    }

    private OfflinePlayer known(CommandSender sender, String name) {
        OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null || target.getName() == null) {
            msg(sender, "unknown-player", "player", name);
            return null;
        }
        return target;
    }

    private void historyCommand(CommandSender sender, String label, String[] args) {
        Player staff = player(sender);
        if (staff == null) return;
        if (args.length == 0) {
            usage(staff, "history");
            return;
        }
        OfflinePlayer target = known(staff, args[0]);
        if (target != null) openHistory(staff, target.getUniqueId(), target.getName());
    }

    void openHistory(Player staff, UUID uuid, String name) {
        loadHistory(uuid, list -> Scheduler.entity(staff, () -> open(staff, "history", menu -> {
            menu.with("target", name).with("count", list.size());
            if (list.isEmpty()) menu.function("empty", c -> {
            });
            menu.function("punish", c -> openPunish(c.player(), uuid, name));
            boolean canLift = staff.hasPermission(config().getString("permissions.lift", "vexcore.punish.lift"));
            menu.paginate(list, (p, slot) -> menu.place(p.type.name().toLowerCase(Locale.ROOT), slot, placeholders(p), c -> {
                if (!canLift || !p.inForce() || !c.type().isRightClick()) return;
                liftOne(c.player(), p, () -> openHistory(c.player(), uuid, name));
            }));
        })));
    }

    private void punishMenuCommand(CommandSender sender, String label, String[] args) {
        Player staff = player(sender);
        if (staff == null) return;
        if (args.length == 0) {
            usage(staff, "punish");
            return;
        }
        OfflinePlayer target = known(staff, args[0]);
        if (target != null) openPunish(staff, target.getUniqueId(), target.getName());
    }

    /** The reasons in config.yml, each a button whose next duration climbs with earlier offences. */
    void openPunish(Player staff, UUID uuid, String name) {
        loadHistory(uuid, list -> Scheduler.entity(staff, () -> open(staff, "punish", menu -> {
            long warns = list.stream().filter(p -> p.type == Type.WARN && p.inForce()).count();
            Punishment ban = activeBan(uuid), mute = activeMute(uuid);
            menu.with("target", name).with("total", list.size()).with("warnings", warns)
                    .with("banned", ban == null ? config().getString("words.no", "&#97F900No") : Text.fill(config().getString("words.until", "&#FF3B3BYes &7(%left%)"), placeholders(ban)))
                    .with("muted", mute == null ? config().getString("words.no", "&#97F900No") : Text.fill(config().getString("words.until", "&#FF3B3BYes &7(%left%)"), placeholders(mute)));
            menu.function("history", c -> openHistory(c.player(), uuid, name));
            ConfigurationSection reasons = config().getConfigurationSection("reasons");
            List<String> keys = reasons == null ? List.of() : new ArrayList<>(reasons.getKeys(false));
            menu.paginate(keys, (key, slot) -> {
                ConfigurationSection r = reasons.getConfigurationSection(key);
                if (r == null) return;
                Type type;
                try {
                    type = Type.valueOf(r.getString("type", "MUTE").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException bad) {
                    return;
                }
                long offences = list.stream().filter(p -> key.equals(p.template)).count();
                List<String> ladder = r.getStringList("durations");
                String step = ladder.isEmpty() ? "perm" : ladder.get((int) Math.min(offences, ladder.size() - 1));
                long seconds = step.equalsIgnoreCase("perm") || step.equalsIgnoreCase("permanent") ? -1 : Time.seconds(step);
                Map<String, Object> ph = new HashMap<>();
                ph.put("reason", r.getString("reason", key));
                ph.put("type", config().getString("words." + type.name().toLowerCase(Locale.ROOT), type.name()));
                ph.put("offences", offences);
                ph.put("next", type == Type.KICK || type == Type.WARN ? "-" : seconds < 0
                        ? config().getString("words.permanent", "Permanent") : plugin.messages().time(Math.max(0, seconds)));
                ph.put("material", r.getString("material", "PAPER"));
                menu.place("reason", slot, ph, c -> {
                    String node = "vexcore.punish." + type.name().toLowerCase(Locale.ROOT);
                    if (!c.player().hasPermission(node)) {
                        msg(c.player(), "no-permission", "permission", node);
                        return;
                    }
                    c.player().closeInventory();
                    apply(c.player(), type, name, seconds, r.getString("reason", key), key, c.type().isRightClick());
                });
            });
        })));
    }

    // ── Placeholders ──────────────────────────────────────────────────────

    Map<String, Object> placeholders(Punishment p) {
        Map<String, Object> ph = new HashMap<>();
        long now = System.currentTimeMillis();
        String permanent = config().getString("words.permanent", "Permanent");
        ph.put("id", String.format(Locale.ROOT, "%04d", p.id));
        ph.put("player", p.name);
        ph.put("target", p.name);
        ph.put("staff", p.staff);
        ph.put("reason", p.reason);
        ph.put("type", config().getString("words." + p.type.name().toLowerCase(Locale.ROOT), p.type.name()));
        ph.put("date", Dates.format(p.created, config().getString("date-format", "dd.MM.yyyy HH:mm")));
        ph.put("duration", p.type == Type.KICK ? "-" : p.permanent() ? permanent : plugin.messages().time(Math.max(0, (p.expires - p.created) / 1000)));
        ph.put("expires", p.permanent() ? permanent : Dates.format(p.expires, config().getString("date-format", "dd.MM.yyyy HH:mm")));
        ph.put("left", p.permanent() ? permanent : plugin.messages().time(Math.max(0, (p.expires - now) / 1000)));
        String status = !p.active && p.removedBy != null ? Text.fill(config().getString("words.lifted", "&#97F900Lifted by %by%"), Map.of("by", p.removedBy))
                : p.type == Type.KICK ? config().getString("words.done", "&7Done")
                : p.inForce() ? config().getString("words.active", "&#FF3B3BActive") : config().getString("words.expired", "&7Expired");
        ph.put("status", status);
        ph.put("appeal", config().getString("appeal", ""));
        ph.put("server", config().getString("server-name", "survival"));
        return ph;
    }

    private Map<String, Object> discord(Map<String, Object> ph, Punishment p) {
        Map<String, Object> out = new HashMap<>(ph);
        out.put("reason", Webhook.escape(p.reason));
        out.put("type", p.type.name().toLowerCase(Locale.ROOT));
        return out;
    }
}
