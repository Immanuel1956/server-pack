package com.vexorstudios.vexcore.features.chatfilter;

import com.vexorstudios.vexcore.VexCore;
import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The chat filter, built like LifestealCore's: every message runs through the checks in
 * config.yml in order (slowmode, length and character spam, repeat, shouting), then through the
 * rules in blocked.yml. The first thing that blocks ends it.
 *
 * <p>Every rule runs three times: on the raw message; on a folded copy where accents, zalgo,
 * invisible characters, look-alike letters from other alphabets (Cyrillic, Greek, fullwidth,
 * small caps) and leetspeak are turned back into plain letters and every other character
 * becomes a word break; and on a glued copy where runs of 1-2 letter pieces are joined, so
 * "f u c k" and "f.u.c.k" read as one word while "who read" stays two. REPLACE masks the hit,
 * CANCEL drops the message, WARN only reports. Hits are logged for /chathistory.
 *
 * <p>Public chat, /msg, team chat, the commands listed under scan, item renames and team names
 * all go through here.
 */
public final class ChatFilterFeature extends Feature implements Listener {

    public enum Action {REPLACE, CANCEL, WARN}

    record Rule(String name, Action action, boolean alert, String bypass, List<Pattern> patterns,
                int strikes, List<String> commands) {
    }

    /** config.yml values the chat thread needs for every message, read once per reload. */
    private record Settings(boolean slowmode, long slowMs, boolean length, int maxChars, int sameInRow,
                            boolean repeat, long repeatMs, int repeatPercent, boolean shouting, int maxUpper,
                            boolean shoutCancel, boolean words, String mask, boolean notifyMask, long joinDelayMs,
                            boolean symbols, int symbolsMin, int symbolsPercent) {
    }

    /** Strikes: rule hits add up; crossing a threshold mutes (and runs commands). Kept over relogs. */
    private static final class Strikes {
        int strikes;
        long lastStrike;
        long mutedUntil;
    }

    /** Folded text plus, for every character, where it came from in the original. */
    record Folded(String text, int[] map) {
    }

    /** blocked: stop the message. text: what to send instead (maybe masked or lowered). */
    public record Verdict(boolean blocked, String text, String rule) {
    }

    private static final class State {
        long last;
        String previous = "";
        long previousAt;
        long joined;
    }

    private static String stripInvisible(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(cp -> !invisible(cp)).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Characters that show nothing (zero-width, control, the blank Hangul fillers, braille blank). */
    private static boolean invisible(int cp) {
        int type = Character.getType(cp);
        return type == Character.FORMAT || type == Character.CONTROL
                || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800
                || cp == 0x034F || cp == 0x17B4 || cp == 0x17B5 || cp == 0x180E;
    }
    private static final Pattern ZALGO = Pattern.compile("(\\p{M})\\p{M}+");
    private static final Map<Character, Character> FOLD = new HashMap<>();

    static {
        String[][] pairs = {
                // other alphabets and styles that look latin
                {"аɑα", "a"}, {"вʙβ", "b"}, {"сϲᴄς", "c"}, {"ԁᴅđ", "d"}, {"еєёɛεᴇ", "e"}, {"ꜰƒ", "f"}, {"ɡɢ", "g"},
                {"һʜн", "h"}, {"іїɪιı", "i"}, {"јᴊ", "j"}, {"κᴋк", "k"}, {"ʟł", "l"}, {"мᴍ", "m"}, {"пɴηи", "n"},
                {"оοσᴏøө", "o"}, {"рρᴘ", "p"}, {"ԛǫ", "q"}, {"ʀг", "r"}, {"ѕꜱʂ", "s"}, {"тτᴛ", "t"}, {"υᴜц", "u"},
                {"νᴠ", "v"}, {"ѡωᴡш", "w"}, {"хχ", "x"}, {"уүγʏ", "y"}, {"ᴢʐ", "z"},
                // leetspeak
                {"4@∆λ", "a"}, {"8ß", "b"}, {"(¢©", "c"}, {"3€ə", "e"}, {"69", "g"}, {"#", "h"}, {"1!|¡", "i"},
                {"£", "l"}, {"0°", "o"}, {"5$§", "s"}, {"7+†", "t"}, {"µ", "u"}, {"¥", "y"}, {"2", "z"}};
        for (String[] pair : pairs) for (char c : pair[0].toCharArray()) FOLD.put(c, pair[1].charAt(0));
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Strikes> records = new ConcurrentHashMap<>();
    /** Swapped whole on reload, never changed in place: chat threads read it at any moment. */
    private volatile List<Rule> rules = List.of();
    private volatile List<Pattern> allowed = List.of();
    /** blocked.yml exceptions: innocent words that contain a bad one ("shitake"); never caught. */
    private volatile List<Pattern> exceptions = List.of();
    /** Defaults until enable() reads config.yml, so it is never null (the tests use it that way). */
    private volatile Settings settings = readSettings();
    private File rulesFile;

    @Override
    protected void enable() {
        rulesFile = new File(plugin.files().dir(), "features/chatfilter/blocked.yml");
        settings = readSettings();
        loadRules();
        loadAllowed();
        db().schema("chat_log", "CREATE TABLE IF NOT EXISTS {t} (uuid VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, message TEXT NOT NULL, "
                + "rule VARCHAR(32) NOT NULL, action VARCHAR(16) NOT NULL, source VARCHAR(16) NOT NULL, time BIGINT NOT NULL)");
        db().index("chat_log", "uuid");
        db().index("chat_log", "time");
        int keep = config().getInt("log.keep-days", 14);
        if (keep > 0) {
            long cutoff = System.currentTimeMillis() - keep * 86_400_000L;
            Database db = db();
            db.queue("prune chat log", c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("chat_log") + " WHERE time < ?")) {
                    ps.setLong(1, cutoff);
                    ps.executeUpdate();
                }
            });
        }
        listen(this);
        command("chatfilter", this::command, (s, a) -> a.length == 1 ? List.of("regex", "remove", "similar", "test", "strikes", "clear", "reload")
                : a.length == 2 && (a[0].equalsIgnoreCase("strikes") || a[0].equalsIgnoreCase("clear")) ? null : List.of());
        long forget = Math.max(1, config().getLong("strikes.forget-minutes", 30)) * 60_000L;
        every(20 * 60, () -> { // forget old strikes and finished mutes
            long now = System.currentTimeMillis();
            records.values().removeIf(r -> now - r.lastStrike > forget && now > r.mutedUntil);
        });
        command("chathistory", this::history, (s, a) -> a.length == 1 ? null : List.of());
    }

    private Settings readSettings() {
        return new Settings(
                config().getBoolean("slowmode.enabled", true), Math.max(0, config().getLong("slowmode.seconds", 3)) * 1000,
                config().getBoolean("length.enabled", true), config().getInt("length.max-characters", 128),
                config().getInt("length.max-same-in-a-row", 5),
                config().getBoolean("repeat.enabled", true), Math.max(0, config().getLong("repeat.remember-seconds", 30)) * 1000,
                config().getInt("repeat.match-percent", 80),
                config().getBoolean("shouting.enabled", true), config().getInt("shouting.max-uppercase", 8),
                config().getString("shouting.action", "LOWERCASE").equalsIgnoreCase("CANCEL"),
                config().getBoolean("words.enabled", true), config().getString("words.mask", "***"),
                config().getBoolean("words.notify-on-mask", true),
                Math.max(0, config().getLong("join-delay-seconds", 3)) * 1000,
                config().getBoolean("symbols.enabled", true), Math.max(1, config().getInt("symbols.min-length", 12)),
                Math.max(1, Math.min(100, config().getInt("symbols.max-percent", 60))));
    }

    /** allowed: your own domains and invites; they are taken out before the rules look. */
    private void loadAllowed() {
        List<Pattern> out = new ArrayList<>();
        for (String entry : config().getStringList("allowed")) {
            if (entry == null || entry.isBlank()) continue;
            try {
                out.add(entry.startsWith("regex:") ? Pattern.compile(entry.substring(6), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                        : Pattern.compile(Pattern.quote(entry.strip()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException e) {
                problems().add("features/chatfilter/config.yml: allowed: bad pattern '" + entry + "' (" + e.getDescription() + ")");
            }
        }
        allowed = List.copyOf(out);
    }

    /** The text with allowed parts and exception words blanked out (same length, so hit positions still fit). */
    private String withoutAllowed(String raw) {
        List<Pattern> list = allowed, words = exceptions;
        if (list.isEmpty() && words.isEmpty()) return raw;
        char[] chars = null;
        for (Pattern p : words.isEmpty() ? list : list.isEmpty() ? words : concat(list, words)) {
            Matcher m = p.matcher(raw);
            while (m.find()) {
                if (chars == null) chars = raw.toCharArray();
                for (int i = m.start(); i < m.end(); i++) chars[i] = ' ';
            }
        }
        return chars == null ? raw : new String(chars);
    }

    private static List<Pattern> concat(List<Pattern> a, List<Pattern> b) {
        List<Pattern> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private void loadRules() {
        List<Rule> rules = new ArrayList<>();
        try {
            readRules(rules);
        } finally {
            this.rules = List.copyOf(rules);
        }
    }

    private void readRules(List<Rule> rules) {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(rulesFile);
        // All exception words in one pattern: one scan per message however long the list is.
        List<String> words = new ArrayList<>();
        for (String word : file.getStringList("exceptions")) if (word != null && !word.isBlank()) words.add(Pattern.quote(word.strip()));
        exceptions = words.isEmpty() ? List.of() : List.of(Pattern.compile(WORD_START + "(?:" + String.join("|", words) + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        ConfigurationSection root = file.getConfigurationSection("rules");
        if (root == null) {
            problems().add("features/chatfilter/blocked.yml: no rules");
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            Action action;
            try {
                action = Action.valueOf(s.getString("action", "CANCEL").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems().add("features/chatfilter/blocked.yml: " + name + ": action must be REPLACE, CANCEL or WARN");
                continue;
            }
            List<Pattern> patterns = new ArrayList<>();
            for (String regex : s.getStringList("patterns")) {
                try {
                    patterns.add(Pattern.compile(lead(regex), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                } catch (PatternSyntaxException e) {
                    problems().add("features/chatfilter/blocked.yml: " + name + ": bad pattern '" + regex + "' (" + e.getDescription() + ")");
                }
            }
            int strikes = Math.max(0, s.getInt("strikes", action == Action.WARN ? 0 : 1));
            rules.add(new Rule(name, action, s.getBoolean("alert", true), s.getString("bypass", ""), patterns,
                    strikes, s.getStringList("commands")));
        }
    }

    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";

    /**
     * Same matches, less work: "(?<![\p{L}\p{N}])f+uck" becomes "f(?<![\p{L}\p{N}].)f*uck". The
     * lookbehind (the costly part) then only runs where an "f" is, not at every character of
     * every message. Anything not of that exact shape is left as written.
     */
    static String lead(String regex) {
        int at = WORD_START.length();
        if (!regex.startsWith(WORD_START) || regex.length() < at + 2) return regex;
        char c = regex.charAt(at), next = regex.charAt(at + 1);
        if (!Character.isLetterOrDigit(c) || next == '*' || next == '?' || next == '{') return regex;
        boolean plus = next == '+';
        if (plus && at + 2 < regex.length() && "+?".indexOf(regex.charAt(at + 2)) >= 0) return regex; // f++ / f+?
        return c + "(?<![\\p{L}\\p{N}].)" + (plus ? c + "*" : "") + regex.substring(at + (plus ? 2 : 1));
    }

    /** The active filter, or null when the feature is off. */
    public static ChatFilterFeature of(VexCore plugin) {
        return plugin.features().get("chatfilter") instanceof ChatFilterFeature f ? f : null;
    }

    // ── Folding ───────────────────────────────────────────────────────────

    /**
     * Plain lower-case letters and digits; everything else is one word break. Works by code
     * point, so "fancy font" letters (𝐟𝐮𝐜𝐤, 𝓯𝓾𝓬𝓴: two chars each) fold back to plain ones.
     */
    static Folded fold(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        int[] map = new int[raw.length() + 1];
        for (int i = 0; i < raw.length(); ) {
            int at = i;
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            char base;
            if (cp < 0x80) {
                base = Character.toLowerCase((char) cp); // plain ASCII: no Unicode work
            } else {
                if (invisible(cp)) continue;
                String decomposed = Normalizer.normalize(Character.toString(cp), Normalizer.Form.NFKD);
                int first = decomposed.isEmpty() ? cp : decomposed.codePointAt(0);
                base = first > 0xFFFF ? ' ' : Character.toLowerCase((char) first);
            }
            Character folded = FOLD.get(base);
            if (folded != null) base = folded;
            if (Character.getType(base) == Character.NON_SPACING_MARK || (base >= 0x80 && invisible(base))) continue;
            if (Character.isLetterOrDigit(base)) {
                map[out.length()] = at;
                out.append(base);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                map[out.length()] = at;
                out.append(' ');
            }
        }
        return new Folded(out.toString().strip(), Arrays.copyOf(map, out.length()));
    }

    /** Joins runs of 1-2 letter pieces: "f u c k" -> "fuck", "who read" stays. */
    static Folded glue(Folded f) {
        String[] pieces = f.text.split(" ");
        StringBuilder out = new StringBuilder(f.text.length());
        int[] map = new int[f.text.length() + 1];
        int at = 0;
        boolean inRun = false;
        for (String piece : pieces) {
            if (piece.isEmpty()) continue;
            boolean shortPiece = piece.length() <= 2;
            if (out.length() > 0 && !(shortPiece && inRun)) {
                map[out.length()] = f.map[Math.max(0, at - 1)];
                out.append(' ');
            }
            for (int i = 0; i < piece.length(); i++) {
                map[out.length()] = f.map[at + i];
                out.append(piece.charAt(i));
            }
            at += piece.length() + 1;
            inRun = shortPiece;
        }
        return new Folded(out.toString(), Arrays.copyOf(map, out.length()));
    }

    /** Levenshtein similarity in percent. */
    static int similarity(String a, String b) {
        if (a.equals(b)) return 100;
        int n = a.length(), m = b.length();
        if (n == 0 || m == 0) return 0;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return (int) Math.round(100.0 * (1.0 - (double) prev[m] / Math.max(n, m)));
    }

    /** "word" -> a pattern for w+o+r+d+ with the usual endings, like /chatfilter regex writes. */
    static String regexFor(String word) {
        String letters = fold(word).text.replace(" ", "");
        StringBuilder p = new StringBuilder("(?<![\\p{L}\\p{N}])");
        for (char c : letters.toCharArray()) p.append(Pattern.quote(String.valueOf(c))).append('+');
        return p.append("(?:s|es|ed|er|ers|ing|in|y|z|a)?(?![\\p{L}\\p{N}])").toString();
    }

    // ── Rules ─────────────────────────────────────────────────────────────

    /** Original-text ranges a rule hits, over all three passes. */
    private static List<int[]> hits(Rule rule, String raw, Folded[] extra) {
        List<int[]> out = new ArrayList<>();
        for (Pattern p : rule.patterns) {
            Matcher m = p.matcher(raw);
            while (m.find()) if (m.end() > m.start()) out.add(new int[]{m.start(), m.end()});
            for (Folded f : extra) {
                m = p.matcher(f.text);
                while (m.find()) {
                    if (m.end() <= m.start() || f.map.length == 0) continue;
                    int last = f.map[Math.min(m.end(), f.map.length) - 1];
                    // End after the whole character (fancy-font letters are two chars long).
                    int start = f.map[m.start()], end = last + Character.charCount(raw.codePointAt(last));
                    // Leetspeak turns digits into letters: "455" reads "ass", "7175" reads "tits".
                    // A hit needs at least one real letter, so prices and coordinates stay clean.
                    if (hasLetter(raw, start, end)) out.add(new int[]{start, end});
                }
            }
        }
        return out;
    }

    private static boolean hasLetter(String raw, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(raw.length(), end); ) {
            int cp = raw.codePointAt(i);
            if (Character.isLetter(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** The first rule (in file order) that catches the text, and where. */
    private Map.Entry<Rule, List<int[]>> firstRule(Player player, String raw) {
        Folded folded = fold(raw);
        Folded glued = glue(folded);
        // Passes that would read the same text as an earlier one are skipped: for plain chat
        // ("gg anyone at spawn") the folded and glued copies are usually the raw text again.
        boolean foldedNew = !folded.text.equalsIgnoreCase(raw);
        boolean gluedNew = !glued.text.equals(folded.text);
        Folded[] extra = foldedNew && gluedNew ? new Folded[]{folded, glued} : foldedNew ? new Folded[]{folded}
                : gluedNew ? new Folded[]{glued} : new Folded[0];
        // Every rule is looked at, so a swear (REPLACE) can't carry a scam or a link (CANCEL)
        // through: any CANCEL wins, else every REPLACE hit is masked together, else a WARN.
        Rule replace = null, warn = null;
        List<int[]> masked = new ArrayList<>();
        for (Rule rule : rules) {
            if (player != null && !rule.bypass.isEmpty() && player.hasPermission(rule.bypass)) continue;
            List<int[]> hits = hits(rule, raw, extra);
            if (hits.isEmpty()) continue;
            switch (rule.action) {
                case CANCEL -> {
                    return Map.entry(rule, hits);
                }
                case REPLACE -> {
                    if (replace == null) replace = rule;
                    masked.addAll(hits);
                }
                case WARN -> {
                    if (warn == null) warn = rule;
                }
            }
        }
        if (replace != null) return Map.entry(replace, masked);
        return warn == null ? null : Map.entry(warn, List.of());
    }

    private String mask(String raw, List<int[]> hits) {
        char[] chars = raw.toCharArray();
        boolean[] masked = new boolean[chars.length];
        for (int[] h : hits) for (int i = Math.max(0, h[0]); i < Math.min(chars.length, h[1]); i++) masked[i] = true;
        String mask = settings.mask();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            if (!masked[i]) out.append(chars[i]);
            else if (i == 0 || !masked[i - 1]) out.append(mask);
        }
        return out.toString();
    }

    /** True when a name (team, item) is caught by no rule at all. */
    public boolean cleanName(String name) {
        Map.Entry<Rule, List<int[]>> hit = firstRule(null, withoutAllowed(stripInvisible(name)));
        return hit == null || hit.getKey().action == Action.WARN;
    }

    // ── Checking ──────────────────────────────────────────────────────────

    /**
     * Checks a message; source is chat, msg, team or command. The sender is told when it is
     * blocked, alerts go out, hits are logged.
     */
    public Verdict check(Player player, String message, String source) {
        String raw = ZALGO.matcher(stripInvisible(message == null ? "" : message)).replaceAll("$1").strip();
        if (raw.isEmpty()) return new Verdict(true, null, "EMPTY");
        // vexcore.chatfilter.bypass skips the checks (slowmode, join delay, spam, caps); operators
        // skip them with op-bypass.chat-cooldowns, and the word rules with op-bypass.chat-filter.
        boolean bypass = player != null && com.vexorstudios.vexcore.core.Bypass.has(player, "vexcore.chatfilter.bypass", "chat-cooldowns");
        boolean opWords = player != null && com.vexorstudios.vexcore.core.Bypass.op(player, "chat-filter");
        if (!bypass && !opWords && player != null && !source.equals("command")) {
            long muted = mutedFor(player.getUniqueId());
            if (muted > 0) {
                msg(player, "filter-muted", "time", plugin.messages().time(muted));
                return new Verdict(true, null, "MUTED");
            }
        }
        if (!bypass && player != null) {
            String check = checks(player, raw, source.equals("chat"));
            if (check != null) {
                if (!check.equals("shouting-lowered")) {
                    msg(player, check, "seconds", check.equals("join-delay") ? joinLeft(player) : left(player), "max", settings.maxChars());
                    report(player, raw, check.toUpperCase(Locale.ROOT), "CANCEL", source, config().getBoolean("alerts.checks", false));
                    return new Verdict(true, null, check.toUpperCase(Locale.ROOT));
                }
                raw = raw.toLowerCase(Locale.ROOT);
            }
        }
        if (!settings.words() || opWords) return new Verdict(false, raw, null);
        Map.Entry<Rule, List<int[]>> hit = firstRule(player, withoutAllowed(raw));
        if (hit == null) return new Verdict(false, raw, null);
        Rule rule = hit.getKey();
        report(player, raw, rule.name, rule.action.name(), source, rule.alert);
        if (player != null) punish(player, rule);
        return switch (rule.action) {
            case WARN -> new Verdict(false, raw, rule.name);
            case REPLACE -> {
                // The message goes out masked, and the sender hears that it wasn't allowed.
                if (player != null && settings.notifyMask()) msg(player, "censored");
                yield new Verdict(false, mask(raw, hit.getValue()), rule.name);
            }
            case CANCEL -> {
                if (player != null) msg(player, "blocked");
                yield new Verdict(true, null, rule.name);
            }
        };
    }

    private long left(Player player) {
        State st = states.get(player.getUniqueId());
        long wait = settings.slowMs();
        return st == null ? 0 : Math.max(1, (st.last + wait - System.currentTimeMillis() + 999) / 1000);
    }

    private long joinLeft(Player player) {
        State st = states.get(player.getUniqueId());
        return st == null ? 0 : Math.max(1, (st.joined + settings.joinDelayMs() - System.currentTimeMillis() + 999) / 1000);
    }

    // ── Strikes ───────────────────────────────────────────────────────────

    /** Seconds the filter still mutes this player, 0 if not muted. */
    private long mutedFor(UUID id) {
        Strikes r = records.get(id);
        if (r == null) return 0;
        long left = r.mutedUntil - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** A rule caught the player: strikes, and a mute or commands when a threshold is crossed. */
    private void punish(Player player, Rule rule) {
        if (!rule.commands.isEmpty()) runCommands(rule.commands, player, rule.name, 0);
        if (rule.strikes <= 0 || !config().getBoolean("strikes.enabled", true)) return;
        long now = System.currentTimeMillis();
        long forget = Math.max(1, config().getLong("strikes.forget-minutes", 30)) * 60_000L;
        int before, after;
        Strikes r = records.computeIfAbsent(player.getUniqueId(), k -> new Strikes());
        synchronized (r) {
            if (now - r.lastStrike > forget) r.strikes = 0;
            before = r.strikes;
            r.strikes += rule.strikes;
            r.lastStrike = now;
            after = r.strikes;
        }
        ConfigurationSection levels = config().getConfigurationSection("strikes.punishments");
        String reached = null;
        if (levels != null) for (String key : levels.getKeys(false)) {
            int at;
            try {
                at = Integer.parseInt(key.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (before < at && after >= at && (reached == null || at > Integer.parseInt(reached.trim()))) reached = key;
        }
        if (reached == null) {
            msg(player, "strike", "strikes", after, "rule", rule.name);
            return;
        }
        ConfigurationSection level = levels.getConfigurationSection(reached);
        long mute = level == null ? -1 : com.vexorstudios.vexcore.core.Time.seconds(level.getString("mute", ""));
        if (mute > 0) synchronized (r) {
            r.mutedUntil = Math.max(r.mutedUntil, now + mute * 1000);
        }
        Map<String, Object> ph = Map.of("player", player.getName(), "strikes", after, "rule", rule.name,
                "time", plugin.messages().time(Math.max(0, mute)));
        msg(player, mute > 0 ? "strike-muted" : "strike", ph);
        List<CommandSender> staff = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("vexcore.chatfilter.alerts")) staff.add(p);
        staff.add(Bukkit.getConsoleSender());
        broadcast(staff, "strike-alert", ph);
        if (level != null) runCommands(level.getStringList("commands"), player, rule.name, after);
    }

    private void runCommands(List<String> commands, Player player, String rule, int strikes) {
        if (commands.isEmpty() || !plugin.isEnabled()) return;
        Map<String, Object> ph = Map.of("player", player.getName(), "uuid", player.getUniqueId().toString(),
                "rule", rule, "strikes", strikes);
        Scheduler.global(() -> {
            for (String line : commands) {
                String command = com.vexorstudios.vexcore.core.Text.fill(line, ph).strip();
                if (command.startsWith("/")) command = command.substring(1);
                if (!command.isEmpty()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        });
    }

    /** Null if every check passes, "shouting-lowered" to lower the message, else the message key. */
    /**
     * {@code chat}: public chat. Slowmode and repeat only watch public chat; a /r right after
     * chatting, or the same "ty" to two people, is not spam.
     */
    private String checks(Player player, String raw, boolean chat) {
        State st = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        long now = System.currentTimeMillis();
        Settings cfg = settings;
        synchronized (st) {
            if (cfg.joinDelayMs() > 0 && st.joined > 0 && now - st.joined < cfg.joinDelayMs()) return "join-delay";
            if (chat && cfg.slowmode() && now - st.last < cfg.slowMs()) return "slowmode";
            if (cfg.length()) {
                if (raw.length() > cfg.maxChars()) return "too-long";
                if (cfg.sameInRow() > 0 && sameInARow(cfg.sameInRow()).matcher(raw).find()) return "spamming";
            }
            if (cfg.symbols() && raw.length() >= cfg.symbolsMin() && symbolPercent(raw) > cfg.symbolsPercent()) return "symbols";
            boolean repeat = chat && cfg.repeat();
            String norm = repeat ? fold(raw).text : "";
            if (repeat && !norm.isEmpty() && now - st.previousAt < cfg.repeatMs()
                    && similarity(st.previous, norm) >= cfg.repeatPercent()) {
                return "repeating";
            }
            String result = null;
            if (cfg.shouting()) {
                int upper = 0;
                for (int i = 0; i < raw.length(); i++) if (Character.isUpperCase(raw.charAt(i))) upper++;
                if (upper > cfg.maxUpper()) {
                    if (cfg.shoutCancel()) return "shouting";
                    result = "shouting-lowered";
                }
            }
            if (chat) {
                st.last = now;
                st.previous = norm;
                st.previousAt = now;
            }
            return result;
        }
    }

    /** Percent of the visible characters that are neither letters nor digits (symbols, emoji). */
    static int symbolPercent(String raw) {
        int visible = 0, symbols = 0;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            visible++;
            if (!Character.isLetterOrDigit(cp)) symbols++;
        }
        return visible == 0 ? 0 : symbols * 100 / visible;
    }

    private volatile Pattern sameInARow;
    private volatile int sameCount = -1;

    private Pattern sameInARow(int same) {
        if (same != sameCount) {
            sameInARow = Pattern.compile("(\\D)\\1{" + same + ",}"); // digits may repeat: "selling for 1000000"
            sameCount = same;
        }
        return sameInARow;
    }

    // ── Alerts and log ────────────────────────────────────────────────────

    private void report(Player player, String message, String rule, String action, String source, boolean alert) {
        if (player == null) return;
        if (alert && config().getBoolean("alerts.enabled", true)) {
            List<CommandSender> staff = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("vexcore.chatfilter.alerts")) staff.add(p);
            staff.add(Bukkit.getConsoleSender());
            plugin.messages().broadcast(staff, config().getString("alerts.format", "%player% » %message% (%rule% %action%)"),
                    Map.of("player", player.getName(), "message", Component.text(message), "rule", rule, "action", action),
                    plugin.messages().prefix(this));
        }
        boolean isRule = rules.stream().anyMatch(r -> r.name.equals(rule));
        if (!config().getBoolean("log.enabled", true) || (!isRule && config().getBoolean("log.rules-only", true))) return;
        Database db = db();
        String uuid = player.getUniqueId().toString(), name = player.getName();
        long now = System.currentTimeMillis();
        db.queue("chat log", c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("chat_log")
                    + " (uuid, name, message, rule, action, source, time) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, message);
                ps.setString(4, rule);
                ps.setString(5, action);
                ps.setString(6, source);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
        });
    }

    // ── Scanned commands (/nick something...) ─────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        List<String> scanned = config().getStringList("scan.commands");
        String message = event.getMessage();
        if (scanned.isEmpty() || message.indexOf(' ') < 0) return;
        if (!scanned.contains(com.vexorstudios.vexcore.core.Commands.label(message))) return;
        String[] parts = message.substring(1).split(" ", 2);
        if (parts.length < 2 || parts[1].isBlank()) return; // nothing typed after the command
        Verdict v = check(event.getPlayer(), parts[1], "command");
        if (v.blocked) event.setCancelled(true);
        else if (!v.text.equals(parts[1])) event.setMessage("/" + parts[0] + " " + v.text);
    }

    /** Whether private messages are scanned (scan.private-messages). */
    public boolean scans(String source) {
        return config().getBoolean("scan." + (source.equals("msg") ? "private-messages" : source), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (settings.joinDelayMs() <= 0 || com.vexorstudios.vexcore.core.Bypass.has(event.getPlayer(), "vexcore.chatfilter.bypass", "chat-cooldowns")) return;
        State st = states.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new State());
        synchronized (st) {
            st.joined = System.currentTimeMillis();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId()); // strikes and mutes stay (records)
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void command(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "regex" -> {
                if (args.length < 2) {
                    msg(sender, "usage-regex");
                    return;
                }
                String word = args[1];
                if (fold(word).text.replace(" ", "").chars().distinct().count() < 3) {
                    msg(sender, "regex-invalid");
                    return;
                }
                String regex = regexFor(word);
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(rulesFile);
                String rule = config().getString("regex-rule", "CUSTOM");
                String path = "rules." + rule;
                for (String key : yml.getConfigurationSection("rules") == null ? List.<String>of() : yml.getConfigurationSection("rules").getKeys(false)) {
                    if (yml.getStringList("rules." + key + ".patterns").contains(regex)) {
                        msg(sender, "regex-known", "word", word, "rule", key);
                        return;
                    }
                }
                if (!yml.contains(path)) {
                    yml.set(path + ".enabled", true);
                    yml.set(path + ".action", "CANCEL");
                    yml.set(path + ".alert", true);
                    yml.set(path + ".bypass", "");
                }
                List<String> patterns = new ArrayList<>(yml.getStringList(path + ".patterns"));
                patterns.add(regex);
                yml.set(path + ".patterns", patterns);
                try {
                    yml.save(rulesFile);
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not save " + rulesFile + ": " + e.getMessage());
                    return;
                }
                loadRules();
                msg(sender, "regex-added", "word", word, "rule", rule);
            }
            case "remove" -> {
                if (args.length < 2) {
                    msg(sender, "usage-remove");
                    return;
                }
                String regex = regexFor(args[1]);
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(rulesFile);
                ConfigurationSection root = yml.getConfigurationSection("rules");
                String from = null;
                if (root != null) for (String key : root.getKeys(false)) {
                    List<String> patterns = new ArrayList<>(yml.getStringList("rules." + key + ".patterns"));
                    if (patterns.remove(regex)) {
                        yml.set("rules." + key + ".patterns", patterns);
                        from = key;
                    }
                }
                if (from == null) {
                    msg(sender, "remove-unknown", "word", args[1]);
                    return;
                }
                try {
                    yml.save(rulesFile);
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not save " + rulesFile + ": " + e.getMessage());
                    return;
                }
                loadRules();
                msg(sender, "removed", "word", args[1], "rule", from);
            }
            case "reload" -> {
                // Only blocked.yml and allowed: no full /vexcore reload needed after editing words.
                int known = problems().size();
                loadRules();
                loadAllowed();
                List<String> found = new ArrayList<>(problems().subList(known, problems().size()));
                problems().subList(known, problems().size()).clear(); // the full reload report finds them again
                msg(sender, "reloaded", "rules", rules.size(), "problems", found.size());
                for (String problem : found) sender.sendMessage(Component.text(problem));
            }
            case "strikes", "clear" -> {
                if (args.length < 2) {
                    msg(sender, "usage-strikes");
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) {
                    msg(sender, "unknown-player", "player", args[1]);
                    return;
                }
                if (sub.equals("clear")) {
                    records.remove(target.getUniqueId());
                    msg(sender, "strikes-cleared", "player", target.getName());
                    return;
                }
                Strikes r = records.get(target.getUniqueId());
                long forget = Math.max(1, config().getLong("strikes.forget-minutes", 30)) * 60_000L;
                int strikes = r == null || System.currentTimeMillis() - r.lastStrike > forget ? 0 : r.strikes;
                msg(sender, "strikes-info", "player", target.getName(), "strikes", strikes,
                        "muted", plugin.messages().time(mutedFor(target.getUniqueId())));
            }
            case "similar" -> {
                if (args.length < 3) {
                    msg(sender, "usage-similar");
                    return;
                }
                msg(sender, "similar", "first", args[1], "second", args[2], "percent", similarity(fold(args[1]).text, fold(args[2]).text));
            }
            case "test" -> {
                if (args.length < 2) {
                    msg(sender, "usage-test");
                    return;
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Map.Entry<Rule, List<int[]>> hit = firstRule(null, withoutAllowed(text));
                if (hit == null) msg(sender, "test-clean");
                else msg(sender, "test-hit", "rule", hit.getKey().name, "action", hit.getKey().action.name(),
                        "result", hit.getKey().action == Action.REPLACE ? mask(text, hit.getValue()) : text);
            }
            default -> msg(sender, "help");
        }
    }

    private void history(CommandSender sender, String label, String[] args) {
        Player viewer = player(sender);
        if (viewer == null) return;
        String who = args.length > 0 ? args[0] : null;
        OfflinePlayer target = who == null ? null : Bukkit.getOfflinePlayerIfCached(who);
        if (who != null && target == null) {
            msg(viewer, "unknown-player", "player", who);
            return;
        }
        Database db = db();
        String uuid = target == null ? null : target.getUniqueId().toString();
        db.query("chat history", c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, message, rule, action, source, time FROM " + db.table("chat_log")
                    + (uuid == null ? "" : " WHERE uuid = ?") + " ORDER BY time DESC LIMIT 450")) {
                if (uuid != null) ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), String.valueOf(rs.getLong(6))});
                }
            }
            return rows;
        }).thenAccept(rows -> Scheduler.entity(viewer, () -> {
            if (rows.isEmpty()) {
                msg(viewer, "history-empty");
                return;
            }
            String time = config().getString("log.time-format", "dd.MM.yyyy HH:mm");
            int wrap = Math.max(10, config().getInt("log.wrap", 30));
            open(viewer, "history", menu -> menu.paginate(rows, (r, slot) -> menu.place("entry", slot, Map.of(
                    "player", r[0], "message", wrapLines(r[1], wrap), "rule", r[2], "action", r[3], "source", r[4],
                    "time", com.vexorstudios.vexcore.core.Dates.format(Long.parseLong(r[5]), time)), null)));
        }));
    }

    /** Wraps text into lines for lore; "&f" keeps every line white. */
    private static String wrapLines(String text, int width) {
        StringBuilder out = new StringBuilder();
        int line = 0;
        for (String word : text.replace("&", "").replace("<", "").split(" ")) {
            if (line > 0 && line + word.length() > width) {
                out.append("\n&f");
                line = 0;
            } else if (line > 0) {
                out.append(' ');
                line++;
            }
            out.append(word);
            line += word.length();
        }
        return out.toString();
    }
}
