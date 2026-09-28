package com.vexorstudios.vexcore.features.chat;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Messages;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.SoundSpec;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.core.Time;
import com.vexorstudios.vexcore.features.chatfilter.ChatFilterFeature;
import com.vexorstudios.vexcore.features.msg.MsgFeature;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Public chat, handled like LifestealCore's:
 * <ul>
 *   <li>format: "{prefix}{name} ▷ {message}", prefix and suffix from placeholders
 *       (%luckperms_prefix%), a profile card on hover (the whole line or only the name, lines
 *       whose placeholder didn't resolve are dropped), a command on click;</li>
 *   <li>render tokens: [item], [inv], [ec] (each with its own trigger, permission and look),
 *       shared as read-only snapshots; they also work in /msg and staff chat;</li>
 *   <li>@Name mentions light up and ping that player;</li>
 *   <li>/chattoggle hides chat for yourself, /mutechat [time] locks it for everyone (it
 *       survives a restart), /clearchat wipes it.</li>
 * </ul>
 * Whatever players type is never parsed for tags; colour codes only with vexcore.chat.color.
 */
public final class ChatFeature extends Feature implements Listener {

    record Snapshot(String title, ItemStack[] items, long expires) {
    }

    private static final Pattern UNRESOLVED = Pattern.compile("%[A-Za-z0-9_]+%");

    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private File lockFile;
    private volatile long lockedUntil; // 0 open, -1 until unlocked, else a time

    @Override
    protected void enable() {
        lockFile = plugin.files().data("chat.yml");
        lockedUntil = YamlConfiguration.loadConfiguration(lockFile).getLong("locked-until", 0);
        listen(this);
        toggle("chat", true, p -> flip(p, "chat", "shown", "hidden"));
        toggle("mentions", true, p -> flip(p, "mentions", "mentions-on", "mentions-off"));
        command("chattoggle", (sender, label, args) -> {
            Player p = player(sender);
            if (p != null) flip(p, "chat", "shown", "hidden");
        });
        command("mentiontoggle", (sender, label, args) -> {
            Player p = player(sender);
            if (p != null) flip(p, "mentions", "mentions-on", "mentions-off");
        });
        command("mutechat", this::mute);
        command("clearchat", (sender, label, args) -> {
            int lines = config().getInt("clear.lines", 100);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("vexcore.chat.bypass")) continue;
                for (int i = 0; i < lines; i++) p.sendMessage(Component.empty());
            }
            broadcast(Messages.everyone(), "cleared", Map.of("player", sender.getName()));
        });
        command("chatview", (sender, label, args) -> {
            Player player = player(sender);
            if (player != null && args.length == 1) view(player, args[0]);
        });
    }

    // ── Chat lock ─────────────────────────────────────────────────────────

    private boolean locked() {
        long until = lockedUntil;
        return until == -1 || until > System.currentTimeMillis();
    }

    private void setLock(long until) {
        lockedUntil = until;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("locked-until", until);
        try {
            yml.save(lockFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + lockFile + ": " + e.getMessage());
        }
    }

    private void mute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (locked()) {
                setLock(0);
                broadcast(Messages.everyone(), "unmuted", Map.of("player", sender.getName()));
            } else {
                setLock(-1);
                broadcast(Messages.everyone(), "muted", Map.of("player", sender.getName()));
            }
            return;
        }
        long seconds = Time.seconds(args[0]);
        if (seconds <= 0) {
            msg(sender, "usage-mute");
            return;
        }
        setLock(System.currentTimeMillis() + seconds * 1000);
        broadcast(Messages.everyone(), "muted-timed", Map.of("player", sender.getName(), "time", plugin.messages().time(seconds)));
    }

    // ── Chat ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (locked() && !player.hasPermission("vexcore.chat.bypass")) {
            event.setCancelled(true);
            msg(player, "muted-blocked");
            return;
        }
        String raw = Text.plain(event.message());
        ChatFilterFeature filter = ChatFilterFeature.of(plugin);
        if (filter != null && filter.scans("chat")) {
            ChatFilterFeature.Verdict v = filter.check(player, raw, "chat");
            if (v.blocked()) {
                event.setCancelled(true);
                return;
            }
            raw = v.text();
        }
        event.message(render(player, typed(player, raw, "vexcore.chat.color"), raw));

        MsgFeature msg = plugin.features().get("msg") instanceof MsgFeature m ? m : null;
        event.viewers().removeIf(a -> a instanceof Player viewer && !viewer.equals(player)
                && (!plugin.toggles().isOn(viewer.getUniqueId(), "chat") || (msg != null && msg.ignores(viewer.getUniqueId(), player.getUniqueId()))));

        // Mentions: @name lights up for everyone and pings that player, unless either of the
        // two ignores the other (or the player turned mentions off).
        if (raw.indexOf('@') >= 0 && config().getBoolean("mentions.enabled", true)
                && (config().getString("mentions.permission", "").isEmpty() || player.hasPermission(config().getString("mentions.permission")))) {
            Component message = event.message();
            java.util.Set<Player> pinged = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher m = MENTION.matcher(raw);
            while (m.find()) {
                Player target = Bukkit.getPlayerExact(m.group(1));
                if (target == null || target.equals(player) || !pinged.add(target)) continue;
                if (!com.vexorstudios.vexcore.core.Visibility.knows(player, target) || !plugin.toggles().isOn(target.getUniqueId(), "mentions")
                        || (msg != null && (msg.ignores(target.getUniqueId(), player.getUniqueId()) || msg.ignores(player.getUniqueId(), target.getUniqueId())))) {
                    pinged.remove(target);
                    continue;
                }
                Pattern at = Pattern.compile("(?i)(?<![A-Za-z0-9_])@" + Pattern.quote(target.getName()) + "(?![A-Za-z0-9_])");
                Component lit = Text.parse(config().getString("mentions.format", "%color%@%player%&r")
                        .replace("%color%", config().getString("mentions.color", "&#FFD900")), null, Map.of("player", target.getName()));
                message = message.replaceText(b -> b.match(at).replacement(lit));
            }
            event.message(message);
            String prefix = config().getString("mentions.prefix", "&#FFD900&lMENTION &7▷ ");
            for (Player target : pinged) Scheduler.entity(target, () -> {
                String bar = config().getString("mentions.notify.actionbar", "%prefix%&f%player% mentioned you!");
                Map<String, Object> ph = Map.of("player", com.vexorstudios.vexcore.core.Visibility.name(player), "prefix", prefix);
                if (!bar.isEmpty()) target.sendActionBar(Text.parse(bar.replace("%prefix%", prefix), target, ph));
                SoundSpec sound = SoundSpec.of(config().get("mentions.notify.sound"));
                if (sound != null) sound.play(target);
            });
        }
        AtomicReference<Component> plain = new AtomicReference<>();
        AtomicReference<Component> staff = new AtomicReference<>();
        io.papermc.paper.chat.ChatRenderer renderer = (source, displayName, text, viewer) ->
                isStaff(viewer) ? line(source, text, staff, true) : line(source, text, plain, false);
        event.renderer(renderer);
        if (config().getBoolean("keep-format-on-top", true)) renderers.put(event, renderer);
    }

    /** The renderer each chat event got at LOW, put back at HIGHEST (see onChatLate). */
    private final Map<AsyncChatEvent, io.papermc.paper.chat.ChatRenderer> renderers =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Another chat plugin that sets its own renderer after VexCore (a "click to manage" hover for
     * staff, a different format) would replace the whole line and with it the profile card. With
     * keep-format-on-top VexCore's line is put back last; staff get their manage click from
     * hover.staff instead.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChatLate(AsyncChatEvent event) {
        io.papermc.paper.chat.ChatRenderer renderer = renderers.remove(event);
        if (renderer != null && event.renderer() != renderer) event.renderer(renderer);
    }

    /** Whether a chat viewer sees the staff card (manage click). */
    private boolean isStaff(Audience viewer) {
        if (!(viewer instanceof Player p) || !config().getBoolean("hover.staff.enabled", true)) return false;
        String permission = config().getString("hover.staff.permission", "vexcore.chat.manage");
        return permission.isEmpty() || p.hasPermission(permission);
    }

    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9_]{3,16})(?![A-Za-z0-9_])");

    /** What a player typed as a component: colour codes with the permission, never tags. */
    public static Component typed(Player player, String raw, String colourPermission) {
        return player.hasPermission(colourPermission) ? Text.parse(MiniMessage.miniMessage().escapeTags(raw)) : Component.text(raw);
    }

    /** The formatted line, built once and shared by every viewer (once more for staff). */
    private Component line(Player source, Component text, AtomicReference<Component> cached, boolean staff) {
        Component done = cached.get();
        if (done == null) {
            done = format(source, text, staff);
            cached.set(done);
        }
        return done;
    }

    /** The whole chat line of a player; {@code staff} adds the manage lines and click. */
    private Component format(Player source, Component message, boolean staff) {
        Map<String, Object> ph = new HashMap<>();
        // /hide: only the shared name, no rank, team or profile card that would give them away.
        boolean disguised = plugin.features().get("hide") instanceof com.vexorstudios.vexcore.features.hide.HideFeature h && h.isHidden(source.getUniqueId());
        String shown = disguised ? ((com.vexorstudios.vexcore.features.hide.HideFeature) plugin.features().get("hide")).shownName(source) : source.getName();
        ph.put("player", shown);
        String prefix = disguised ? "" : Text.papi(source, config().getString("prefix-placeholder", "%luckperms_prefix%"));
        String suffix = disguised ? "" : Text.papi(source, config().getString("suffix-placeholder", "%luckperms_suffix%"));
        String tag = disguised ? null : plugin.placeholders().resolve(source, "team_tag");
        String format = config().getString("format", "{prefix}{name} &7▷ &r{message}")
                .replace("{prefix}", UNRESOLVED.matcher(prefix).matches() ? "" : prefix)
                .replace("{suffix}", UNRESOLVED.matcher(suffix).matches() ? "" : suffix)
                .replace("{team}", tag == null ? "" : tag);
        Component hover = disguised ? null : hover(source, staff);
        String click = null;
        if (staff) {
            // Staff click the real name even when the player is disguised.
            String manage = config().getString("hover.staff.command", "punish %player%").strip();
            String label = manage.split(" ", 2)[0];
            // A command that isn't there (punishments turned off) falls back to the normal click.
            if (!manage.isEmpty() && Bukkit.getCommandMap().getCommand(label) != null) {
                click = "/" + manage.replace("%player%", source.getName());
            } else if (config().getBoolean("hover.click.enabled", true)) {
                click = "/" + config().getString("hover.click.command", "stats %player%").replace("%player%", source.getName());
            }
            if (disguised) hover = hover(source, true);
        } else if (!disguised && config().getBoolean("hover.click.enabled", true)) {
            click = "/" + config().getString("hover.click.command", "stats %player%").replace("%player%", source.getName());
        }
        boolean nameOnly = config().getString("hover.apply-to", "WHOLE-LINE").equalsIgnoreCase("NAME");
        int split = format.indexOf("{message}");
        String head = split < 0 ? format : format.substring(0, split);
        String tail = split < 0 ? "" : format.substring(split + "{message}".length());
        Component name = interactive(Text.parse(head.replace("{name}", shown), disguised ? null : source, ph), hover, click);
        // Siblings, not children of the name: otherwise the message takes the name's hover too.
        Component line = Component.text().append(name).append(message).append(Text.parse(tail, source, ph)).build();
        return nameOnly ? line : interactive(line, hover, click);
    }

    private static Component interactive(Component c, Component hover, String click) {
        if (hover != null) c = c.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hover));
        if (click != null) c = c.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(click));
        return c;
    }

    private record Card(Component card, Component staff, long until) {
    }

    private final Map<java.util.UUID, Card> cards = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The profile card, rebuilt at most every few seconds per player (it runs many placeholders).
     * Staff see the same card with the hover.staff lines under it, so the profile never goes
     * missing behind a "click to manage".
     */
    private Component hover(Player source, boolean staff) {
        long now = System.currentTimeMillis();
        Card c = cards.get(source.getUniqueId());
        if (c == null || c.until <= now) {
            Component card = config().getBoolean("hover.enabled", true) ? buildHover(source) : null;
            Component extra = lines(config().getStringList("hover.staff.lines"), source);
            Component forStaff = card == null ? extra : extra == null ? card : card.append(Component.newline()).append(extra);
            c = new Card(card, forStaff, now + config().getLong("hover.cache-seconds", 5) * 1000);
            if (cards.size() > 1000) cards.clear();
            cards.put(source.getUniqueId(), c);
        }
        return staff ? c.staff : c.card;
    }

    /** Plain config lines with %player%, joined; null when there are none. */
    private static Component lines(List<String> lines, Player source) {
        Component out = null;
        Map<String, Object> ph = Map.of("player", source.getName());
        for (String line : lines) {
            Component c = Text.parse(line, source, ph);
            out = out == null ? c : out.append(Component.newline()).append(c);
        }
        return out;
    }

    /** Lines whose placeholders found no plugin are left out. */
    private Component buildHover(Player source) {
        List<String> lines = new ArrayList<>(config().getStringList("hover.lines"));
        if (config().getBoolean("hover.supporter.enabled", true) && source.hasPermission(config().getString("hover.supporter.permission", "vexcore.chat.supporter"))) {
            lines.addAll(config().getStringList("hover.supporter.lines"));
        }
        Component out = null;
        boolean hide = config().getBoolean("hover.hide-unresolved", true);
        Map<String, Object> ph = Map.of("player", source.getName());
        for (String line : lines) {
            String filled = Text.papi(source, plugin.placeholders().expand(source, Text.fill(line, ph)));
            if (hide && UNRESOLVED.matcher(filled).find()) continue;
            Component c = Text.parse(filled, source, ph);
            out = out == null ? c : out.append(Component.newline()).append(c);
        }
        return out;
    }

    // ── Render tokens ─────────────────────────────────────────────────────

    /** Turns [item], [inv], [ec] in a message into the real thing. Also used by /msg and staff chat. */
    public Component render(Player player, Component message, String raw) {
        ConfigurationSection tokens = config().getConfigurationSection("tokens");
        if (tokens == null) return message;
        for (String kind : List.of("item", "inventory", "enderchest")) {
            ConfigurationSection t = tokens.getConfigurationSection(kind);
            if (t == null || !t.getBoolean("enabled", true)) continue;
            String trigger = t.getString("trigger", "[" + kind + "]");
            String permission = t.getString("permission", "");
            if (!raw.toLowerCase(java.util.Locale.ROOT).contains(trigger.toLowerCase(java.util.Locale.ROOT)) || (!permission.isEmpty() && !player.hasPermission(permission))) continue;
            Component shown = switch (kind) {
                case "item" -> item(player, t);
                case "inventory" -> link(player, t, onOwner(player, () -> inventory(player)), "inventory");
                default -> link(player, t, onOwner(player, () -> ender(player)), "enderchest");
            };
            if (shown == null) continue;
            Pattern p = Pattern.compile("(?i)" + Pattern.quote(trigger));
            message = message.replaceText(b -> b.match(p).replacement(shown).once());
        }
        return message;
    }

    /**
     * Reads the player's items on their own thread. Chat runs on its own thread, where reading
     * an inventory the game is changing can give a torn copy. Null if that took too long.
     */
    private static <T> T onOwner(Player player, java.util.function.Supplier<T> read) {
        if (Bukkit.isOwnedByCurrentRegion(player)) return read.get();
        java.util.concurrent.CompletableFuture<T> result = new java.util.concurrent.CompletableFuture<>();
        Scheduler.entity(player, () -> {
            try {
                result.complete(read.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }, () -> result.complete(null));
        try {
            return result.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private Component item(Player player, ConfigurationSection t) {
        ItemStack hand = onOwner(player, () -> player.getInventory().getItemInMainHand().clone());
        if (hand == null) return null;
        Component name = hand.isEmpty() ? Text.parse(t.getString("empty", "&7Air")) : hand.displayName();
        Map<String, Object> ph = new HashMap<>();
        ph.put("item", name);
        ph.put("player", player.getName());
        Component out = Text.parse(t.getString("display", "&#2BD6FF[%item%&#2BD6FF]"), null, ph);
        if (t.getBoolean("show-amount", true) && hand.getAmount() > 1) {
            out = out.append(Text.parse(t.getString("amount-format", " &7x%amount%"), null, Map.of("amount", hand.getAmount())));
        }
        return out;
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }

    private ItemStack[] inventory(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] items = new ItemStack[54];
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 9; i < 36 && i < storage.length; i++) items[i - 9] = copy(storage[i]); // rows 1-3: main inventory
        for (int i = 0; i < 9 && i < storage.length; i++) items[27 + i] = copy(storage[i]);  // row 4: hotbar
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < 4 && i < armor.length; i++) items[45 + i] = copy(armor[3 - i]); // row 6: helmet..boots
        items[50] = copy(inv.getItemInOffHand());
        return items;
    }

    private ItemStack[] ender(Player player) {
        ItemStack[] chest = player.getEnderChest().getContents();
        ItemStack[] items = new ItemStack[chest.length];
        for (int i = 0; i < chest.length; i++) items[i] = copy(chest[i]);
        return items;
    }

    private Component link(Player player, ConfigurationSection t, ItemStack[] items, String kind) {
        if (items == null) return null;
        String id = Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36);
        synchronized (snapshots) {
            long now = System.currentTimeMillis();
            snapshots.values().removeIf(s -> s.expires < now);
            while (snapshots.size() >= Math.max(1, config().getInt("snapshots.max", 200))) {
                snapshots.remove(snapshots.keySet().iterator().next());
            }
            snapshots.put(id, new Snapshot(config().getString("titles." + kind, "%player%").replace("%player%", com.vexorstudios.vexcore.core.Visibility.name(player)),
                    items, now + config().getLong("snapshots.keep-seconds", 300) * 1000));
        }
        Map<String, Object> ph = Map.of("player", com.vexorstudios.vexcore.core.Visibility.name(player));
        Component hover = null;
        for (String line : t.getStringList("hover")) {
            Component c = Text.parse(line, null, ph);
            hover = hover == null ? c : hover.append(Component.newline()).append(c);
        }
        return interactive(Text.parse(t.getString("display", "[%player%]"), null, ph), hover,
                "/" + plugin.commands().name("chatview") + " " + id);
    }

    private void view(Player player, String id) {
        Snapshot s;
        synchronized (snapshots) {
            s = snapshots.get(id);
        }
        if (s == null || s.expires < System.currentTimeMillis()) {
            msg(player, "expired");
            return;
        }
        open(player, "view", menu -> {
            menu.with("title", s.title);
            for (int i = 0; i < s.items.length && i < menu.file().size(); i++) if (s.items[i] != null) menu.set(i, s.items[i], null);
        });
    }
}
