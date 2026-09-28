package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends configured messages.
 *
 * <p>A message is looked up under {@code messages.<key>} in the feature's own config.yml first
 * and in globalmessages.yml second, so a feature can override any global text. It may be one
 * line or a list of lines; an empty string sends nothing. Each line can start with
 * <pre>
 *   [actionbar] text          shown above the hotbar
 *   [title] Title|Subtitle    shown as a title
 *   [sound] name;volume;pitch played to the receiver
 * </pre>
 * and anything else goes to chat. {@code {prefix}} is the feature's {@code prefix} (or the global
 * one). If {@code sounds.<key>} exists next to the message, that sound is played with it.
 */
public final class Messages {

    private final VexCore plugin;
    private YamlConfiguration global = new YamlConfiguration();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public Messages(VexCore plugin) {
        this.plugin = plugin;
    }

    public void load(YamlConfiguration global) {
        load(global, plugin == null ? new YamlConfiguration() : plugin.settings());
    }

    /** {@code settings} is config.yml, for its {@code sounds:} switches. */
    public void load(YamlConfiguration global, YamlConfiguration settings) {
        this.global = global;
        warned.clear();
        sounds.clear();
        titleTimes = times(global.getConfigurationSection("title-times"));
        globalSounds.clear();
        lastSent.clear();
        SoundSpec.configure(settings);
        clickOnMessages = settings.getBoolean("sounds.click-on-messages", false);
    }

    /** {@code sounds.click-on-messages} in config.yml: whether default-sound is used at all. */
    private volatile boolean clickOnMessages;

    /**
     * When each message was last sent to each player. A message that comes again within
     * {@link #REPEAT_MS} (a teleport countdown, the combat timer, the vanish reminder) is a tick:
     * its sound only plays the first time, unless {@code sounds.ticking} is on.
     */
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private static final long REPEAT_MS = 2500;

    private boolean repeated(CommandSender receiver, Feature feature, String key) {
        if (!(receiver instanceof Player player)) return false;
        long now = System.currentTimeMillis();
        Long before = lastSent.put(player.getUniqueId() + ":" + (feature == null ? "" : feature.id()) + ":" + key, now);
        if (lastSent.size() > 4096) lastSent.values().removeIf(t -> now - t > REPEAT_MS);
        return before != null && now - before < REPEAT_MS && !SoundSpec.ticking();
    }

    /** How long [title] lines fade in, stay and fade out (title-times in globalmessages.yml). */
    static volatile Title.Times titleTimes = Title.DEFAULT_TIMES;

    /** Fade-in, stay and fade-out in ticks from a section like {@code {fade-in: 10, stay: 70, fade-out: 20}}. */
    public static Title.Times times(org.bukkit.configuration.ConfigurationSection s) {
        if (s == null) return Title.DEFAULT_TIMES;
        return Title.Times.times(java.time.Duration.ofMillis(Math.max(0, s.getInt("fade-in", 10)) * 50L),
                java.time.Duration.ofMillis(Math.max(0, s.getInt("stay", 70)) * 50L),
                java.time.Duration.ofMillis(Math.max(0, s.getInt("fade-out", 20)) * 50L));
    }

    public static Title.Times titleTimes() {
        return titleTimes;
    }

    /** Parsed sounds per feature and key; features and files are new after every reload. */
    private final Map<Feature, Map<String, java.util.Optional<SoundSpec>>> sounds =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final Map<String, java.util.Optional<SoundSpec>> globalSounds = new ConcurrentHashMap<>();

    public YamlConfiguration global() {
        return global;
    }

    public String prefix(Feature feature) {
        if (feature != null) {
            String own = feature.config().getString("prefix");
            if (own != null) return own;
        }
        return global.getString("prefix", "");
    }

    /** The raw value of a message: feature first, then global. Null when neither has it. */
    public Object raw(Feature feature, String key) {
        if (feature != null) {
            Object own = feature.config().get("messages." + key);
            if (own != null) return own;
        }
        return global.get(key);
    }

    public void send(Feature feature, CommandSender to, String key, Map<String, ?> placeholders) {
        if (to != null) sendTo(feature, List.of(to), key, placeholders, true);
    }

    /**
     * Message {@code key} (with its sound) to every receiver. The message is looked up and each
     * line prepared once; a line that is the same for everyone is also parsed once.
     */
    public void send(Feature feature, Iterable<? extends CommandSender> to, String key, Map<String, ?> placeholders) {
        sendTo(feature, to, key, placeholders, false);
    }

    private void sendTo(Feature feature, Iterable<? extends CommandSender> to, String key, Map<String, ?> placeholders, boolean direct) {
        Object raw = raw(feature, key);
        if (raw == null) {
            if (warned.add((feature == null ? "global" : feature.id()) + ":" + key)) {
                plugin.getLogger().warning("Missing message '" + key + "' ("
                        + (feature == null ? "globalmessages.yml" : Files.configPath(feature.id())) + ")");
            }
            return;
        }
        List<Line> lines = prepare(raw, placeholders, prefix(feature));
        if (lines.isEmpty()) return; // a message set to "" is off, sound included
        SoundSpec sound = messageSound(feature, key, direct, lines.stream().anyMatch(l -> l.kind == Kind.SOUND),
                lines.stream().allMatch(l -> l.kind == Kind.ACTIONBAR));
        for (CommandSender receiver : to) {
            boolean quiet = repeated(receiver, feature, key);
            runOnReceiver(receiver, () -> {
                if (sound != null && !quiet && receiver instanceof Player player) sound.play(player);
                for (Line line : lines) if (!quiet || line.kind != Kind.SOUND) line.send(receiver, placeholders);
            });
        }
    }

    /**
     * The sound of a message: its own, else (only when {@code sounds.click-on-messages} is on)
     * default-sound for a direct chat message. Action-bar status lines never get the click.
     */
    SoundSpec messageSound(Feature feature, String key, boolean direct, boolean inline, boolean actionbarOnly) {
        if (inline) return null;
        SoundSpec specific = sound(feature, key);
        if (specific != null) return specific;
        boolean explicit = (feature != null && feature.config().contains("sounds." + key)) || global.contains("sounds." + key);
        return direct && !explicit && !actionbarOnly && clickOnMessages ? SoundSpec.of(global.get("default-sound")) : null;
    }

    /** The sound that goes with a message key, or null. */
    public SoundSpec sound(Feature feature, String key) {
        Map<String, java.util.Optional<SoundSpec>> cache = feature == null ? globalSounds
                : sounds.computeIfAbsent(feature, f -> new ConcurrentHashMap<>());
        return cache.computeIfAbsent(key, k -> {
            Object own = feature == null ? null : feature.config().get("sounds." + k);
            Object configured = own != null ? own : global.get("sounds." + k);
            SoundSpec spec = SoundSpec.of(configured);
            // No sound of its own: an error message gets error-sound (the villager "hmm").
            if (configured == null && isError(raw(feature, k))) spec = SoundSpec.of(global.get("error-sound"));
            return java.util.Optional.ofNullable(spec);
        }).orElse(null);
    }

    private static final java.util.regex.Pattern CODES = java.util.regex.Pattern.compile("&#[0-9a-fA-F]{6}|[&§][0-9a-fk-orA-FK-OR]|<[^>]*>");

    /** Whether a message contains error-marker (globalmessages.yml), colour codes ignored. */
    private boolean isError(Object raw) {
        String marker = global.getString("error-marker", "ERROR");
        if (raw == null || marker == null || marker.isEmpty()) return false;
        for (String line : Text.lines(raw)) if (CODES.matcher(line).replaceAll("").contains(marker)) return true;
        return false;
    }

    /** Sends a config value (string or list) that is not under {@code messages}. */
    public void deliver(CommandSender to, Object raw, Map<String, ?> placeholders, String prefix) {
        if (to == null) return;
        List<Line> lines = prepare(raw, placeholders, prefix);
        if (lines.isEmpty()) return;
        SoundSpec fallback = lines.stream().anyMatch(l -> l.kind == Kind.SOUND) ? null
                : isError(raw) ? SoundSpec.of(global.get("error-sound"))
                : clickOnMessages ? SoundSpec.of(global.get("default-sound")) : null;
        runOnReceiver(to, () -> {
            if (to instanceof Player player && fallback != null) fallback.play(player);
            for (Line line : lines) line.send(to, placeholders);
        });
    }

    /** Sends a config value to many receivers; lines that are the same for everyone are parsed once. */
    public void broadcast(Iterable<? extends CommandSender> to, Object raw, Map<String, ?> placeholders, String prefix) {
        List<Line> lines = prepare(raw, placeholders, prefix);
        if (lines.isEmpty()) return;
        for (CommandSender receiver : to) runOnReceiver(receiver, () -> {
            for (Line line : lines) line.send(receiver, placeholders);
        });
    }

    private void runOnReceiver(CommandSender receiver, Runnable action) {
        if (receiver instanceof Player p && plugin.isEnabled() && !Bukkit.isOwnedByCurrentRegion(p)) Scheduler.entity(p, action);
        else action.run();
    }

    // ── Lines ─────────────────────────────────────────────────────────────

    private enum Kind {CHAT, ACTIONBAR, TITLE, SOUND}

    /**
     * One line of a message. {@code first}/{@code second} are already parsed when the line looks
     * the same to everyone (nothing left for PlaceholderAPI or %vexcore_...% once the message's
     * own placeholders are in); otherwise each receiver gets their own parse.
     */
    private record Line(Kind kind, String first, String second, Component a, Component b, SoundSpec sound) {

        void send(CommandSender to, Map<String, ?> placeholders) {
            Player viewer = to instanceof Player p ? p : null;
            switch (kind) {
                case CHAT -> to.sendMessage(a != null ? a : Text.links(Text.parse(first, viewer, placeholders), first));
                case ACTIONBAR -> to.sendActionBar(a != null ? a : Text.parse(first, viewer, placeholders));
                case TITLE -> to.showTitle(Title.title(a != null ? a : Text.parse(first, viewer, placeholders),
                        b != null ? b : second == null ? Component.empty() : Text.parse(second, viewer, placeholders), titleTimes));
                case SOUND -> {
                    if (sound != null && viewer != null) sound.play(viewer);
                }
            }
        }
    }

    private static List<Line> prepare(Object raw, Map<String, ?> placeholders, String prefix) {
        if (raw == null) return List.of();
        List<String> texts = Text.lines(raw);
        if (texts.size() == 1 && texts.getFirst().isBlank()) return List.of();
        List<Line> out = new java.util.ArrayList<>(texts.size());
        for (String line : texts) {
            if (prefix != null && line.contains("{prefix}")) line = line.replace("{prefix}", prefix);
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("[actionbar]")) {
                String t = trimmed.substring(11).trim();
                out.add(new Line(Kind.ACTIONBAR, t, null, shared(t, placeholders), null, null));
            } else if (trimmed.startsWith("[title]")) {
                String[] parts = trimmed.substring(7).trim().split("\\|", 2);
                String sub = parts.length > 1 ? parts[1] : null;
                out.add(new Line(Kind.TITLE, parts[0], sub, shared(parts[0], placeholders),
                        sub == null ? Component.empty() : shared(sub, placeholders), null));
            } else if (trimmed.startsWith("[sound]")) {
                out.add(new Line(Kind.SOUND, null, null, null, null, SoundSpec.of(Text.fill(trimmed.substring(7).trim(), placeholders))));
            } else {
                Component c = shared(line, placeholders);
                out.add(new Line(Kind.CHAT, line, null, c == null ? null : Text.links(c, line), null, null));
            }
        }
        return out;
    }

    /** The parsed line if every receiver sees the same thing, else null. */
    private static Component shared(String text, Map<String, ?> placeholders) {
        if (text.indexOf('%') >= 0) {
            String rest = text;
            if (placeholders != null) for (String key : placeholders.keySet()) {
                if (rest.indexOf('%') < 0) break;
                rest = rest.replace("%" + key + "%", "");
            }
            if (rest.indexOf('%') >= 0) return null; // PlaceholderAPI or %vexcore_...% per receiver
        }
        return Text.parse(text, null, placeholders);
    }

    /** A duration written with the labels under {@code time:} in globalmessages.yml. */
    public String time(long seconds) {
        return Time.format(seconds, global.getConfigurationSection("time"));
    }

    /** Turns {@code "key", value, "key2", value2} into a map. */
    public static Map<String, Object> map(Object... kv) {
        if (kv == null || kv.length == 0) return Map.of();
        Map<String, Object> out = new HashMap<>(kv.length);
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(String.valueOf(kv[i]), kv[i + 1]);
        return out;
    }

    /** Console gets messages too; this is the "everyone" audience for broadcasts. */
    public static Iterable<CommandSender> everyone() {
        java.util.ArrayList<CommandSender> all = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        all.add(Bukkit.getConsoleSender());
        return all;
    }
}
