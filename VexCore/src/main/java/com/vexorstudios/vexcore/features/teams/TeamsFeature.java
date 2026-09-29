package com.vexorstudios.vexcore.features.teams;

import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Pos;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.chatfilter.ChatFilterFeature;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teams: /team create|invite|join|leave|kick|promote|demote|transfer|rename|disband|info|list|
 * pvp|sethome|home|chat, and /teamchat (/tc). Roles: member, officer, leader. Team names go
 * through the chat filter's strictest name check plus the pattern and reserved names below.
 * Everything is written at once; teams are read into memory at start.
 */
public final class TeamsFeature extends Feature implements Listener {

    static final int MEMBER = 0, OFFICER = 1, LEADER = 2;

    // What a member may do, handed out by the leader in the member menu (as in LifestealCore).
    // Kept in the role column: role | HAS_PERMS | perms << 4, so no new column is needed; rows
    // written before this (plain 0/1/2) get the defaults for their role.
    static final int MANAGE = 1, PVP = 2, VISIT_HOME = 4, EDIT_HOME = 8, CHAT = 16, ALL = 31;
    private static final int HAS_PERMS = 8;

    static final class Team {
        final String id;
        volatile String name;
        volatile boolean pvp;
        volatile Pos home;
        final long created;
        final Map<UUID, Integer> members = new ConcurrentHashMap<>();
        final Map<UUID, String> names = new ConcurrentHashMap<>();
        final Map<UUID, Integer> perms = new ConcurrentHashMap<>();
        final Map<UUID, Long> joined = new ConcurrentHashMap<>();

        Team(String id, String name, boolean pvp, Pos home, long created) {
            this.id = id;
            this.name = name;
            this.pvp = pvp;
            this.home = home;
            this.created = created;
        }

        UUID leader() {
            for (Map.Entry<UUID, Integer> e : members.entrySet()) if (e.getValue() == LEADER) return e.getKey();
            return null;
        }
    }

    private final Map<String, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, Team> byMember = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> invites = new ConcurrentHashMap<>();
    private final Set<UUID> chatMode = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> disbandConfirm = new ConcurrentHashMap<>();
    private volatile boolean ready;

    @Override
    protected void enable() {
        db().schema("teams", "CREATE TABLE IF NOT EXISTS {t} (id VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, "
                + "pvp INT NOT NULL, home VARCHAR(255) NOT NULL, created BIGINT NOT NULL)");
        db().schema("team_members", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, team VARCHAR(36) NOT NULL, "
                + "role INT NOT NULL, name VARCHAR(32) NOT NULL)");
        db().schema("team_joined", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL PRIMARY KEY, joined BIGINT NOT NULL)");
        int defaults = defaultPerms();
        Database db = db();
        db.query("load teams", c -> {
            Map<String, Team> loaded = new java.util.HashMap<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name, pvp, home, created FROM " + db.table("teams"))) {
                while (rs.next()) loaded.put(rs.getString(1), new Team(rs.getString(1), rs.getString(2), rs.getInt(3) != 0, pos(rs.getString(4)), rs.getLong(5)));
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, team, role, name FROM " + db.table("team_members"))) {
                while (rs.next()) {
                    Team t = loaded.get(rs.getString(2));
                    if (t == null) continue;
                    UUID u = UUID.fromString(rs.getString(1));
                    int stored = rs.getInt(3);
                    int role = stored & 3;
                    t.members.put(u, role);
                    t.perms.put(u, (stored & HAS_PERMS) != 0 ? stored >> 4 : role >= OFFICER ? ALL : defaults);
                    t.names.put(u, rs.getString(4));
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, joined FROM " + db.table("team_joined"))) {
                Map<UUID, Long> joined = new java.util.HashMap<>();
                while (rs.next()) joined.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                for (Team t : loaded.values()) for (UUID u : t.members.keySet()) t.joined.put(u, joined.getOrDefault(u, t.created));
            }
            return loaded;
        }).thenAccept(loaded -> {
            for (Team t : loaded.values()) {
                if (t.members.isEmpty()) continue;
                teams.put(t.id, t);
                for (UUID u : t.members.keySet()) byMember.put(u, t);
            }
            ready = true;
        });
        listen(this);
        command("team", this::command, this::complete);
        command("teamchat", (sender, label, args) -> {
            Player p = player(sender);
            if (p == null) return;
            if (args.length == 0) toggleChat(p);
            else teamChat(p, String.join(" ", args));
        });
        placeholder("team", (p, a) -> {
            Team t = byMember.get(p.getUniqueId());
            return t == null ? config().getString("no-team", "") : t.name;
        });
        placeholder("team_tag", (p, a) -> {
            Team t = byMember.get(p.getUniqueId());
            return t == null ? "" : config().getString("chat-tag", "&7[&b%team%&7] ").replace("%team%", t.name);
        });
        placeholder("team_role", (p, a) -> {
            Team t = byMember.get(p.getUniqueId());
            return t == null ? "" : role(t.members.getOrDefault(p.getUniqueId(), MEMBER));
        });
        placeholder("team_members", (p, a) -> {
            Team t = byMember.get(p.getUniqueId());
            return t == null ? "0" : String.valueOf(t.members.size());
        });
        placeholder("team_online", (p, a) -> {
            Team t = byMember.get(p.getUniqueId());
            if (t == null) return "0";
            int n = 0;
            for (Player m : online(t)) if (!vanished(m)) n++; // vanished staff don't show as online
            return String.valueOf(n);
        });
    }

    /** The team name of a player, or null. For other features (nametags, chat). */
    public String teamOf(UUID player) {
        Team t = byMember.get(player);
        return t == null ? null : t.name;
    }

    public boolean sameTeam(UUID a, UUID b) {
        Team t = byMember.get(a);
        return t != null && t == byMember.get(b);
    }

    private String role(int role) {
        return config().getString("roles." + (role == LEADER ? "leader" : role == OFFICER ? "officer" : "member"),
                role == LEADER ? "Leader" : role == OFFICER ? "Officer" : "Member");
    }

    private static Pos pos(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(";");
        try {
            return new Pos(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Pos p) {
        return p == null ? "" : p.world() + ";" + p.x() + ";" + p.y() + ";" + p.z() + ";" + p.yaw() + ";" + p.pitch();
    }

    private boolean vanished(Player p) {
        return plugin.features().get("vanish") instanceof com.vexorstudios.vexcore.features.vanish.VanishFeature v && v.isVanished(p.getUniqueId());
    }

    private List<Player> online(Team t) {
        List<Player> out = new ArrayList<>();
        for (UUID u : t.members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) out.add(p);
        }
        return out;
    }

    private Team byName(String name) {
        for (Team t : teams.values()) if (t.name.equalsIgnoreCase(name)) return t;
        return null;
    }

    // ── Saving ────────────────────────────────────────────────────────────

    private void saveTeam(Team t) {
        Database db = db();
        String id = t.id, name = t.name, home = str(t.home);
        int pvp = t.pvp ? 1 : 0;
        long created = t.created;
        db.queue("team", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("teams", new String[]{"id"}, "name", "pvp", "home", "created"))) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setInt(3, pvp);
                ps.setString(4, home);
                ps.setLong(5, created);
                ps.executeUpdate();
            }
        });
    }

    private void saveMember(Team t, UUID member, int role, String name) {
        t.members.put(member, role);
        t.names.put(member, name);
        t.perms.putIfAbsent(member, role >= OFFICER ? ALL : defaultPerms());
        boolean fresh = t.joined.putIfAbsent(member, System.currentTimeMillis()) == null;
        long joined = t.joined.get(member);
        byMember.put(member, t);
        Database db = db();
        String team = t.id;
        int stored = role | HAS_PERMS | (t.perms.get(member) << 4);
        db.queue("team member", c -> {
            try (PreparedStatement ps = c.prepareStatement(db.upsert("team_members", new String[]{"uuid"}, "team", "role", "name"))) {
                ps.setString(1, member.toString());
                ps.setString(2, team);
                ps.setInt(3, stored);
                ps.setString(4, name);
                ps.executeUpdate();
            }
            if (fresh) try (PreparedStatement ps = c.prepareStatement(db.upsert("team_joined", new String[]{"uuid"}, "joined"))) {
                ps.setString(1, member.toString());
                ps.setLong(2, joined);
                ps.executeUpdate();
            }
        });
    }

    /** What new members may do (default-permissions in config.yml). */
    private int defaultPerms() {
        int p = 0;
        for (String name : config().getStringList("default-permissions")) p |= perm(name);
        return p;
    }

    private static int perm(String name) {
        return switch (name.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "manage-teammates", "manage" -> MANAGE;
            case "pvp" -> PVP;
            case "visit-home" -> VISIT_HOME;
            case "edit-home" -> EDIT_HOME;
            case "team-chat", "chat" -> CHAT;
            default -> 0;
        };
    }

    /** Whether a member may do {@code perm}; the leader always may. */
    boolean can(Team t, UUID member, int perm) {
        Integer role = t.members.get(member);
        if (role == null) return false;
        return role == LEADER || (t.perms.getOrDefault(member, 0) & perm) != 0;
    }

    /** Checks {@code perm} and says so when it's missing. */
    private boolean allowed(Player player, Team mine, int perm) {
        if (mine == null) {
            msg(player, "no-team");
            return false;
        }
        if (!can(mine, player.getUniqueId(), perm)) {
            msg(player, "not-allowed");
            return false;
        }
        return true;
    }

    /** Leader only: turns one of a member's rights on or off. */
    private void togglePerm(Team t, UUID member, int perm) {
        int now = t.perms.getOrDefault(member, 0) ^ perm;
        t.perms.put(member, now);
        saveMember(t, member, t.members.getOrDefault(member, MEMBER), t.names.getOrDefault(member, "?"));
    }

    private void removeMember(Team t, UUID member) {
        t.members.remove(member);
        t.names.remove(member);
        t.perms.remove(member);
        t.joined.remove(member);
        byMember.remove(member, t);
        chatMode.remove(member);
        Database db = db();
        db.queue("team leave", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("team_members") + " WHERE uuid = ?")) {
                ps.setString(1, member.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("team_joined") + " WHERE uuid = ?")) {
                ps.setString(1, member.toString());
                ps.executeUpdate();
            }
        });
    }

    private void deleteTeam(Team t) {
        teams.remove(t.id);
        for (UUID u : t.members.keySet()) {
            byMember.remove(u, t);
            chatMode.remove(u);
        }
        Database db = db();
        List<String> gone = new ArrayList<>();
        for (UUID u : t.members.keySet()) gone.add(u.toString());
        db.queue("team disband", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("team_joined") + " WHERE uuid = ?")) {
                for (String u : gone) {
                    ps.setString(1, u);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("team_members") + " WHERE team = ?")) {
                ps.setString(1, t.id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("teams") + " WHERE id = ?")) {
                ps.setString(1, t.id);
                ps.executeUpdate();
            }
        });
    }

    private void tell(Team t, String key, Map<String, ?> ph) {
        broadcast(online(t), key, ph);
    }

    // ── Names ─────────────────────────────────────────────────────────────

    /** Null if the name is fine, else the message key saying why not. */
    private String badName(Player player, String name) {
        int min = config().getInt("name.min-length", 3), max = config().getInt("name.max-length", 16);
        if (name.length() < min || name.length() > max) return "name-length";
        if (!name.matches(config().getString("name.pattern", "^[A-Za-z0-9_]+$"))) return "name-characters";
        // Whatever the pattern allows: team names show in chat, tags and placeholders everywhere,
        // so never tags, colour codes or placeholders.
        if (name.chars().anyMatch(ch -> "<>%&§\\{}".indexOf(ch) >= 0)) return "name-characters";
        // Whole words only: "mod" blocks "Mod" and "Mod_Team", not "Modern".
        String lower = name.toLowerCase(Locale.ROOT);
        List<String> words = java.util.Arrays.asList(lower.split("[_0-9]+"));
        for (String reserved : config().getStringList("name.blocked")) {
            String r = reserved.toLowerCase(Locale.ROOT);
            if (lower.equals(r) || words.contains(r)) return "name-reserved";
        }
        ChatFilterFeature filter = ChatFilterFeature.of(plugin);
        if (filter != null && !filter.cleanName(name)) return "name-filtered";
        Team other = byName(name);
        if (other != null && other != byMember.get(player.getUniqueId())) return "name-taken";
        return null;
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void command(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        if (!ready) {
            msg(player, "data-loading");
            return;
        }
        if (args.length == 0) { // the menu does everything
            openMenu(player);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String arg = args.length > 1 ? args[1] : null;
        Team mine = byMember.get(player.getUniqueId());
        int myRole = mine == null ? -1 : mine.members.getOrDefault(player.getUniqueId(), MEMBER);
        switch (sub) {
            case "create" -> create(player, mine, arg);
            case "invite" -> invite(player, mine, myRole, arg);
            case "join", "accept" -> join(player, mine, arg);
            case "leave" -> leave(player, mine, myRole);
            case "kick" -> kick(player, mine, myRole, arg);
            case "promote", "demote" -> promote(player, mine, myRole, arg, sub.equals("promote"));
            case "transfer" -> transfer(player, mine, myRole, arg);
            case "rename" -> rename(player, mine, myRole, arg);
            case "disband" -> disband(player, mine, myRole);
            case "pvp" -> togglePvp(player, mine);
            case "sethome" -> setHome(player, mine);
            case "delhome" -> delHome(player, mine);
            case "home" -> home(player, mine);
            case "menu", "gui" -> openMenu(player);
            case "chat" -> {
                if (args.length > 1) teamChat(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                else toggleChat(player);
            }
            case "list" -> list(player);
            case "info" -> info(player, arg == null ? mine : byName(arg), arg);
            default -> msg(player, "help");
        }
    }

    private void togglePvp(Player player, Team mine) {
        if (!allowed(player, mine, PVP)) return;
        mine.pvp = !mine.pvp;
        saveTeam(mine);
        tell(mine, mine.pvp ? "pvp-on" : "pvp-off", Map.of("player", player.getName()));
    }

    private void setHome(Player player, Team mine) {
        if (!allowed(player, mine, EDIT_HOME)) return;
        mine.home = Pos.of(player.getLocation());
        saveTeam(mine);
        tell(mine, "home-set", Map.of("player", player.getName()));
    }

    private void delHome(Player player, Team mine) {
        if (!allowed(player, mine, EDIT_HOME)) return;
        if (mine.home == null) {
            msg(player, "no-home");
            return;
        }
        mine.home = null;
        saveTeam(mine);
        tell(mine, "home-deleted", Map.of("player", player.getName()));
    }

    private void home(Player player, Team mine) {
        if (!allowed(player, mine, VISIT_HOME)) return;
        Pos home = mine.home;
        if (home == null) {
            msg(player, "no-home");
            return;
        }
        plugin.teleports().start(this, player, home::location, "vexcore.team.bypass", Map.of("team", mine.name), null);
    }

    private void create(Player player, Team mine, String name) {
        if (mine != null) {
            msg(player, "already-in-team");
            return;
        }
        if (name == null) {
            msg(player, "help");
            return;
        }
        String bad = badName(player, name);
        if (bad != null) {
            msg(player, bad, "min", config().getInt("name.min-length", 3), "max", config().getInt("name.max-length", 16));
            return;
        }
        double cost = config().getDouble("create-cost", 0);
        if (cost > 0 && !plugin.money().withdraw(player, cost)) {
            msg(player, "cannot-afford", "amount", plugin.money().format(cost));
            return;
        }
        Team t = new Team(UUID.randomUUID().toString(), name, false, null, System.currentTimeMillis());
        teams.put(t.id, t);
        saveTeam(t);
        saveMember(t, player.getUniqueId(), LEADER, player.getName());
        msg(player, "created", "team", name);
        if (config().getBoolean("broadcast-create", false)) broadcast(Bukkit.getOnlinePlayers(), "created-broadcast", Map.of("team", name, "player", player.getName()));
    }

    private void invite(Player player, Team mine, int role, String name) {
        if (!allowed(player, mine, MANAGE)) return;
        if (name == null) {
            usage(player, "team");
            return;
        }
        Player target = target(player, name);
        if (target == null) return;
        if (byMember.containsKey(target.getUniqueId())) {
            msg(player, "target-in-team", "player", target.getName());
            return;
        }
        if (mine.members.size() >= config().getInt("max-members", 8)) {
            msg(player, "team-full", "max", config().getInt("max-members", 8));
            return;
        }
        invites.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(mine.id, System.currentTimeMillis() + config().getLong("invite-seconds", 60) * 1000);
        String join = "/" + plugin.commands().name("team") + " join " + mine.name;
        msg(player, "invited", "player", target.getName());
        msg(target, "invite-received", "team", mine.name, "player", player.getName(), "command", join);
    }

    private void join(Player player, Team mine, String name) {
        if (mine != null) {
            msg(player, "already-in-team");
            return;
        }
        Team t = name == null ? null : byName(name);
        Map<String, Long> own = invites.get(player.getUniqueId());
        Long until = t == null || own == null ? null : own.get(t.id);
        if (until == null || until < System.currentTimeMillis()) {
            msg(player, "no-invite", "team", name == null ? "?" : name);
            return;
        }
        if (t.members.size() >= config().getInt("max-members", 8)) {
            msg(player, "team-full", "max", config().getInt("max-members", 8));
            return;
        }
        own.remove(t.id);
        saveMember(t, player.getUniqueId(), MEMBER, player.getName());
        tell(t, "joined", Map.of("player", player.getName(), "team", t.name));
    }

    private void leave(Player player, Team mine, int role) {
        if (mine == null) {
            msg(player, "no-team");
            return;
        }
        if (role == LEADER && mine.members.size() > 1) {
            msg(player, "leader-leave");
            return;
        }
        if (mine.members.size() <= 1) {
            deleteTeam(mine);
            msg(player, "disbanded", "team", mine.name);
            return;
        }
        removeMember(mine, player.getUniqueId());
        msg(player, "left", "team", mine.name);
        tell(mine, "member-left", Map.of("player", player.getName()));
    }

    private UUID member(Team t, String name) {
        for (Map.Entry<UUID, String> e : t.names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    private void kick(Player player, Team mine, int role, String name) {
        if (!allowed(player, mine, MANAGE)) return;
        if (name == null) {
            usage(player, "team");
            return;
        }
        UUID target = member(mine, name);
        if (target == null) {
            msg(player, "not-member", "player", name);
            return;
        }
        kick(player, mine, target);
    }

    private void kick(Player player, Team mine, UUID target) {
        String name = mine.names.getOrDefault(target, "?");
        // Never the leader, never yourself; an officer only by the leader.
        if (target.equals(player.getUniqueId()) || mine.members.getOrDefault(target, MEMBER) == LEADER
                || (mine.members.getOrDefault(target, MEMBER) == OFFICER && mine.members.getOrDefault(player.getUniqueId(), MEMBER) != LEADER)) {
            msg(player, "cant-kick");
            return;
        }
        removeMember(mine, target);
        tell(mine, "kicked", Map.of("player", name, "by", player.getName()));
        Player online = Bukkit.getPlayer(target);
        if (online != null) msg(online, "you-were-kicked", "team", mine.name);
    }

    private void promote(Player player, Team mine, int role, String name, boolean up) {
        if (mine == null) {
            msg(player, "no-team");
            return;
        }
        if (role != LEADER) {
            msg(player, "not-leader");
            return;
        }
        UUID target = name == null ? null : member(mine, name);
        if (target == null || target.equals(player.getUniqueId())) {
            msg(player, "not-member", "player", name == null ? "?" : name);
            return;
        }
        int now = up ? OFFICER : MEMBER;
        mine.perms.put(target, up ? ALL : defaultPerms());
        saveMember(mine, target, now, mine.names.get(target));
        tell(mine, up ? "promoted" : "demoted", Map.of("player", mine.names.get(target), "role", role(now)));
    }

    private void transfer(Player player, Team mine, int role, String name) {
        if (mine == null || role != LEADER) {
            msg(player, mine == null ? "no-team" : "not-leader");
            return;
        }
        UUID target = name == null ? null : member(mine, name);
        if (target == null || target.equals(player.getUniqueId())) {
            msg(player, "not-member", "player", name == null ? "?" : name);
            return;
        }
        mine.perms.put(player.getUniqueId(), ALL);
        saveMember(mine, target, LEADER, mine.names.get(target));
        saveMember(mine, player.getUniqueId(), OFFICER, player.getName());
        tell(mine, "transferred", Map.of("player", mine.names.get(target)));
    }

    private void rename(Player player, Team mine, int role, String name) {
        if (mine == null || role != LEADER) {
            msg(player, mine == null ? "no-team" : "not-leader");
            return;
        }
        if (name == null) {
            msg(player, "help");
            return;
        }
        String bad = badName(player, name);
        if (bad != null) {
            msg(player, bad, "min", config().getInt("name.min-length", 3), "max", config().getInt("name.max-length", 16));
            return;
        }
        String old = mine.name;
        mine.name = name;
        saveTeam(mine);
        tell(mine, "renamed", Map.of("old", old, "team", name));
    }

    private void disband(Player player, Team mine, int role) {
        if (mine == null || role != LEADER) {
            msg(player, mine == null ? "no-team" : "not-leader");
            return;
        }
        Long asked = disbandConfirm.remove(player.getUniqueId());
        if (asked == null || System.currentTimeMillis() - asked > config().getLong("disband-confirm-seconds", 10) * 1000) {
            disbandConfirm.put(player.getUniqueId(), System.currentTimeMillis());
            msg(player, "disband-confirm");
            return;
        }
        tell(mine, "disbanded", Map.of("team", mine.name));
        deleteTeam(mine);
    }

    private void list(Player player) {
        List<Team> list = new ArrayList<>(teams.values());
        list.sort(Comparator.comparingInt((Team t) -> -t.members.size()).thenComparing(t -> t.name.toLowerCase(Locale.ROOT)));
        msg(player, "list-header", "count", list.size());
        for (int i = 0; i < Math.min(Math.max(1, config().getInt("list-size", 10)), list.size()); i++) {
            Team t = list.get(i);
            int on = 0;
            for (Player m : online(t)) if (!vanished(m)) on++; // vanished staff don't show as online
            msg(player, "list-line", "place", i + 1, "team", t.name, "members", t.members.size(), "online", on);
        }
    }

    private void info(Player player, Team t, String asked) {
        if (t == null) {
            msg(player, asked == null ? "no-team" : "unknown-team", "team", asked == null ? "" : asked);
            return;
        }
        List<String> members = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : t.members.entrySet()) {
            Player member = Bukkit.getPlayer(e.getKey());
            boolean on = member != null && !vanished(member);
            members.add((on ? config().getString("online-color", "&a") : config().getString("offline-color", "&7"))
                    + (e.getValue() == LEADER ? "**" : e.getValue() == OFFICER ? "*" : "") + t.names.getOrDefault(e.getKey(), "?"));
        }
        UUID leader = t.leader();
        msg(player, "info", "team", t.name, "leader", leader == null ? "-" : t.names.getOrDefault(leader, "?"),
                "members", String.join(config().getString("members-separator", "&7, "), members), "count", t.members.size(), "max", config().getInt("max-members", 8),
                "pvp", t.pvp ? config().getString("pvp-on-text", "&aon") : config().getString("pvp-off-text", "&coff"),
                "created", new java.text.SimpleDateFormat(config().getString("date-format", "dd.MM.yyyy")).format(new java.util.Date(t.created)));
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("create", "invite", "join", "leave", "kick", "promote", "demote", "transfer",
                "rename", "disband", "info", "list", "pvp", "sethome", "delhome", "home", "chat", "menu");
        if (args.length == 2 && List.of("info", "join").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            for (Team t : teams.values()) names.add(t.name);
            return names;
        }
        return args.length == 2 ? null : List.of();
    }

    // ── Menus (LifestealCore's team menu) ─────────────────────────────────

    private static final String[] SORTS = {"ONLINE_MEMBERS", "PERMISSIONS", "JOIN_DATE", "ALPHABETICALLY"};

    /** What each player's team menu shows: the search and the sort. */
    private static final class View {
        String search;
        int sort;
    }

    private final Map<UUID, View> views = new ConcurrentHashMap<>();

    /** /team: the team menu, or the create/invites menu without a team. */
    void openMenu(Player player) {
        if (!ready) {
            msg(player, "data-loading");
            return;
        }
        if (byMember.get(player.getUniqueId()) == null) openNoTeam(player);
        else openTeam(player);
    }

    private String date(long millis) {
        return new java.text.SimpleDateFormat(config().getString("date-format", "dd.MM.yyyy")).format(new java.util.Date(millis));
    }

    private int onlineCount(Team t) {
        int n = 0;
        for (Player m : online(t)) if (!vanished(m)) n++;
        return n;
    }

    private void openTeam(Player player) {
        View view = views.computeIfAbsent(player.getUniqueId(), k -> new View());
        open(player, "team", menu -> {
            Team t = byMember.get(player.getUniqueId());
            if (t == null) return; // disbanded while open: an empty menu, the next click reopens
            UUID me = player.getUniqueId();
            var file = menu.file().yml();
            UUID leader = t.leader();
            menu.with("team", t.name).with("owner", leader == null ? "-" : t.names.getOrDefault(leader, "?"))
                    .with("members", t.members.size()).with("limit", config().getInt("max-members", 8))
                    .with("online", onlineCount(t)).with("search", view.search == null ? file.getString("no-search", "-") : view.search)
                    .with("pvp_status", file.getString(t.pvp ? "pvp-status.enabled" : "pvp-status.disabled", t.pvp ? "&aON" : "&cOFF"))
                    .with("home", t.home == null ? config().getString("no-home-text", "none") : t.home.world());
            for (int i = 0; i < SORTS.length; i++) {
                String label = file.getString("sort-options." + SORTS[i], SORTS[i]);
                String prefix = file.getString(i == view.sort ? "sort-selected-prefix" : "sort-unselected-prefix", "");
                menu.with("sort_" + (i + 1), prefix + label);
            }
            List<UUID> list = new ArrayList<>(t.members.keySet());
            if (view.search != null) {
                String q = view.search.toLowerCase(Locale.ROOT);
                list.removeIf(u -> !t.names.getOrDefault(u, "").toLowerCase(Locale.ROOT).contains(q));
            }
            Comparator<UUID> byName = Comparator.comparing(u -> t.names.getOrDefault(u, "").toLowerCase(Locale.ROOT));
            list.sort(switch (SORTS[view.sort]) {
                case "ONLINE_MEMBERS" -> Comparator.comparing((UUID u) -> { Player o = Bukkit.getPlayer(u); return o == null || vanished(o); }).thenComparing(byName);
                case "PERMISSIONS" -> Comparator.comparingInt((UUID u) -> -(t.members.getOrDefault(u, 0) * 64 + Integer.bitCount(t.perms.getOrDefault(u, 0)))).thenComparing(byName);
                case "JOIN_DATE" -> Comparator.comparingLong((UUID u) -> t.joined.getOrDefault(u, t.created)).thenComparing(byName);
                default -> byName;
            });
            menu.paginate(list, (u, slot) -> {
                Player o = Bukkit.getPlayer(u);
                boolean on = o != null && !vanished(o);
                Map<String, Object> ph = new java.util.HashMap<>();
                ph.put("name", t.names.getOrDefault(u, "?"));
                ph.put("rank", role(t.members.getOrDefault(u, MEMBER)));
                ph.put("status", file.getString(on ? "status.online" : "status.offline", on ? "&aONLINE" : "&cOFFLINE"));
                ph.put("joined", date(t.joined.getOrDefault(u, t.created)));
                menu.place("member", slot, ph, c -> {
                    if (u.equals(me)) {
                        msg(player, "cannot-manage-self");
                        return;
                    }
                    if (!can(t, me, MANAGE)) {
                        msg(player, "not-allowed");
                        return;
                    }
                    openMember(player, u);
                });
            });
            menu.function("search", c -> {
                player.closeInventory();
                com.vexorstudios.vexcore.core.Dialogs.ask(this, player, "search", "search-prompt", Map.of("team", t.name), view.search == null ? "" : view.search, text -> {
                    view.search = text.isBlank() ? null : text.strip();
                    openTeam(player);
                }, () -> openTeam(player));
            });
            menu.function("sort", c -> {
                view.sort = (view.sort + (c.type().isRightClick() ? SORTS.length - 1 : 1)) % SORTS.length;
                menu.refresh();
            });
            menu.function("team-info", c -> {
                view.search = null;
                menu.refresh();
            });
            menu.function("invite", c -> {
                if (!allowed(player, t, MANAGE)) return;
                player.closeInventory();
                com.vexorstudios.vexcore.core.Dialogs.ask(this, player, "invite", "invite-prompt", Map.of("team", t.name), "", text -> {
                    if (text.isBlank()) openTeam(player);
                    else invite(player, byMember.get(me), 0, text.strip().split(" ")[0]);
                }, () -> openTeam(player));
            });
            menu.function("team-home", c -> {
                if (c.type().isRightClick()) {
                    if (c.type().isShiftClick()) delHome(player, t);
                    else setHome(player, t);
                    menu.refresh();
                    return;
                }
                player.closeInventory();
                home(player, t);
            });
            menu.function("pvp", c -> {
                togglePvp(player, t);
                menu.refresh();
            });
            menu.function("team-chat", c -> {
                toggleChat(player);
                menu.refresh();
            });
            menu.function("leave", c -> {
                boolean lead = t.members.getOrDefault(me, MEMBER) == LEADER;
                if (lead && t.members.size() > 1) {
                    confirm(player, "disband", Map.of("team", t.name), () -> {
                        Team now = byMember.get(me);
                        if (now == t && t.members.getOrDefault(me, MEMBER) == LEADER) {
                            tell(t, "disbanded", Map.of("team", t.name));
                            deleteTeam(t);
                        }
                        openMenu(player);
                    }, () -> openTeam(player));
                    return;
                }
                confirm(player, "leave", Map.of("team", t.name), () -> {
                    Team now = byMember.get(me);
                    if (now == t) leave(player, t, t.members.getOrDefault(me, MEMBER));
                    openMenu(player);
                }, () -> openTeam(player));
            });
        });
    }

    private void openMember(Player player, UUID target) {
        open(player, "member", menu -> {
            UUID me = player.getUniqueId();
            Team t = byMember.get(me);
            if (t == null || !t.members.containsKey(target)) {
                Scheduler.entity(player, () -> openMenu(player)); // they left meanwhile
                return;
            }
            var file = menu.file().yml();
            String on = file.getString("on-status", "&aON"), off = file.getString("off-status", "&cOFF");
            int perms = t.perms.getOrDefault(target, 0);
            boolean targetLeads = t.members.getOrDefault(target, MEMBER) == LEADER;
            menu.with("name", t.names.getOrDefault(target, "?")).with("team", t.name)
                    .with("manage_status", (perms & MANAGE) != 0 || targetLeads ? on : off)
                    .with("pvp_status", (perms & PVP) != 0 || targetLeads ? on : off)
                    .with("visit_status", (perms & VISIT_HOME) != 0 || targetLeads ? on : off)
                    .with("edit_status", (perms & EDIT_HOME) != 0 || targetLeads ? on : off)
                    .with("chat_status", (perms & CHAT) != 0 || targetLeads ? on : off);
            boolean lead = t.members.getOrDefault(me, MEMBER) == LEADER;
            String[] names = {"manage-teammates", "pvp", "visit-home", "edit-home", "team-chat"};
            int[] bits = {MANAGE, PVP, VISIT_HOME, EDIT_HOME, CHAT};
            for (int i = 0; i < names.length; i++) {
                int bit = bits[i];
                menu.function(names[i], c -> {
                    if (!lead) {
                        msg(player, "not-leader");
                        return;
                    }
                    if (targetLeads) {
                        msg(player, "cannot-edit-owner");
                        return;
                    }
                    togglePerm(t, target, bit);
                    msg(player, "permission-changed", "player", t.names.getOrDefault(target, "?"));
                    menu.refresh();
                });
            }
            menu.function("kick", c -> confirm(player, "kick", Map.of("player", t.names.getOrDefault(target, "?")), () -> {
                Team now = byMember.get(me);
                if (now == t && t.members.containsKey(target) && can(t, me, MANAGE)) kick(player, t, target);
                openTeam(player);
            }, () -> openMember(player, target)));
            if (lead) menu.function("transfer", c -> confirm(player, "transfer", Map.of("player", t.names.getOrDefault(target, "?")), () -> {
                Team now = byMember.get(me);
                if (now == t && t.members.containsKey(target)) transfer(player, t, LEADER, t.names.get(target));
                openTeam(player);
            }, () -> openMember(player, target)));
            menu.function("back", c -> openTeam(player));
        });
    }

    /** The confirm menu: %action% is the text for {@code action} (confirm.yml actions). */
    private void confirm(Player player, String action, Map<String, ?> ph, Runnable yes, Runnable no) {
        open(player, "confirm", menu -> {
            for (Map.Entry<String, ?> e : ph.entrySet()) menu.with(e.getKey(), e.getValue());
            menu.with("action", Text.fill(menu.file().yml().getString("actions." + action, action), ph));
            menu.function("confirm", c -> yes.run());
            menu.function("cancel", c -> no.run());
        });
    }

    private void openNoTeam(Player player) {
        open(player, "noteam", menu -> {
            UUID me = player.getUniqueId();
            if (byMember.get(me) != null) {
                Scheduler.entity(player, () -> openMenu(player)); // joined meanwhile
                return;
            }
            menu.with("limit", config().getInt("max-members", 8)).with("cost", plugin.money().format(config().getDouble("create-cost", 0)));
            List<Team> invitedTo = new ArrayList<>();
            Map<String, Long> own = invites.get(me);
            long now = System.currentTimeMillis();
            if (own != null) {
                own.values().removeIf(until -> until < now);
                for (String id : own.keySet()) {
                    Team t = teams.get(id);
                    if (t != null) invitedTo.add(t);
                }
            }
            if (invitedTo.isEmpty()) menu.function("no-invites", c -> {
            });
            menu.paginate(invitedTo, (t, slot) -> {
                UUID leader = t.leader();
                menu.place("invite", slot, Map.of("team", t.name, "owner", leader == null ? "-" : t.names.getOrDefault(leader, "?"),
                        "members", t.members.size(), "online", onlineCount(t)), c -> {
                    join(player, byMember.get(me), t.name);
                    openMenu(player);
                });
            });
            menu.function("create", c -> {
                player.closeInventory();
                com.vexorstudios.vexcore.core.Dialogs.ask(this, player, "create", "create-prompt", Map.of("min", config().getInt("name.min-length", 3),
                        "max", config().getInt("name.max-length", 16)), "", text -> {
                    if (text.isBlank()) {
                        openMenu(player);
                        return;
                    }
                    create(player, byMember.get(me), text.strip().split(" ")[0]);
                    if (byMember.get(me) != null) openTeam(player);
                }, () -> openMenu(player));
            });
        });
    }

    // ── Team chat and friendly fire ───────────────────────────────────────

    private void toggleChat(Player player) {
        if (!allowed(player, byMember.get(player.getUniqueId()), CHAT)) return;
        boolean now = chatMode.add(player.getUniqueId());
        if (!now) chatMode.remove(player.getUniqueId());
        msg(player, now ? "chat-on" : "chat-off");
    }

    private void teamChat(Player player, String text) {
        Team t = byMember.get(player.getUniqueId());
        if (!allowed(player, t, CHAT)) {
            chatMode.remove(player.getUniqueId());
            return;
        }
        ChatFilterFeature filter = ChatFilterFeature.of(plugin);
        if (filter != null) {
            ChatFilterFeature.Verdict v = filter.check(player, text, "team");
            if (v.blocked()) return;
            text = v.text();
        }
        var ignores = plugin.features().get("msg") instanceof com.vexorstudios.vexcore.features.msg.MsgFeature m ? m : null;
        Map<String, Object> ph = Map.of("player", player.getName(), "team", t.name, "message", Component.text(text));
        for (UUID member : t.members.keySet()) {
            Player receiver = Bukkit.getPlayer(member);
            if (receiver != null && (ignores == null || !ignores.ignores(member, player.getUniqueId())))
                msg(receiver, "chat-format", ph);
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        views.remove(event.getPlayer().getUniqueId());
    }

    /** A member who changed their Minecraft name: kick, promote and /team info use the new one. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Team t = byMember.get(p.getUniqueId());
        if (t == null || p.getName().equals(t.names.get(p.getUniqueId()))) return;
        saveMember(t, p.getUniqueId(), t.members.getOrDefault(p.getUniqueId(), MEMBER), p.getName());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        // Frozen for a screenshare: their chat belongs to staff only (screenshare handles it).
        if (plugin.features().get("screenshare") instanceof com.vexorstudios.vexcore.features.screenshare.ScreenshareFeature ss
                && ss.isFrozen(player.getUniqueId())) return;
        String text = Text.plain(event.message());
        String trigger = config().getString("chat-trigger", "!");
        boolean triggered = !trigger.isEmpty() && text.startsWith(trigger) && text.length() > trigger.length()
                && byMember.containsKey(player.getUniqueId());
        if (!triggered && !chatMode.contains(player.getUniqueId())) return;
        // Muted: left to punishments (it cancels the message and says why) instead of reaching the team.
        if (plugin.features().get("punishments") instanceof com.vexorstudios.vexcore.features.punishments.PunishmentsFeature punish
                && punish.isMuted(player.getUniqueId())) return;
        event.setCancelled(true);
        teamChat(player, triggered ? text.substring(trigger.length()).strip() : text);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Entity damager = event.getDamager();
        Player attacker = null;
        if (damager instanceof Player p) attacker = p;
        else if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        else if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;
        Team t = byMember.get(attacker.getUniqueId());
        if (t != null && !t.pvp && t == byMember.get(victim.getUniqueId())) event.setCancelled(true);
    }

    /** Names of teams, for the importer and other features. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Team t : teams.values()) out.add(t.name);
        return out;
    }

    /** For other features: the members of a player's team who are online. */
    public List<Player> onlineMates(OfflinePlayer player) {
        Team t = byMember.get(player.getUniqueId());
        return t == null ? List.of() : online(t);
    }
}
