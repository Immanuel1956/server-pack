package com.vexorstudios.vexcore.features.milestones;

import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * /killrewards: rewards for player kills. Uses the server's own kill statistic, so kills from
 * before VexCore was installed count too.
 */
public final class KillRewardsFeature extends MilestoneFeature {

    @Override
    protected long progress(Player player) {
        return player.getStatistic(Statistic.PLAYER_KILLS);
    }

    @Override
    protected long required(ConfigurationSection reward) {
        int kills = reward.getInt("kills", 0);
        return kills >= 1 ? kills : -1;
    }

    @Override
    protected String show(long value) {
        return String.valueOf(value);
    }

    @Override
    protected void enable() {
        super.enable();
        placeholder("kills", (p, a) -> p.getPlayer() == null ? "0" : String.valueOf(progress(p.getPlayer())));
    }
}
