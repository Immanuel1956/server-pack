package com.vexorstudios.vexcore.features.milestones;

import com.vexorstudios.vexcore.core.Time;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * /playtime: rewards for time played. Uses the server's own play-time statistic, so time played
 * before VexCore was installed counts too.
 */
public final class PlaytimeFeature extends MilestoneFeature {

    @Override
    protected long progress(Player player) {
        return player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
    }

    @Override
    protected long required(ConfigurationSection reward) {
        return Time.seconds(reward.getString("playtime", ""));
    }

    @Override
    protected String show(long seconds) {
        return Time.format(seconds, config().getConfigurationSection("time-format"));
    }

    @Override
    protected void enable() {
        super.enable();
        placeholder("playtime", (p, a) -> p.getPlayer() == null ? "0" : show(progress(p.getPlayer())));
    }
}
