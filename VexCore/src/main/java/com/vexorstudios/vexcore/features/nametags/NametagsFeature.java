package com.vexorstudios.vexcore.features.nametags;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.hide.HideFeature;
import com.vexorstudios.vexcore.features.vanish.VanishFeature;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Name tags ported from LifestealCore: every player gets text lines floating above their head
 * (text displays), each line cycling through its frames, with every VexCore and PlaceholderAPI
 * placeholder. Only text that really changed is sent. The vanilla name tag is hidden (Paper).
 *
 * <p>With PacketEvents installed the lines are rooted to the head, as in LifestealCore: every
 * player's game (the owner's too) is told the lines ride the player, and the server's own moves
 * of the lines are dropped, so they move with the head exactly (no lag, no wobble). On the
 * server they only follow the player: Paper refuses to teleport a player who really carries
 * passengers to another world, so real riding would break /spawn, /rtp and other plugins'
 * teleports. Without PacketEvents the lines follow the player every tick. Tags hide while sneaking, while invisible, in spectator mode, for vanished and /hide players,
 * from viewers who can't see the player, and (hide-own) from the player themselves.
 */
public final class NametagsFeature extends Feature implements Listener {

    private static final String TEAM = "vx_nametags";

    record Line(String key, int interval, int refresh, List<String> frames) {
    }

    /** One player's tag: a display per line, the text each shows now, where it was last put. */
    static final class Tag {
        final List<TextDisplay> displays = new ArrayList<>();
        final List<Component> shown = new ArrayList<>();
        long[] updatedAt = new long[0];
        final Set<UUID> hiddenFrom = ConcurrentHashMap.newKeySet();
        Location last;
        Location scratch; // reused every tick for the player's position
        int owner; // the player's entity id
        boolean hidden;
        Scheduler.Task task = Scheduler.NOOP;
    }

    private final Map<UUID, Tag> tags = new ConcurrentHashMap<>();

    // For the packet side (read on network threads): player entity id -> its line entity ids,
    // line entity id -> player entity id, and the passengers the server itself gave the player.
    final Map<Integer, int[]> byOwner = new ConcurrentHashMap<>();
    final Map<Integer, Integer> ownerOf = new ConcurrentHashMap<>();
    final Map<Integer, int[]> real = new ConcurrentHashMap<>();
    private Object mount; // the PacketEvents listener, when PacketEvents is installed
    private List<Line> lines = List.of();
    // Read once per enable: follow() runs every tick for every moving player.
    private double yOffset, lineGap;
    private boolean hideSneaking, hideInvisible, hideOwn;
    private java.util.Set<String> disabledWorlds = java.util.Set.of();
    private long lastViewers;

    /** A tick clock without a task: 50 ms steps of real time (the same as ticks at 20 TPS). */
    private static long ticks() {
        return System.currentTimeMillis() / 50;
    }

    @Override
    protected void enable() {
        List<Line> list = new ArrayList<>();
        ConfigurationSection section = config().getConfigurationSection("lines");
        int refresh = Math.max(1, config().getInt("refresh", 10));
        if (section != null) for (String key : section.getKeys(false)) {
            ConfigurationSection l = section.getConfigurationSection(key);
            if (l == null || !l.getBoolean("enabled", true) || l.getStringList("frames").isEmpty()) continue;
            int lineRefresh = Math.max(1, l.getInt("refresh", refresh));
            int interval = l.getInt("interval", 0);
            // interval rounds up to a multiple of refresh; 0 never turns over.
            if (interval > 0) interval = ((interval + lineRefresh - 1) / lineRefresh) * lineRefresh;
            list.add(new Line(key, interval, lineRefresh, l.getStringList("frames")));
        }
        lines = List.copyOf(list);
        if (Scheduler.FOLIA) problems().add("features/nametags: Folia has no scoreboards, so the vanilla name tag stays visible under the lines");
        listen(this);
        command("nametags", (sender, label, args) -> {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                int count = 0;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    onPlayerThread(p, () -> rebuild(p)); // each on their own thread (Folia)
                    count++;
                }
                msg(sender, "reloaded", "count", count);
                return;
            }
            msg(sender, "info", "lines", lines.size(), "offset", config().getDouble("display.y-offset", 0.5),
                    "gap", config().getDouble("display.line-gap", 0.25), "refresh", refresh);
        });
        String bg = config().getString("display.background", "#00000000");
        try {
            background = Color.fromARGB((int) Long.parseLong(bg.replace("#", ""), 16));
        } catch (RuntimeException bad) {
            background = Color.fromARGB(0);
            problems().add("features/nametags: display.background '" + bg + "' is not a colour like #AARRGGBB; using transparent");
        }
        int light = Math.max(0, Math.min(15, config().getInt("display.brightness", 15)));
        brightness = new Display.Brightness(light, light);
        viewRange = (float) (config().getDouble("display.view-distance", 48) / 64.0);
        lineWidth = config().getInt("display.line-width", 1000);
        opacity = (byte) Math.max(0, Math.min(255, config().getInt("display.text-opacity", 255)));
        seeThrough = config().getBoolean("display.see-through", false);
        shadow = config().getBoolean("display.shadow", true);
        teleportDuration = Math.max(0, Math.min(59, config().getInt("display.teleport-duration", 1)));
        scale = (float) config().getDouble("display.scale", 1.0);
        yOffset = config().getDouble("display.y-offset", 0.5);
        lineGap = config().getDouble("display.line-gap", 0.25);
        hideSneaking = config().getBoolean("display.hide-when-sneaking", true);
        hideInvisible = config().getBoolean("display.hide-when-invisible", true);
        hideOwn = config().getBoolean("display.hide-own", false);
        disabledWorlds = java.util.Set.copyOf(config().getStringList("disabled-worlds"));
        if (!hookPackets()) {
            problems().add("features/nametags: install PacketEvents to fix the lines to the head; without it they follow the player");
        }
        every(refresh, this::refreshAll);
        for (Player p : Bukkit.getOnlinePlayers()) onPlayerThread(p, () -> rebuild(p));
    }

    /**
     * Hooks into PacketEvents once it is running. Tried again on every refresh: VexCore starts
     * before most plugins, so PacketEvents is often not up yet when this feature starts.
     */
    private boolean hookPackets() {
        if (mount != null) return true;
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) return false;
        try {
            mount = Mount.register(this);
            // Tags put up before now were sent without the mount: send them again.
            for (Player p : Bukkit.getOnlinePlayers()) if (tags.containsKey(p.getUniqueId())) onPlayerThread(p, () -> rebuild(p));
            return true;
        } catch (Throwable error) {
            plugin.getLogger().warning("Name tags: could not hook into PacketEvents (" + error + "); lines follow players instead");
            packetsFailed = true;
            return false;
        }
    }

    private boolean packetsFailed;

    @Override
    protected void disable() {
        if (mount != null) Mount.unregister(mount);
        mount = null;
        for (UUID id : new ArrayList<>(tags.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) onPlayerThread(p, () -> remove(p));
            else removeTag(tags.remove(id));
        }
        if (!Scheduler.FOLIA) for (Player p : Bukkit.getOnlinePlayers()) {
            Team team = p.getScoreboard().getTeam(TEAM);
            if (team != null) team.unregister();
        }
    }

    // ── Building ──────────────────────────────────────────────────────────

    /** Whether a player shows a tag at all right now. */
    private boolean wanted(Player p) {
        if (!p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (disabledWorlds.contains(p.getWorld().getName())) return false;
        if (plugin.features().get("vanish") instanceof VanishFeature v && v.isVanished(p.getUniqueId())) return false;
        return !(plugin.features().get("hide") instanceof HideFeature h && h.isHidden(p.getUniqueId()));
    }

    /** Player's thread: removes the old lines and puts up new ones. */
    public void rebuild(Player p) {
        // A rebuild queued before a reload must not put up a second tag from the old feature.
        if (!isEnabled()) return;
        remove(p);
        if (!wanted(p) || lines.isEmpty()) return;
        Tag tag = new Tag();
        Location base = p.getLocation();
        Location at = head(p, base);
        int[] ids = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            int line = i;
            TextDisplay d = p.getWorld().spawn(at, TextDisplay.class, e -> style(e, line));
            if (hideOwn) p.hideEntity(plugin, d);
            tag.displays.add(d);
            tag.shown.add(null);
            ids[i] = d.getEntityId();
        }
        tag.updatedAt = new long[lines.size()];
        java.util.Arrays.fill(tag.updatedAt, Long.MIN_VALUE / 2);
        tag.last = base;
        tag.owner = p.getEntityId();
        // Known to the packet side before the lines are sent to anyone (that happens next tick).
        byOwner.put(tag.owner, ids);
        for (int id : ids) ownerOf.put(id, tag.owner);
        tags.put(p.getUniqueId(), tag);
        tagVersion++;
        update(p, tag, true);
        if (!Scheduler.FOLIA) {
            tag.hiddenFrom.clear();
            viewers(p, tag); // new lines: hidden right away from anyone who can't see the player
        }
        tag.task = Scheduler.entityTimer(p, () -> follow(p, tag), 1, 1);
    }

    /** Style of the text display for line {@code i}; the line's height is its offset above the head. */
    // Display style, read once at enable (every rebuild spawns a display per line).
    private Color background;
    private Display.Brightness brightness;
    private float viewRange, scale;
    private int lineWidth, teleportDuration;
    private byte opacity;
    private boolean seeThrough, shadow;

    private void style(TextDisplay d, int i) {
        d.setPersistent(false);
        d.setBillboard(Display.Billboard.CENTER);
        d.setDefaultBackground(false);
        d.setBackgroundColor(background);
        d.setBrightness(brightness);
        d.setViewRange(viewRange);
        d.setLineWidth(lineWidth);
        d.setTextOpacity(opacity);
        d.setSeeThrough(seeThrough);
        d.setShadowed(shadow);
        d.setTeleportDuration(teleportDuration); // smooth following
        // Every line sits on top of the head; the offset moves each one up (the top line highest).
        // Riding lines all sit at the same point too, so it looks the same either way.
        float up = (float) (yOffset + (lines.size() - 1 - i) * lineGap);
        d.setTransformation(new Transformation(new Vector3f(0, up, 0), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
    }

    /** The top of the head (lower while sneaking), where riders sit. */
    private static Location head(Player p, Location base) {
        return base.clone().add(0, p.getHeight(), 0);
    }

    private void remove(Player p) {
        Tag tag = tags.remove(p.getUniqueId());
        if (tag != null) tagVersion++;
        removeTag(tag);
    }

    private void removeTag(Tag tag) {
        if (tag == null) return;
        tag.task.cancel();
        int[] ids = byOwner.get(tag.owner);
        if (ids != null && tag.displays.size() == ids.length && tag.displays.get(0).getEntityId() == ids[0]) {
            byOwner.remove(tag.owner);
            real.remove(tag.owner);
        }
        for (TextDisplay d : tag.displays) ownerOf.remove(d.getEntityId());
        for (TextDisplay d : tag.displays) {
            if (!d.isValid()) continue;
            // After a teleport the old lines may sit in another region (Folia): removed there.
            if (!Scheduler.FOLIA || Bukkit.isOwnedByCurrentRegion(d)) d.remove();
            else d.getScheduler().run(plugin, t -> d.remove(), null);
        }
    }

    // ── Moving and updating ───────────────────────────────────────────────

    /** Every tick on the player's thread: follow them; start over after a world change or a long jump. */
    private void follow(Player p, Tag tag) {
        Location last = tag.last;
        Location now = tag.scratch == null ? p.getLocation() : p.getLocation(tag.scratch);
        if (last != null && now.getWorld() == last.getWorld() && now.getX() == last.getX() && now.getY() == last.getY() && now.getZ() == last.getZ()) {
            tag.scratch = now; // didn't move: nothing to send (most ticks)
            return;
        }
        boolean broken = false;
        for (TextDisplay d : tag.displays) if (!d.isValid()) {
            broken = true;
            break;
        }
        if (last == null || now.getWorld() != last.getWorld() || now.distanceSquared(last) > 64 || broken) {
            rebuild(p);
            return;
        }
        tag.scratch = last; // the two locations take turns: no new object per tick
        tag.last = now;
        // Riding lines are placed by each player's game; this keeps the server's copy (what the
        // owner sees in third person, and what decides who is sent the lines) on the head too.
        double x = now.getX(), y = now.getY() + p.getHeight(), z = now.getZ();
        for (TextDisplay d : tag.displays) {
            if (Scheduler.FOLIA) {
                d.teleportAsync(new Location(now.getWorld(), x, y, z));
            } else {
                // Paper: a plain teleport of an entity near the player is immediate and makes no
                // future or chunk request (teleportAsync does, every tick, for every line).
                last.set(x, y, z);
                d.teleport(last);
            }
        }
    }

    private void refreshAll() {
        if (mount == null && !packetsFailed) hookPackets();
        // Who-sees-whose-tag is every pair of players: once a second is plenty (Paper only).
        long now = ticks();
        boolean viewersDue = !Scheduler.FOLIA && now - lastViewers >= 20;
        if (viewersDue) lastViewers = now;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tag tag = tags.get(p.getUniqueId());
            Scheduler.entity(p, () -> {
                boolean wanted = wanted(p);
                if (tag == null) {
                    if (wanted) rebuild(p);
                    return;
                }
                if (!wanted) {
                    remove(p);
                    return;
                }
                if (!tag.displays.get(0).isValid()) { // killed (/kill, a clear-lag plugin): put it back
                    rebuild(p);
                    return;
                }
                update(p, tag, false);
            });
            if (tag != null && viewersDue) viewers(p, tag);
        }
        if (!Scheduler.FOLIA) hideVanilla(viewersDue);
    }

    /** Player's thread: sets each line's text, but only when it changed. */
    private void update(Player p, Tag tag, boolean all) {
        boolean hide = (hideSneaking && p.isSneaking())
                || (hideInvisible && (p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY)));
        long ticks = ticks();
        for (int i = 0; i < lines.size() && i < tag.displays.size(); i++) {
            Line line = lines.get(i);
            // Each line is looked at every line.refresh ticks (sooner when it has to hide or show).
            if (!all && tag.hidden == hide && ticks - tag.updatedAt[i] < line.refresh) continue;
            tag.updatedAt[i] = ticks;
            int frame = line.interval <= 0 ? 0 : (int) ((ticks / line.interval) % line.frames.size());
            Component text = hide ? Component.empty() : render(p, line.frames.get(frame));
            if (!Objects.equals(text, tag.shown.get(i))) {
                tag.displays.get(i).text(text);
                tag.shown.set(i, text);
            }
        }
        tag.hidden = hide;
    }

    /** Placeholders filled in; any a plugin didn't answer are dropped instead of shown raw. */
    private Component render(Player p, String frame) {
        String filled = Text.papi(p, plugin.placeholders().expand(p, frame.replace("%player%", p.getName())));
        return Text.parse(UNRESOLVED.matcher(filled).replaceAll(""));
    }

    private static final java.util.regex.Pattern UNRESOLVED = java.util.regex.Pattern.compile("%[A-Za-z0-9_]+%");

    /** A tag is only seen by players who can see its owner (vanish, /playerhide). Paper only. */
    private void viewers(Player owner, Tag tag) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(owner)) continue;
            boolean see = viewer.canSee(owner);
            boolean hidden = tag.hiddenFrom.contains(viewer.getUniqueId());
            if (see == !hidden) continue;
            for (TextDisplay d : tag.displays) {
                if (see) viewer.showEntity(plugin, d);
                else viewer.hideEntity(plugin, d);
            }
            if (see) tag.hiddenFrom.remove(viewer.getUniqueId());
            else tag.hiddenFrom.add(viewer.getUniqueId());
        }
    }

    /** Bumped whenever a tag comes or goes; boards whose team matches it are skipped. */
    private volatile long tagVersion;
    private final Map<Scoreboard, Long> teamVersion = new java.util.WeakHashMap<>(); // main thread (Paper only)

    /** The vanilla name tag is hidden with a team, on every scoreboard in use. Paper only. */
    private void hideVanilla(boolean full) {
        Set<Scoreboard> done = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Player> online = null;
        long version = tagVersion;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            if (!done.add(board)) continue;
            // Only boards that are new, or that were set up before a tag came or went.
            // Once a second every board is checked in full: another plugin (TAB, a prefix plugin,
            // /team) can move a player into its own team, which takes them out of this one.
            Long seen = teamVersion.get(board);
            if (!full && seen != null && seen == version) continue;
            teamVersion.put(board, version);
            if (online == null) online = new ArrayList<>(Bukkit.getOnlinePlayers());
            Team team = board.getTeam(TEAM);
            if (team == null) {
                team = board.registerNewTeam(TEAM);
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            for (Player p : online) {
                boolean in = team.hasEntry(p.getName());
                boolean should = tags.containsKey(p.getUniqueId());
                if (should && !in) team.addEntry(p.getName());
                else if (!should && in) team.removeEntry(p.getName());
            }
        }
    }

    // ── Events ────────────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Scheduler.entityLater(p, () -> rebuild(p), Math.max(1, config().getLong("join-delay-ticks", 20)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId();
        for (Tag t : tags.values()) t.hiddenFrom.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        remove(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Scheduler.entityLater(p, () -> rebuild(p), 2);
    }

    /** The lines every rider list of this player should hold: the server's own riders plus the lines. */
    int[] passengers(int vehicle) {
        int[] lines = byOwner.get(vehicle);
        int[] own = real.get(vehicle);
        if (lines == null) return own == null ? new int[0] : own;
        if (own == null || own.length == 0) return lines;
        int[] all = java.util.Arrays.copyOf(own, own.length + lines.length);
        System.arraycopy(lines, 0, all, own.length, lines.length);
        return all;
    }

    /**
     * Only loaded when PacketEvents is installed. Tells each player's game that the lines ride
     * their owner: when a line or its owner is sent to a player, and whenever the server sends the
     * owner's rider list; the server's moves of the lines are dropped.
     */
    static final class Mount implements com.github.retrooper.packetevents.event.PacketListener {
        private final NametagsFeature feature;

        private Mount(NametagsFeature feature) {
            this.feature = feature;
        }

        static Object register(NametagsFeature feature) {
            return com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                    .registerListener(new Mount(feature), com.github.retrooper.packetevents.event.PacketListenerPriority.NORMAL);
        }

        static void unregister(Object listener) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                    .unregisterListener((com.github.retrooper.packetevents.event.PacketListenerCommon) listener);
        }

        @Override
        public void onPacketSend(com.github.retrooper.packetevents.event.PacketSendEvent event) {
            var type = event.getPacketType();
            // The server moves its copy of the lines too; on a game where they ride the head those
            // moves would pull them off it (the wobble and trailing). Dropped, as LifestealCore does.
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_TELEPORT) {
                if (!feature.ownerOf.isEmpty() && feature.ownerOf.containsKey(new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport(event).getEntityId())) event.setCancelled(true);
            } else if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                if (!feature.ownerOf.isEmpty() && feature.ownerOf.containsKey(new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync(event).getId())) event.setCancelled(true);
            } else if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
                if (!feature.ownerOf.isEmpty() && feature.ownerOf.containsKey(new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove(event).getEntityId())) event.setCancelled(true);
            } else if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                if (!feature.ownerOf.isEmpty() && feature.ownerOf.containsKey(new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId())) event.setCancelled(true);
            } else if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SPAWN_ENTITY) {
                if (feature.byOwner.isEmpty() || event.getUser() == null) return;
                int id = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity(event).getEntityId();
                Integer owner = feature.ownerOf.get(id);
                int vehicle = owner != null ? owner : feature.byOwner.containsKey(id) ? id : -1;
                var user = event.getUser();
                if (vehicle < 0) return; // the owner's own game too: their tag rides their head in F5
                // Whichever of the two arrives second makes the mount stick (the game ignores
                // riders it doesn't know yet).
                event.getTasksAfterSend().add(() -> {
                    if (!feature.byOwner.containsKey(vehicle)) return;
                    user.sendPacketSilently(new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers(
                            vehicle, feature.passengers(vehicle)));
                });
            } else if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SET_PASSENGERS) {
                if (feature.byOwner.isEmpty() || event.getUser() == null) return;
                var packet = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers(event);
                int vehicle = packet.getEntityId();
                if (!feature.byOwner.containsKey(vehicle)) return;
                // The server's own riders of a tagged player (another plugin's): keep them, add the lines.
                feature.real.put(vehicle, packet.getPassengers().clone());
                packet.setPassengers(feature.passengers(vehicle));
                event.markForReEncode(true);
            }
        }
    }
}
