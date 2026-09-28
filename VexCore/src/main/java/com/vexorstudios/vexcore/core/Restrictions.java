package com.vexorstudios.vexcore.core;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Things that stop a player from teleporting right now (combat tag, a duel, screenshare...).
 * Features add a rule; every teleport checks all of them before it starts and on every
 * countdown tick.
 */
public final class Restrictions {

    public record Rule(Feature owner, Predicate<Player> blocks, String message) {
    }

    private final List<Rule> rules = new CopyOnWriteArrayList<>();

    public void add(Feature owner, Predicate<Player> blocks, String messageKey) {
        rules.add(new Rule(owner, blocks, messageKey));
    }

    public void clear(Feature owner) {
        rules.removeIf(r -> r.owner == owner);
    }

    /** The first rule that blocks this player, or null. */
    public Rule check(Player player) {
        for (Rule rule : rules) {
            try {
                if (rule.blocks.test(player)) return rule;
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    /** The first rule of another feature than {@code except} that blocks this player, or null. */
    public Rule checkOthers(Player player, Feature except) {
        for (Rule rule : rules) {
            if (rule.owner == except) continue;
            try {
                if (rule.blocks.test(player)) return rule;
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    /** Tells the player why they are blocked. True if they are. */
    public boolean deny(Player player) {
        Rule rule = check(player);
        if (rule == null) return false;
        rule.owner.msg(player, rule.message);
        return true;
    }
}
