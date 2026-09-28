package com.vexorstudios.vexcore.features.reports;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SoundSpec;
import com.vexorstudios.vexcore.core.Visibility;
import com.vexorstudios.vexcore.core.Webhook;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player reports.
 * <ul>
 *   <li>/report &lt;player&gt; opens a dialog to type the reason in (/report &lt;player&gt; &lt;reason&gt; skips it).</li>
 *   <li>Reports are numbered #0001, #0002... saved in the database, shown to online staff
 *       (clickable) and sent to a Discord webhook.</li>
 *   <li>/reports lists the pending ones. A report opens a 27-slot menu: the reporter's head, the
 *       report (every detail) and the offender's head. Click a head to teleport to them.</li>
 *   <li>/report resolve &lt;number&gt; closes a report.</li>
 * </ul>
 */
public final class ReportsFeature extends Feature {

    static final String PENDING = "PENDING";
    static final String RESOLVED = "RESOLVED";

    record Report(int id, UUID reporter, String reporterName, UUID target, String targetName, String reason,
                  String reporterLoc, String targetLoc, String server, long created,
                  String status, String resolvedBy, long resolvedAt) {

        Report resolved(String by, long at) {
            return new Report(id, reporter, reporterName, target, targetName, reason, reporterLoc, targetLoc, server,
                    created, RESOLVED, by, at);
        }
    }

    /** Pending reports, plus the ones resolved since the last reload. */
    private final Map<Integer, Report> reports = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private ReportDialog dialog;

    @Override
    protected void enable() {
        dialog = new ReportDialog(this);
        db().schema("reports", "CREATE TABLE IF NOT EXISTS {t} (id INT NOT NULL PRIMARY KEY, "
                + "reporter VARCHAR(36) NOT NULL, reporter_name VARCHAR(32) NOT NULL, "
                + "target VARCHAR(36) NOT NULL, target_name VARCHAR(32) NOT NULL, reason TEXT NOT NULL, "
                + "reporter_loc VARCHAR(128) NOT NULL, target_loc VARCHAR(128) NOT NULL, server VARCHAR(64) NOT NULL, "
                + "created BIGINT NOT NULL, status VARCHAR(16) NOT NULL, resolved_by VARCHAR(32), resolved_at BIGINT)");
        db().index("reports", "status");
        String table = db().table("reports");
        db().query("load reports", c -> {
            Map<Integer, Report> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, reporter, reporter_name, target, target_name, reason, "
                    + "reporter_loc, target_loc, server, created, status, resolved_by, resolved_at FROM " + table + " WHERE status = ?")) {
                ps.setString(1, PENDING);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getInt(1), read(rs));
                }
            }
            return out;
        }).whenComplete((found, error) -> {
            if (found != null) reports.putAll(found);
            loaded = true;
        });

        command("report", this::report, (sender, args) -> {
            if (args.length == 1) {
                List<String> out = playerNames(sender);
                if (sender.hasPermission(managePermission())) out.add("resolve");
                return out;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("resolve") && sender.hasPermission(managePermission())) {
                return pending().stream().map(r -> number(r.id)).toList();
            }
            return List.of();
        });
        command("reports", this::reportsCommand, (sender, args) -> List.of());

        placeholder("reports_pending", (p, a) -> String.valueOf(pending().size()));
    }

    @Override
    protected void disable() {
        reports.clear();
        lastReport.clear();
    }

    private static Report read(ResultSet rs) throws SQLException {
        return new Report(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), UUID.fromString(rs.getString(4)),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getLong(10),
                rs.getString(11), rs.getString(12), rs.getLong(13));
    }

    private String managePermission() {
        return config().getString("permissions.manage", "vexcore.reports.manage");
    }

    private String notifyPermission() {
        return config().getString("permissions.notify", "vexcore.reports.notify");
    }

    // ── /report ───────────────────────────────────────────────────────────

    private void report(CommandSender sender, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("resolve") && sender.hasPermission(managePermission())) {
            if (args.length < 2) {
                msg(sender, "resolve-usage", "command", plugin.commands().name("report"));
                return;
            }
            resolve(sender, args[1]);
            return;
        }
        Player p = player(sender);
        if (p == null) return;
        if (args.length == 0) {
            usage(p, "report");
            return;
        }
        if (!loaded) {
            msg(p, "loading");
            return;
        }
        Player target = target(p, args[0]);
        if (target == null) return;
        if (target.equals(p) && !config().getBoolean("allow-self-report", false)) {
            msg(p, "self");
            return;
        }
        if (!target.equals(p) && target.hasPermission(config().getString("permissions.exempt", "vexcore.reports.exempt"))) {
            msg(p, "exempt", "target", target.getName());
            return;
        }
        long left = cooldownLeft(p);
        if (left > 0) {
            msg(p, "cooldown", "time", plugin.messages().time(left));
            return;
        }
        if (args.length > 1) { // reason typed straight after the name: no dialog
            submit(p, target.getUniqueId(), target.getName(), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            return;
        }
        if (ReportDialog.available()) dialog.open(p, target);
        else msg(p, "no-dialog", "command", plugin.commands().name("report"), "target", target.getName());
    }

    long cooldownLeft(Player p) {
        if (p.hasPermission(config().getString("permissions.bypass-cooldown", "vexcore.reports.bypasscooldown"))) return 0;
        Long last = lastReport.get(p.getUniqueId());
        if (last == null) return 0;
        long cooldown = Math.max(0, config().getLong("cooldown-seconds", 60)) * 1000;
        long left = last + cooldown - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** Plain text a player typed: no colour codes, tags or placeholders, one line. */
    static String clean(String raw, int max) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\p{Cntrl}", " ")
                .replace("&", "").replace("§", "").replace("<", "").replace(">", "").replace("%", "")
                .replaceAll("\\s+", " ").strip();
        return s.length() > max ? s.substring(0, max).strip() : s;
    }

    int maxLength() {
        return Math.max(16, Math.min(1000, config().getInt("reason.max-length", 256)));
    }

    int minLength() {
        return Math.max(1, config().getInt("reason.min-length", 3));
    }

    /**
     * Files a report (player's thread). Returns false, after telling the player, when the reason
     * is too short so the dialog can be shown again.
     */
    boolean submit(Player p, UUID targetId, String targetName, String rawReason) {
        String reason = clean(rawReason, maxLength());
        if (reason.length() < minLength()) {
            msg(p, "reason-too-short", "min", minLength());
            return false;
        }
        long left = cooldownLeft(p);
        if (left > 0) {
            msg(p, "cooldown", "time", plugin.messages().time(left));
            return true;
        }
        lastReport.put(p.getUniqueId(), System.currentTimeMillis());
        if (lastReport.size() > 1000) { // cooldowns long over don't need remembering
            long cutoff = System.currentTimeMillis() - Math.max(0, config().getLong("cooldown-seconds", 60)) * 1000;
            lastReport.values().removeIf(t -> t < cutoff);
        }
        UUID reporter = p.getUniqueId();
        String reporterName = p.getName();
        String reporterLoc = where(p.getLocation());
        Player target = Bukkit.getPlayer(targetId);
        String targetLoc = target != null ? where(target.getLocation()) : "-";
        String server = config().getString("server-name", "survival");
        int first = Math.max(0, config().getInt("first-number", 1));
        long now = System.currentTimeMillis();
        String table = db().table("reports");
        db().query("create report", c -> {
            int id;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(id) FROM " + table)) {
                id = rs.next() && rs.getObject(1) != null ? rs.getInt(1) + 1 : first;
            }
            id = Math.max(id, first);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (id, reporter, reporter_name, target, target_name, "
                    + "reason, reporter_loc, target_loc, server, created, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setInt(1, id);
                ps.setString(2, reporter.toString());
                ps.setString(3, reporterName);
                ps.setString(4, targetId.toString());
                ps.setString(5, targetName);
                ps.setString(6, reason);
                ps.setString(7, reporterLoc);
                ps.setString(8, targetLoc);
                ps.setString(9, server);
                ps.setLong(10, now);
                ps.setString(11, PENDING);
                ps.executeUpdate();
            }
            return new Report(id, reporter, reporterName, targetId, targetName, reason, reporterLoc, targetLoc, server, now, PENDING, null, 0);
        }).whenComplete((report, error) -> {
            if (!plugin.isEnabled()) return;
            Scheduler.global(() -> {
                if (!isEnabled()) return;
                Player online = Bukkit.getPlayer(reporter);
                if (report == null) {
                    lastReport.remove(reporter);
                    if (online != null) msg(online, "failed");
                    return;
                }
                reports.put(report.id, report);
                if (online != null) msg(online, "submitted", placeholders(report));
                announce(report);
            });
        });
        return true;
    }

    private void announce(Report report) {
        Map<String, Object> ph = placeholders(report);
        ph.put("reason", Component.text(report.reason)); // never parsed: nothing typed can format or click
        List<CommandSender> staff = new ArrayList<>();
        for (Player s : Bukkit.getOnlinePlayers()) if (s.hasPermission(notifyPermission())) staff.add(s);
        staff.add(Bukkit.getConsoleSender());
        broadcast(staff, "staff-alert", ph);
        Webhook.send(config().getConfigurationSection("webhook"), discord(report));
    }

    // ── /report resolve ───────────────────────────────────────────────────

    private void resolve(CommandSender sender, String raw) {
        int id = parseNumber(raw);
        Report report = id < 0 ? null : reports.get(id);
        if (report == null) {
            msg(sender, "not-found", "id", raw.replace("#", ""));
            return;
        }
        if (!PENDING.equals(report.status)) {
            msg(sender, "already-resolved", placeholders(report));
            return;
        }
        long now = System.currentTimeMillis();
        Report done = report.resolved(sender.getName(), now);
        reports.put(id, done);
        forgetOldResolved();
        String table = db().table("reports");
        db().queue("resolve report", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET status = ?, resolved_by = ?, resolved_at = ? WHERE id = ?")) {
                ps.setString(1, RESOLVED);
                ps.setString(2, done.resolvedBy);
                ps.setLong(3, now);
                ps.setInt(4, id);
                ps.executeUpdate();
            }
        });
        Map<String, Object> ph = placeholders(done);
        msg(sender, "resolved", ph);
        List<CommandSender> staff = new ArrayList<>();
        for (Player s : Bukkit.getOnlinePlayers()) if (s != sender && s.hasPermission(notifyPermission())) staff.add(s);
        broadcast(staff, "staff-resolved", ph);
        Player reporter = Bukkit.getPlayer(done.reporter);
        if (reporter != null && reporter != sender && config().getBoolean("tell-reporter-when-resolved", true)) {
            msg(reporter, "your-report-resolved", ph);
        }
        Webhook.send(config().getConfigurationSection("webhook-resolved"), discord(done));
    }

    /** Resolved reports stay viewable until the next reload; only the latest 200 are kept. */
    private void forgetOldResolved() {
        List<Integer> resolved = new ArrayList<>();
        for (Report r : reports.values()) if (!PENDING.equals(r.status)) resolved.add(r.id);
        if (resolved.size() <= 200) return;
        resolved.sort(null);
        for (int i = 0; i < resolved.size() - 200; i++) reports.remove(resolved.get(i));
    }

    static int parseNumber(String raw) {
        try {
            return Integer.parseInt(raw.replace("#", "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── /reports ──────────────────────────────────────────────────────────

    private void reportsCommand(CommandSender sender, String label, String[] args) {
        Player p = player(sender);
        if (p == null) return;
        if (args.length >= 1) { // "/reports 3" or "/reports view 3" (the staff alert's click)
            String raw = args[0].equalsIgnoreCase("view") && args.length >= 2 ? args[1] : args[0];
            int id = parseNumber(raw);
            if (id < 0 || !reports.containsKey(id)) {
                msg(p, "not-found", "id", raw.replace("#", ""));
                return;
            }
            openReport(p, id);
            return;
        }
        openList(p);
    }

    List<Report> pending() {
        List<Report> out = new ArrayList<>();
        for (Report r : reports.values()) if (PENDING.equals(r.status)) out.add(r);
        out.sort(Comparator.comparingInt(Report::id));
        return out;
    }

    void openList(Player p) {
        open(p, "reports", menu -> {
            List<Report> list = pending();
            menu.with("count", list.size());
            if (list.isEmpty()) menu.function("empty", c -> {
            });
            menu.paginate(list, (r, slot) -> menu.place("report", slot, placeholders(r), c -> openReport(c.player(), r.id)));
        });
    }

    void openReport(Player p, int id) {
        open(p, "report", menu -> {
            Report r = reports.get(id);
            if (r == null) {
                menu.function("back", c -> openList(c.player()));
                return;
            }
            placeholders(r).forEach(menu::with);
            menu.function("reporter", c -> teleport(c.player(), r.reporter, r.reporterName));
            menu.function("offender", c -> teleport(c.player(), r.target, r.targetName));
            menu.function("back", c -> openList(c.player()));
            if (PENDING.equals(r.status)) menu.function("pending", c -> {
            });
            else menu.function("resolved", c -> {
            });
        });
    }

    private void teleport(Player staff, UUID to, String name) {
        Player there = Bukkit.getPlayer(to);
        if (there == null || !Visibility.knows(staff, there)) {
            msg(staff, "not-online", "player", name);
            return;
        }
        staff.closeInventory();
        staff.teleportAsync(there.getLocation()).thenAccept(ok -> {
            if (Boolean.TRUE.equals(ok)) msg(staff, "teleported", "player", there.getName());
        });
    }

    // ── Placeholders ──────────────────────────────────────────────────────

    static String number(int id) {
        return String.format(Locale.ROOT, "%04d", id);
    }

    private static String where(Location l) {
        if (l == null || l.getWorld() == null) return "-";
        return l.getWorld().getName() + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private String date(long time) {
        return com.vexorstudios.vexcore.core.Dates.format(time, config().getString("date-format", "dd.MM.yyyy HH:mm"), config().getString("timezone", ""));
    }

    private String ago(long time) {
        long seconds = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        return config().getString("ago-format", "%time% ago").replace("%time%", plugin.messages().time(seconds));
    }

    /** The reason as lore lines ("\n" between them), each starting with reason.line. */
    private String reasonLines(String reason) {
        int width = Math.max(10, config().getInt("reason.line-width", 30));
        String prefix = config().getString("reason.line", "&#FF0000¦ &f");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : reason.split(" ")) {
            while (word.length() > width) {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        StringBuilder out = new StringBuilder();
        for (String l : lines) {
            if (!out.isEmpty()) out.append('\n');
            out.append(prefix).append(l);
        }
        return out.toString();
    }

    Map<String, Object> placeholders(Report r) {
        Map<String, Object> ph = new HashMap<>();
        boolean pending = PENDING.equals(r.status);
        Player reporter = Bukkit.getPlayer(r.reporter);
        Player target = Bukkit.getPlayer(r.target);
        String yes = config().getString("words.online", "&#97F900Online");
        String no = config().getString("words.offline", "&#FF3B3BOffline");
        ph.put("id", number(r.id));
        ph.put("id_raw", String.valueOf(r.id));
        ph.put("reporter", r.reporterName);
        ph.put("target", r.targetName);
        ph.put("offender", r.targetName);
        ph.put("reason", r.reason);
        ph.put("reason_lines", reasonLines(r.reason));
        ph.put("reason_short", r.reason.length() > 28 ? r.reason.substring(0, 27) + "…" : r.reason);
        ph.put("date", date(r.created));
        ph.put("ago", ago(r.created));
        ph.put("reporter_location", r.reporterLoc);
        ph.put("target_location", r.targetLoc);
        ph.put("server", r.server);
        ph.put("status", pending ? config().getString("words.pending", "&#FFD900Pending") : config().getString("words.resolved", "&#97F900Resolved"));
        ph.put("resolved_by", r.resolvedBy == null ? "-" : r.resolvedBy);
        ph.put("resolved_date", r.resolvedAt <= 0 ? "-" : date(r.resolvedAt));
        ph.put("reporter_online", reporter != null ? yes : no);
        ph.put("target_online", target != null ? yes : no);
        ph.put("report_command", plugin.commands().name("report"));
        ph.put("reports_command", plugin.commands().name("reports"));
        return ph;
    }

    /** For Discord: what a player typed is escaped so it can't ping or format. */
    private Map<String, Object> discord(Report r) {
        Map<String, Object> ph = placeholders(r);
        ph.put("reason", Webhook.escape(r.reason));
        ph.put("status", PENDING.equals(r.status) ? "Pending" : "Resolved");
        ph.put("reporter_online", Bukkit.getPlayer(r.reporter) != null ? "Online" : "Offline");
        ph.put("target_online", Bukkit.getPlayer(r.target) != null ? "Online" : "Offline");
        return ph;
    }

    void play(Player p, String key) {
        SoundSpec s = SoundSpec.of(config().get("sounds." + key));
        if (s != null) s.play(p);
    }
}
