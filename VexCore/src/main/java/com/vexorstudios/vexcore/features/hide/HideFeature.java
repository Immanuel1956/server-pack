package com.vexorstudios.vexcore.features.hide;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.features.vanish.VanishFeature;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /hide, ported from LifestealCore: whoever runs it gets one shared name and skin in everybody
 * else's tab list, above their head and in chat. The name and skin are swapped in the packets
 * other players receive, so it needs the PacketEvents plugin; without it /hide only says it is
 * disabled because of the missing dependency.
 */
public final class HideFeature extends Feature implements Listener {

    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private volatile String skinValue = "";
    private volatile String skinSignature = "";
    private Object bridge;

    @Override
    protected void enable() {
        command("hide", (sender, label, args) -> {
            if (!hook()) {
                msg(sender, "missing-dependency");
                return;
            }
            Player p = player(sender);
            if (p == null) return;
            if (hidden.remove(p.getUniqueId())) {
                refresh(p);
                p.playerListName(null);
                msg(p, "shown");
            } else {
                hidden.add(p.getUniqueId());
                refresh(p);
                p.playerListName(Text.parse(config().getString("name", "&7&k12345678&f")));
                msg(p, "hidden");
            }
            // The name tag comes down while hidden (and back up after).
            if (plugin.features().get("nametags") instanceof com.vexorstudios.vexcore.features.nametags.NametagsFeature tags) tags.rebuild(p);
        });
        // VexCore starts before most plugins: PacketEvents may simply not be running yet. Only a
        // server without it at all is told now; otherwise /hide hooks in on first use.
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            problems().add(com.vexorstudios.vexcore.core.Files.configPath(id()) + ": /hide is disabled due to a missing dependency (install PacketEvents)");
            plugin.getLogger().warning("/hide is disabled due to a missing dependency: PacketEvents is not installed.");
            return;
        }
        hook();
        hidden.addAll(CARRIED);
        CARRIED.clear();
    }

    /** Hooks into PacketEvents if it is running; false when it isn't (installed or not). */
    private synchronized boolean hook() {
        if (bridge != null) return true;
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) return false;
        listen(this);
        loadSkin();
        bridge = PacketBridge.register(this);
        return true;
    }

    /** Hidden players during /vexcore reload: they stay hidden (nobody sees their real name meanwhile). */
    private static final Set<UUID> CARRIED = ConcurrentHashMap.newKeySet();

    @Override
    protected void disable() {
        if (bridge != null) PacketBridge.unregister(bridge);
        bridge = null;
        if (plugin.isEnabled()) {
            CARRIED.addAll(hidden);
            hidden.clear();
            return;
        }
        for (UUID id : hidden) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                hidden.remove(id);
                onPlayerThread(p, () -> p.playerListName(null));
                refresh(p);
            }
        }
        hidden.clear();
    }

    public boolean isHidden(UUID player) {
        return hidden.contains(player);
    }

    /** The name others see: the shared one while hidden. Plain text, for chat. */
    public String shownName(Player player) {
        // With its colour codes: "&k" keeps the name scrambled in chat, as in the tab list.
        return hidden.contains(player.getUniqueId()) ? config().getString("name", "&7&k12345678&f") : player.getName();
    }

    /** The name put in the packets: legacy § codes, at most 16 characters (the game's limit). */
    String profileName() {
        String name = config().getString("name", "&7&k12345678&f").replace('&', '§');
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    String skinValue() {
        return skinValue;
    }

    String skinSignature() {
        return skinSignature;
    }

    boolean hides(UUID target, UUID viewer) {
        return !target.equals(viewer) && hidden.contains(target);
    }

    /** A value and signature from the config, or the skin of a player name, fetched from Mojang in the background. */
    private void loadSkin() {
        String value = config().getString("skin.value", "");
        if (!value.isEmpty()) {
            skinValue = value;
            skinSignature = config().getString("skin.signature", "");
            return;
        }
        String owner = config().getString("skin.player", "");
        if (owner.isEmpty()) return; // the vanilla default skin
        Scheduler.async(() -> {
            try {
                PlayerProfile profile = Bukkit.createProfile(owner);
                if (!profile.complete(true)) return;
                for (ProfileProperty property : profile.getProperties()) {
                    if (property.getName().equals("textures")) {
                        skinValue = property.getValue();
                        skinSignature = property.getSignature() == null ? "" : property.getSignature();
                    }
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not fetch the /hide skin of " + owner + ": " + e.getMessage());
            }
        });
    }

    /** Sends the player out again to everyone who may see them, so the new name and skin show. */
    private void refresh(Player target) {
        boolean vanished = plugin.features().get("vanish") instanceof VanishFeature v && v.isVanished(target.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target) || !viewer.canSee(target)) continue;
            if (vanished && !viewer.hasPermission("vexcore.vanish.see")) continue;
            onPlayerThread(viewer, () -> {
                viewer.hidePlayer(plugin, target);
                viewer.showPlayer(plugin, target);
            });
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR) // after the leave message, which still needs the shared name
    public void onQuit(PlayerQuitEvent event) {
        hidden.remove(event.getPlayer().getUniqueId());
    }

    /** Only loaded when PacketEvents is installed. Swaps name and skin in player info packets. */
    static final class PacketBridge implements com.github.retrooper.packetevents.event.PacketListener {
        private final HideFeature feature;

        private PacketBridge(HideFeature feature) {
            this.feature = feature;
        }

        static Object register(HideFeature feature) {
            return com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                    .registerListener(new PacketBridge(feature), com.github.retrooper.packetevents.event.PacketListenerPriority.NORMAL);
        }

        static void unregister(Object listener) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                    .unregisterListener((com.github.retrooper.packetevents.event.PacketListenerCommon) listener);
        }

        @Override
        public void onPacketSend(com.github.retrooper.packetevents.event.PacketSendEvent event) {
            if (event.getPacketType() != com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.PLAYER_INFO_UPDATE) return;
            if (feature.hidden.isEmpty() || event.getUser() == null || event.getUser().getUUID() == null) return;
            UUID viewer = event.getUser().getUUID();
            var packet = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate(event);
            boolean changed = false;
            for (var entry : packet.getEntries()) {
                var profile = entry.getGameProfile();
                if (profile == null || !feature.hides(entry.getProfileId(), viewer)) continue;
                profile.setName(feature.profileName());
                String value = feature.skinValue();
                profile.setTextureProperties(value.isEmpty() ? List.of() : List.of(
                        new com.github.retrooper.packetevents.protocol.player.TextureProperty("textures", value,
                                feature.skinSignature().isEmpty() ? null : feature.skinSignature())));
                changed = true;
            }
            if (changed) event.markForReEncode(true);
        }
    }
}
