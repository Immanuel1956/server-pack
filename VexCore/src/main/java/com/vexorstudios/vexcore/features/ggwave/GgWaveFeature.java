package com.vexorstudios.vexcore.features.ggwave;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.Messages;
import com.vexorstudios.vexcore.core.Numbers;
import com.vexorstudios.vexcore.core.Scheduler;
import com.vexorstudios.vexcore.core.Text;
import com.vexorstudios.vexcore.core.Time;
import com.vexorstudios.vexcore.gui.Actions;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * /ggwave [time]: for a while every "gg" in chat turns into a coloured GG, and each player's
 * first one pays {@code money} and runs the reward commands. Other features (a store purchase)
 * start a wave with {@link #start}.
 */
public final class GgWaveFeature extends Feature implements Listener {

    private volatile long until;
    private volatile double money;
    private volatile List<String> extra = List.of();
    private final Set<UUID> rewarded = ConcurrentHashMap.newKeySet();
    private final Set<String> rewardedNetworks = ConcurrentHashMap.newKeySet();
    /** Accounts actually paid this wave (linked alts of these get nothing, see IP protection). */
    private final Set<UUID> paid = ConcurrentHashMap.newKeySet();
    private Scheduler.Task ending = Scheduler.NOOP;
    private Pattern gg;

    @Override
    protected void enable() {
        gg = Pattern.compile(config().getString("match", "(?i)^\\s*g+\\s*g+\\s*!*\\s*$"));
        listen(this);
        command("ggwave", (sender, label, args) -> {
            long seconds = args.length > 0 ? Time.seconds(args[0]) : Time.seconds(config().getString("duration", "30s"));
            if (seconds <= 0) {
                msg(sender, "usage");
                return;
            }
            if (running()) { // one wave at a time: a second would reset who got paid
                msg(sender, "already-running");
                return;
            }
            start(sender.getName(), seconds, Math.max(0, config().getDouble("money", 0)), List.of(), "started");
        });
    }

    @Override
    protected synchronized void disable() { // with start(): never cancel one wave's end and keep the next
        ending.cancel();
        until = 0;
    }

    public boolean running() {
        return System.currentTimeMillis() < until;
    }

    /**
     * Starts a wave, replacing one that is running (everyone may gg again). {@code money} is paid
     * to each player's first gg on top of the configured rewards and {@code extraRewards}.
     * {@code messageKey} is broadcast when not null.
     */
    public synchronized void start(String starter, long seconds, double money, List<String> extraRewards, String messageKey) {
        if (seconds <= 0) return;
        ending.cancel();
        this.money = Math.max(0, money);
        this.extra = extraRewards == null ? List.of() : List.copyOf(extraRewards);
        rewarded.clear();
        rewardedNetworks.clear();
        paid.clear();
        until = System.currentTimeMillis() + seconds * 1000;
        long myEnd = until;
        if (messageKey != null) broadcast(Messages.everyone(), messageKey, placeholders(starter, seconds));
        ending = Scheduler.globalLater(() -> {
            if (until == myEnd) broadcast(Messages.everyone(), "ended", Map.of("count", rewarded.size()));
        }, seconds * 20);
    }

    private Map<String, Object> placeholders(String starter, long seconds) {
        return Map.of("time", plugin.messages().time(seconds), "player", starter,
                "money", plugin.money().format(money), "money_short", plugin.money().shortFormat(money),
                "money_raw", Numbers.full(money, 2, ""));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!running() || !gg.matcher(com.vexorstudios.vexcore.core.StyledText.plain(Text.plain(event.message()))).matches()) return;
        List<String> styles = config().getStringList("styles");
        if (!styles.isEmpty()) event.message(Text.parse(styles.get(ThreadLocalRandom.current().nextInt(styles.size()))));
        Player player = event.getPlayer();
        if (!rewarded.add(player.getUniqueId())) return;
        String network = com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.network(player, "ggwave");
        synchronized (paid) { // chat is async: two linked accounts typing gg at once
            if ((network != null && !rewardedNetworks.add(network)) // an alt of someone already paid
                    || com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.linked(player, "ggwave", paid)
                    == com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.Result.TAKEN) {
                com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature.notEarning(player, "ggwave");
                return;
            }
            paid.add(player.getUniqueId());
        }
        double pay = money;
        List<String> commands = new ArrayList<>(config().getStringList("rewards"));
        commands.addAll(extra);
        Scheduler.entity(player, () -> {
            if (pay > 0 && plugin.money().deposit(player, pay)) {
                msg(player, "rewarded", "money", plugin.money().format(pay), "money_short", plugin.money().shortFormat(pay));
            }
            Actions.run(player, commands, Map.of("player", player.getName()), null);
        });
    }
}
