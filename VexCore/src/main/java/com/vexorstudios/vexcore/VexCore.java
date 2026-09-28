package com.vexorstudios.vexcore;

import com.vexorstudios.vexcore.core.ChatInput;
import com.vexorstudios.vexcore.core.Commands;
import com.vexorstudios.vexcore.core.CoreCommand;
import com.vexorstudios.vexcore.core.Database;
import com.vexorstudios.vexcore.core.FeatureManager;
import com.vexorstudios.vexcore.core.Files;
import com.vexorstudios.vexcore.core.Messages;
import com.vexorstudios.vexcore.core.Money;
import com.vexorstudios.vexcore.core.PlayerData;
import com.vexorstudios.vexcore.core.Placeholders;
import com.vexorstudios.vexcore.core.Restrictions;
import com.vexorstudios.vexcore.core.Teleports;
import com.vexorstudios.vexcore.core.Toggles;
import com.vexorstudios.vexcore.features.boosts.BoostsFeature;
import com.vexorstudios.vexcore.features.coinflip.CoinflipFeature;
import com.vexorstudios.vexcore.features.daily.DailyFeature;
import com.vexorstudios.vexcore.features.economy.EconomyFeature;
import com.vexorstudios.vexcore.features.invest.InvestFeature;
import com.vexorstudios.vexcore.features.keyall.KeyallFeature;
import com.vexorstudios.vexcore.features.kits.KitsFeature;
import com.vexorstudios.vexcore.features.milestones.KillRewardsFeature;
import com.vexorstudios.vexcore.features.milestones.PlaytimeFeature;
import com.vexorstudios.vexcore.features.prestige.PrestigeFeature;
import com.vexorstudios.vexcore.features.sell.SellFeature;
import com.vexorstudios.vexcore.features.afk.AfkFeature;
import com.vexorstudios.vexcore.features.combat.CombatFeature;
import com.vexorstudios.vexcore.features.deathmessages.DeathMessagesFeature;
import com.vexorstudios.vexcore.features.dropfix.DropFixFeature;
import com.vexorstudios.vexcore.features.home.HomeFeature;
import com.vexorstudios.vexcore.features.joinmessages.JoinMessagesFeature;
import com.vexorstudios.vexcore.features.links.LinkFeature;
import com.vexorstudios.vexcore.features.live.LiveFeature;
import com.vexorstudios.vexcore.features.menus.MenuFeature;
import com.vexorstudios.vexcore.features.mobtoggle.MobToggleFeature;
import com.vexorstudios.vexcore.features.nightvision.NightVisionFeature;
import com.vexorstudios.vexcore.features.ping.PingFeature;
import com.vexorstudios.vexcore.features.playerhide.PlayerHideFeature;
import com.vexorstudios.vexcore.features.settings.SettingsFeature;
import com.vexorstudios.vexcore.features.sign.SignFeature;
import com.vexorstudios.vexcore.features.spawn.SpawnFeature;
import com.vexorstudios.vexcore.features.tpa.TpaFeature;
import com.vexorstudios.vexcore.features.trash.TrashFeature;
import com.vexorstudios.vexcore.features.workstations.WorkstationsFeature;
import com.vexorstudios.vexcore.gui.MenuListener;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VexCore by VexorStudios.
 *
 * <p>Loads at STARTUP so the economy (and anything else other plugins look up while enabling) is
 * registered before them. Worlds are not loaded yet at that point, so nothing here resolves a
 * world at enable; locations are resolved when they are used.
 */
public final class VexCore extends JavaPlugin implements Listener {

    private static VexCore instance;

    private Files files;
    private YamlConfiguration config = new YamlConfiguration();
    private Messages messages;
    private Database database;
    private PlayerData data;
    private Toggles toggles;
    private Restrictions restrictions;
    private Teleports teleports;
    private Placeholders placeholders;
    private Commands commands;
    private FeatureManager features;
    private ChatInput chatInput;
    private final Money money = new Money();
    private boolean started;
    private boolean serverLoaded;
    private long databaseMillis;

    public static VexCore get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        files = new Files(this);
        files.extract();
        config = files.settings("config.yml");

        messages = new Messages(this);
        database = new Database(this);
        data = new PlayerData(this);
        toggles = new Toggles(this);
        restrictions = new Restrictions();
        teleports = new Teleports(this);
        placeholders = new Placeholders(this);
        commands = new Commands(this);
        features = new FeatureManager(this);
        chatInput = new ChatInput(this);
        registerFeatures();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(data, this);
        getServer().getPluginManager().registerEvents(teleports, this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(chatInput, this);

        long begin = System.currentTimeMillis();
        List<String> problems = boot();
        started = true;
        // Enabled by a plugin manager after startup: the server is already up.
        serverLoaded = !Bukkit.getWorlds().isEmpty();
        if (serverLoaded) {
            placeholders.hook();
            commands.sync();
        }
        com.vexorstudios.vexcore.core.Banner.print(this, com.vexorstudios.vexcore.core.Banner.style(config.getString("startup-banner", "FANCY")),
                System.currentTimeMillis() - begin, databaseMillis, problems);
    }

    private void registerFeatures() {
        // Economy first: everything that pays or charges looks it up.
        features.add("economy", EconomyFeature::new);
        // Before everything that pays out: they ask it about alt accounts.
        features.add("ipprotection", com.vexorstudios.vexcore.features.ipprotection.IpProtectionFeature::new);
        features.add("spawn", SpawnFeature::new);
        features.add("warps", com.vexorstudios.vexcore.features.warps.WarpsFeature::new);
        features.add("pwarps", com.vexorstudios.vexcore.features.pwarps.PlayerWarpsFeature::new);
        features.add("afk", AfkFeature::new);
        features.add("home", HomeFeature::new);
        features.add("tpa", TpaFeature::new);
        features.add("combat", CombatFeature::new);
        features.add("settings", SettingsFeature::new);
        features.add("nightvision", NightVisionFeature::new);
        features.add("playerhide", PlayerHideFeature::new);
        features.add("mobtoggle", MobToggleFeature::new);
        features.add("phantoms", com.vexorstudios.vexcore.features.phantoms.PhantomsFeature::new);
        features.add("joinmessages", JoinMessagesFeature::new);
        features.add("deathmessages", DeathMessagesFeature::new);
        features.add("discord", LinkFeature::new);
        features.add("store", LinkFeature::new);
        features.add("apply", LinkFeature::new);
        features.add("live", LiveFeature::new);
        features.add("rules", MenuFeature::new);
        features.add("guide", MenuFeature::new);
        features.add("media", MenuFeature::new);
        features.add("ranks", MenuFeature::new);
        features.add("dropfix", DropFixFeature::new);
        features.add("workstations", WorkstationsFeature::new);
        features.add("sign", SignFeature::new);
        features.add("ping", PingFeature::new);
        features.add("trash", TrashFeature::new);
        features.add("coinflip", CoinflipFeature::new);
        features.add("invest", InvestFeature::new);
        features.add("daily", DailyFeature::new);
        features.add("keyall", KeyallFeature::new);
        features.add("playtime", PlaytimeFeature::new);
        features.add("killrewards", KillRewardsFeature::new);
        features.add("prestige", PrestigeFeature::new);
        features.add("boosts", BoostsFeature::new);
        features.add("sell", SellFeature::new);
        features.add("kits", KitsFeature::new);
        features.add("chatfilter", com.vexorstudios.vexcore.features.chatfilter.ChatFilterFeature::new);
        features.add("chat", com.vexorstudios.vexcore.features.chat.ChatFeature::new);
        features.add("msg", com.vexorstudios.vexcore.features.msg.MsgFeature::new);
        features.add("staffchat", com.vexorstudios.vexcore.features.staffchat.StaffChatFeature::new);
        features.add("commandroutes", com.vexorstudios.vexcore.features.commandroutes.CommandRoutesFeature::new);
        features.add("commandwhitelist", com.vexorstudios.vexcore.features.commandwhitelist.CommandWhitelistFeature::new);
        features.add("rename", com.vexorstudios.vexcore.features.rename.RenameFeature::new);
        features.add("ranktrial", com.vexorstudios.vexcore.features.ranktrial.RankTrialFeature::new);
        features.add("teams", com.vexorstudios.vexcore.features.teams.TeamsFeature::new);
        features.add("vanish", com.vexorstudios.vexcore.features.vanish.VanishFeature::new);
        features.add("screenshare", com.vexorstudios.vexcore.features.screenshare.ScreenshareFeature::new);
        features.add("nametags", com.vexorstudios.vexcore.features.nametags.NametagsFeature::new);
        features.add("hide", com.vexorstudios.vexcore.features.hide.HideFeature::new);
        features.add("scoreboard", com.vexorstudios.vexcore.features.scoreboard.ScoreboardFeature::new);
        features.add("stafftp", com.vexorstudios.vexcore.features.stafftp.StaffTpFeature::new);
        features.add("punishments", com.vexorstudios.vexcore.features.punishments.PunishmentsFeature::new);
        features.add("staffmode", com.vexorstudios.vexcore.features.staffmode.StaffModeFeature::new);
        features.add("staffessentials", com.vexorstudios.vexcore.features.staffessentials.StaffEssentialsFeature::new);
        features.add("reports", com.vexorstudios.vexcore.features.reports.ReportsFeature::new);
        features.add("invrollback", com.vexorstudios.vexcore.features.invrollback.InvRollbackFeature::new);
        features.add("stats", com.vexorstudios.vexcore.features.stats.StatsFeature::new);
        features.add("leaderboard", com.vexorstudios.vexcore.features.leaderboard.LeaderboardFeature::new);
        features.add("joincounter", com.vexorstudios.vexcore.features.joincounter.JoinCounterFeature::new);
        features.add("announce", com.vexorstudios.vexcore.features.announce.AnnounceFeature::new);
        features.add("antilag", com.vexorstudios.vexcore.features.antilag.AntilagFeature::new);
        features.add("ggwave", com.vexorstudios.vexcore.features.ggwave.GgWaveFeature::new);
        features.add("tebex", com.vexorstudios.vexcore.features.tebex.TebexFeature::new);
        features.add("votes", com.vexorstudios.vexcore.features.votes.VotesFeature::new);
        features.add("events", com.vexorstudios.vexcore.features.events.EventsFeature::new);
        features.add("giveaway", com.vexorstudios.vexcore.features.giveaway.GiveawayFeature::new);
        features.add("ffa", com.vexorstudios.vexcore.features.ffa.FfaFeature::new);
        features.add("duel", com.vexorstudios.vexcore.features.duel.DuelFeature::new);
        features.add("quests", com.vexorstudios.vexcore.features.quests.QuestsFeature::new);
        features.add("rtp", com.vexorstudios.vexcore.features.rtp.RtpFeature::new);
    }

    /** Reads the files, opens the database and starts every enabled feature. Returns problems. */
    private List<String> boot() {
        config = files.settings("config.yml");
        messages.load(files.settings("globalmessages.yml"));
        commands.load(files.settings("commands.yml"));
        List<String> problems = new ArrayList<>();
        YamlConfiguration databaseFile = files.settings("database.yml");
        try {
            // A broken database.yml would fall back to the default (SQLite) and quietly put a
            // MySQL server's players on an empty file. Refuse instead.
            if (files.failed("database.yml")) throw new SQLException("database.yml could not be read");
            long opening = System.currentTimeMillis();
            database.open(databaseFile);
            databaseMillis = System.currentTimeMillis() - opening;
        } catch (SQLException e) {
            problems.add("database.yml: could not connect (" + e.getMessage() + "). Player data will not load until this is fixed and VexCore is reloaded.");
            getLogger().severe("Could not open the database: " + e.getMessage());
        }
        toggles.schema();
        data.register(null, toggles);
        placeholders.add(null, "toggle", (player, id) -> String.valueOf(toggles.isOn(player.getUniqueId(), id)));
        features.enableAll(config);
        new CoreCommand(this).register();
        commands.registerShortcuts();
        data.loadAll();
        data.startAutosave(config.getInt("autosave-minutes", 5));
        problems.addAll(files.takeErrors());
        for (var feature : features.active()) problems.addAll(feature.problems());
        return problems;
    }

    /** Stops everything that {@link #boot()} started, saving every online player. */
    private void shutdown(boolean now) {
        data.stopAutosave();
        MenuListener.closeAll(null, now);
        features.prepareShutdown();
        data.unloadAll();
        features.disableAll();
        data.unregister(null);
        commands.unregisterAll();
        chatInput.clear();
        database.close();
    }

    /** {@code /vexcore reload}: everything, in order. Run on the global thread. */
    public void reload(CommandSender sender) {
        long begin = System.currentTimeMillis();
        messages.send(null, sender, "reload-start", Map.of());
        shutdown(false);
        files.extract();
        List<String> problems = boot();
        commands.sync();
        CoreCommand.report(this, sender, System.currentTimeMillis() - begin, problems);
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        serverLoaded = true;
        placeholders.hook();
    }

    @Override
    public void onDisable() {
        if (!started) return;
        teleports.cancelAll(null);
        shutdown(true);
        placeholders.unhook();
        started = false;
    }

    // ── Services ──────────────────────────────────────────────────────────

    public File jar() {
        return getFile();
    }

    public YamlConfiguration settings() {
        return config;
    }

    public boolean isServerLoaded() {
        return serverLoaded;
    }

    public Files files() {
        return files;
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public PlayerData data() {
        return data;
    }

    public Toggles toggles() {
        return toggles;
    }

    public Restrictions restrictions() {
        return restrictions;
    }

    public Teleports teleports() {
        return teleports;
    }

    public Placeholders placeholders() {
        return placeholders;
    }

    public Commands commands() {
        return commands;
    }

    public FeatureManager features() {
        return features;
    }

    public ChatInput chatInput() {
        return chatInput;
    }

    public Money money() {
        return money;
    }
}
