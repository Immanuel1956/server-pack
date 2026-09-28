package com.vexorstudios.vexcore.features.announce;

import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.SoundSpec;
import com.vexorstudios.vexcore.core.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;

/** /announce &lt;message&gt;: a title, chat lines and a sound for everyone. */
public final class AnnounceFeature extends Feature {

    @Override
    protected void enable() {
        command("announce", (sender, label, args) -> {
            if (args.length == 0) {
                msg(sender, "usage");
                return;
            }
            Map<String, Object> ph = Map.of("message", String.join(" ", args), "player", sender.getName());
            Title.Times times = Title.Times.times(ticks("fade-in", 10), ticks("stay", 70), ticks("fade-out", 20));
            SoundSpec sound = SoundSpec.of(config().get("sound"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(Title.title(Text.parse(config().getString("title", ""), p, ph), Text.parse(config().getString("subtitle", "%message%"), p, ph), times));
                if (config().getBoolean("chat.enabled", true)) plugin.messages().deliver(p, config().getStringList("chat.lines"), ph, "");
                if (sound != null) sound.play(p);
            }
            if (config().getBoolean("chat.enabled", true)) plugin.messages().deliver(Bukkit.getConsoleSender(), config().getStringList("chat.lines"), ph, "");
        });
    }

    private Duration ticks(String key, int fallback) {
        return Duration.ofMillis(config().getInt(key, fallback) * 50L);
    }
}
