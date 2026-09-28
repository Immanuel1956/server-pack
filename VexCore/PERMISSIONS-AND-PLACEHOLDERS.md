# VexCore permissions and placeholders

Built from `plugin.yml`, `commands.yml` and `reference.yml` by `tools/gen_reference.py`.

In game (needs `vexcore.admin`): `/vexcore permissions [search] [page]` and `/vexcore placeholders [search] [page]`.
Click an entry to copy it. The plugin also writes `permissions.txt` and `placeholders.txt` into
`plugins/VexCore/` on every start and `/vexcore reload`, with your renamed commands and your own link
commands included.

**Who has it** is who gets the permission without a permissions plugin: *everyone*, *op* (operators)
or *nobody* (only players or groups you give it to, for example with LuckPerms).

## Permissions

### General

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.ranktrial` | op | Grant an allowed temporary LuckPerms rank |
| `vexcore.admin` | op | /vexcore (reload, import, features, permissions, placeholders) |

### Economy and rewards

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.balance` | everyone | /bal |
| `vexcore.balance.others` | everyone | /bal <player> |
| `vexcore.pay` | everyone | /pay and /paytoggle |
| `vexcore.baltop` | everyone | /baltop |
| `vexcore.payhistory` | everyone | /payhistory |
| `vexcore.payhistory.others` | op | /payhistory <player> |
| `vexcore.economy.admin` | op | /eco |
| `vexcore.coinflip` | everyone | /cf |
| `vexcore.coinflip.create` | everyone | Create coinflips |
| `vexcore.coinflip.bypass` | op | Skip the coinflip cooldown |
| `vexcore.invest` | everyone | /invest |
| `vexcore.invest.admin` | op | /invest limit, reset, event |
| `vexcore.daily` | everyone | /daily |
| `vexcore.daily.bypass` | op | Skip the daily cooldown |
| `vexcore.daily.admin` | op | /daily reset |
| `vexcore.keyall` | everyone | /keyall |
| `vexcore.keyall.admin` | op | /keyall force and set |
| `vexcore.playtime` | everyone | /playtime |
| `vexcore.playtime.admin` | op | /playtime reset |
| `vexcore.killrewards` | everyone | /killrewards |
| `vexcore.killrewards.admin` | op | /killrewards reset |
| `vexcore.prestige` | everyone | /prestige |
| `vexcore.prestige.admin` | op | /prestige set |
| `vexcore.boosts` | everyone | /boosts |
| `vexcore.boosts.bypass` | op | Skip the boost cooldown (still one boost at a time) |
| `vexcore.boosts.stack` | nobody | Run several boosts at once |

### Selling and kits

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.sell` | everyone | /sell (menu, hand, all) |
| `vexcore.worth` | everyone | /worth |
| `vexcore.sell.multiplier.vip` | nobody | Sell multiplier "vip" from features/sell/config.yml (add your own the same way) |
| `vexcore.sell.multiplier.mvp` | nobody | Sell multiplier "mvp" |
| `vexcore.sell.multiplier.elite` | nobody | Sell multiplier "elite" |
| `vexcore.kit` | everyone | /kit and /kit preview (each kit can ask for its own permission) |
| `vexcore.kit.vip` | nobody | The example "vip" kit |
| `vexcore.kit.bypass` | op | Ignore kit cooldowns |
| `vexcore.kit.admin` | op | /kit save, delete, give, reset |

### Spawn / AFK

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.spawn` | everyone | /spawn and /spawns |
| `vexcore.spawn.admin` | op | /setspawn and /delspawn |
| `vexcore.spawn.others` | op | /spawn <name> <player> |
| `vexcore.spawn.bypass` | op | Teleport to spawn without the countdown |

### Warps

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.warp` | everyone | /warp and /warps |
| `vexcore.warp.admin` | op | /setwarp and /delwarp |
| `vexcore.warp.bypass` | op | Warp without the countdown |
| `vexcore.pwarp` | everyone | /pwarp (browse, visit, buy and manage player warps) |
| `vexcore.pwarp.admin` | op | Delete or edit anyone's player warp; skip the slot limit and safety check |
| `vexcore.pwarp.free` | op | Player warps cost nothing |
| `vexcore.pwarp.bypass` | op | Player warp without the countdown |
| `vexcore.afk` | everyone | /afk |
| `vexcore.afk.admin` | op | /setafk |
| `vexcore.afk.bypass` | op | Teleport to the afk area without the countdown |

### Homes (vexcore.home.<n> = up to n homes, highest wins)

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.home` | everyone | /home, /sethome, /delhome |
| `vexcore.home.bypass` | op | Teleport home without the countdown |

### Teleport requests

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.tpa` | everyone | /tpa, /tpaccept, /tpdeny, /tpacancel, /tpatoggle, /tpauto |
| `vexcore.tpahere` | everyone | /tpahere |
| `vexcore.tpa.hereall` | op | /tpahereall |
| `vexcore.tpa.bypass` | op | Teleport without the countdown after a request is accepted |

### Combat

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.combat.bypass` | nobody | Never get combat tagged |

### Toggles

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.settings` | everyone | /settings |
| `vexcore.nightvision` | everyone | /nightvision |
| `vexcore.playerhide` | everyone | /playerhide |
| `vexcore.playerhide.exempt` | nobody | Stay visible to players who use /playerhide |
| `vexcore.mobtoggle` | everyone | /mobtoggle |
| `vexcore.phantoms` | everyone | /phantoms |
| `vexcore.jointoggle` | everyone | /jointoggle |
| `vexcore.deathtoggle` | everyone | /deathtoggle |
| `vexcore.joinmessages.silent` | nobody | Join and leave without a message |

### Links and menus

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.discord` | everyone | /discord |
| `vexcore.store` | everyone | /store |
| `vexcore.apply` | everyone | /apply |
| `vexcore.live` | nobody | /live <link> |
| `vexcore.live.toggle` | everyone | /live toggle |
| `vexcore.live.bypass` | op | Skip the /live cooldown |
| `vexcore.rules` | everyone | /rules |
| `vexcore.socials` | everyone | /socials |
| `vexcore.guide` | everyone | /guide |
| `vexcore.media` | everyone | /media |
| `vexcore.ranks` | everyone | /ranks |

### Utilities

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.craft` | op | /craft |
| `vexcore.anvil` | op | /anvil |
| `vexcore.grindstone` | op | /grindstone |
| `vexcore.smithingtable` | op | /smithingtable |
| `vexcore.loom` | op | /loom |
| `vexcore.cartographytable` | op | /cartographytable |
| `vexcore.stonecutter` | op | /stonecutter |
| `vexcore.echest` | op | /echest |
| `vexcore.echest.others` | op | /echest <player> |
| `vexcore.sign` | op | /sign |
| `vexcore.ping` | everyone | /ping |
| `vexcore.ping.others` | op | /ping <player> |
| `vexcore.trash` | everyone | /trash |

### Chat, staff and events

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.chatfilter.admin` | op | /chatfilter and /chathistory |
| `vexcore.chatfilter.bypass` | nobody | Skip the chat filter checks |
| `vexcore.chatfilter.alerts` | op | See what the chat filter blocks |
| `vexcore.chatfilter.bypass.advertising` | nobody | Post links to other servers |
| `vexcore.chatfilter.bypass.scam` | nobody | Say things the SCAM rule blocks (free op, selling accounts) |
| `vexcore.chatfilter.bypass.links` | nobody | Post links |
| `vexcore.chatfilter.bypass.personalinfo` | nobody | Post e-mail addresses and phone numbers (the PERSONAL_INFO rule) |
| `vexcore.chattoggle` | everyone | /chattoggle |
| `vexcore.mentiontoggle` | everyone | /mentiontoggle |
| `vexcore.chat.admin` | op | /mutechat, /clearchat |
| `vexcore.chat.manage` | op | Staff chat hover: the profile card plus a click that opens /punish |
| `vexcore.chat.bypass` | op | Talk while chat is locked, keep chat on /clearchat |
| `vexcore.chat.color` | nobody | Colour codes in chat |
| `vexcore.chat.supporter` | nobody | The supporter lines on the chat hover card |
| `vexcore.chat.item` | everyone | [item] in chat |
| `vexcore.chat.inventory` | everyone | [inv] in chat |
| `vexcore.chat.enderchest` | everyone | [ec] in chat |
| `vexcore.chat.mention` | everyone | @name mentions |
| `vexcore.msg` | everyone | /msg, /reply, /msgtoggle |
| `vexcore.msg.color` | nobody | Colour codes in private messages |
| `vexcore.msg.bypass` | op | Message players who turned messages off or ignore you |
| `vexcore.ignore` | everyone | /ignore |
| `vexcore.socialspy` | op | /socialspy |
| `vexcore.staffchat` | op | Staff chat |
| `vexcore.commandwhitelist.bypass` | op | Use every command |
| `vexcore.commandwhitelist.staff` | op | The staff command group |
| `vexcore.rename` | op | /rename |
| `vexcore.rename.color` | op | Colours in renames |
| `vexcore.rename.free` | op | Rename without paying |
| `vexcore.team` | everyone | /team |
| `vexcore.team.bypass` | op | Team home without the countdown |
| `vexcore.vanish` | op | /vanish |
| `vexcore.vanish.others` | op | /vanish <player> |
| `vexcore.vanish.see` | op | See vanished players |
| `vexcore.screenshare` | op | /ss and screenshare alerts |
| `vexcore.screenshare.exempt` | nobody | Can't be screenshared |
| `vexcore.stafftp` | op | /stp, /stphere, /stpback |
| `vexcore.scoreboard` | everyone | /scoreboard |
| `vexcore.stats` | everyone | /stats |
| `vexcore.stats.others` | everyone | /stats <player> |
| `vexcore.leaderboard` | everyone | /leaderboard |
| `vexcore.joincounter.admin` | op | /joincounter |
| `vexcore.announce` | op | /announce |
| `vexcore.antilag` | op | /antilag |
| `vexcore.ggwave` | op | /ggwave |
| `vexcore.ipprotection.alts` | op | /alts <player> |
| `vexcore.ipprotection.bypass` | nobody | Not limited by IP protection (give to trusted households) |
| `vexcore.punish.ban` | op | /ban |
| `vexcore.punish.ipban` | op | /ipban |
| `vexcore.punish.mute` | op | /mute |
| `vexcore.punish.kick` | op | /kick |
| `vexcore.punish.warn` | op | /warn |
| `vexcore.punish.unban` | op | /unban |
| `vexcore.punish.unmute` | op | /unmute |
| `vexcore.punish.unwarn` | op | /unwarn |
| `vexcore.punish.history` | op | /history |
| `vexcore.punish.menu` | op | /punish |
| `vexcore.punish.permanent` | op | Permanent (and over max-temporary) punishments |
| `vexcore.punish.exempt` | nobody | Can't be punished by players |
| `vexcore.punish.notify` | op | Sees silent punishments |
| `vexcore.punish.lift` | op | Lift punishments from /history |
| `vexcore.staffmode` | op | /staff |
| `vexcore.fly` | op | /fly |
| `vexcore.fly.others` | op | /fly <player> |
| `vexcore.heal` | op | /heal |
| `vexcore.heal.others` | op | /heal <player> |
| `vexcore.feed` | op | /feed |
| `vexcore.feed.others` | op | /feed <player> |
| `vexcore.god` | op | /god |
| `vexcore.god.others` | op | /god <player> |
| `vexcore.speed` | op | /speed |
| `vexcore.speed.others` | op | /speed <n> <kind> <player> |
| `vexcore.gamemode` | op | /gmc /gms /gma /gmsp |
| `vexcore.gmc.others` | op | /gmc <player> |
| `vexcore.gms.others` | op | /gms <player> |
| `vexcore.gma.others` | op | /gma <player> |
| `vexcore.gmsp.others` | op | /gmsp <player> |
| `vexcore.invsee` | op | /invsee |
| `vexcore.clearinventory` | op | /clearinventory |
| `vexcore.clearinventory.others` | op | /clearinventory <player> |
| `vexcore.vote` | everyone | /vote /votetop /voteparty |
| `vexcore.votes.admin` | op | /fakevote, /voteparty start |
| `vexcore.events` | everyone | /event |
| `vexcore.events.admin` | op | /event start\|stop\|koth set |
| `vexcore.events.ignore` | nobody | Can't capture the KOTH hill |
| `vexcore.report` | everyone | /report <player> |
| `vexcore.reports` | op | /reports |
| `vexcore.reports.manage` | op | /report resolve <number> |
| `vexcore.reports.notify` | op | Told about new reports |
| `vexcore.reports.exempt` | nobody | Can't be reported |
| `vexcore.reports.bypasscooldown` | op | No report cooldown |
| `vexcore.invrollback` | op | /invrollback <player> |
| `vexcore.invrollback.restore` | op | Restore inventory backups |
| `vexcore.goal` | everyone | /goal |
| `vexcore.goal.admin` | op | /goal set\|add\|reset\|refresh |
| `vexcore.tebex.admin` | op | /tebexpurchase |
| `vexcore.giveaway` | everyone | /giveaway (and /giveaway cancel for your own) |
| `vexcore.giveaway.forcecancel` | op | /giveaway forcecancel <player> [reason]: cancel anyone's giveaway |
| `vexcore.ffa` | everyone | /ffa (host) |
| `vexcore.ffa.join` | everyone | /ffaaccept |
| `vexcore.ffa.bypass` | op | No FFA host cooldown |
| `vexcore.duel` | everyone | /1v1 |
| `vexcore.quests` | everyone | /quests |
| `vexcore.rtp` | everyone | /rtp |
| `vexcore.rtp.bypass` | op | No RTP countdown or cooldown |
| `vexcore.endlock` | op | /endlock |
| `vexcore.endlock.bypass` | op | RTP and portals to the End while it is locked |
| `vexcore.hide` | op | /hide (needs PacketEvents) |
| `vexcore.nametags.admin` | op | /nametags |

### Made from your settings

| Permission | Who has it | What it does |
|---|---|---|
| `vexcore.home.[number]` | nobody | Up to that many homes (vexcore.home.5); the highest one counts |
| `vexcore.pwarps.[number]` | nobody | Up to that many player warps (vexcore.pwarps.5) |
| `vexcore.warp.[name]` | nobody | Use that server warp, when per-warp-permission is on |
| `vexcore.kit.[kit]` | nobody | Claim that kit (a kit without its own permission: line) |
| `[any permission you write in a config]` | nobody | permission: of a boost, kit, menu item or link, permission-homes, sell multipliers, command whitelist groups |

## Commands and their permissions

Rename, alias, re-permission or turn off any of these in `commands.yml`.

| Command | Aliases | Permission | What it does |
|---|---|---|---|
| `/ranktrial <player> <rank> <duration>` |  | `vexcore.ranktrial` |  |
| `/plugins` | /pl | `` |  |
| `/help` |  | `` |  |
| `/tutorial` |  | `` |  |
| `/vexcore <reload\|features\|permissions\|placeholders\|version\|import setupcore>` | /vcore, /vc | `vexcore.admin` | VexCore admin: reload, import, features, permissions, placeholders, version |
| `/balance [player]` | /bal, /money | `vexcore.balance` | Your balance, or another player's |
| `/pay <player> <amount>` |  | `vexcore.pay` | Pay a player |
| `/paytoggle` |  | `vexcore.pay` | Turn receiving payments on or off |
| `/baltop` | /balancetop, /moneytop | `vexcore.baltop` | The richest players |
| `/payhistory [player]` | /payments | `vexcore.payhistory` | Your latest payments |
| `/eco <give\|take\|set\|reset\|freeze> <player> [amount]` | /economy | `vexcore.economy.admin` | Change balances |
| `/coinflip [create <amount>\|delete\|toggle\|history]` | /cf | `vexcore.coinflip` | Bet money on a coin flip |
| `/invest [add\|withdraw <amount\|all>\|collect\|top]` |  | `vexcore.invest` | Invest money that pays out while you play |
| `/daily` |  | `vexcore.daily` | Your daily reward |
| `/keyall` |  | `vexcore.keyall` | Time until the next key-all |
| `/playtime` | /playtimerewards | `vexcore.playtime` | Rewards for time played |
| `/killrewards` |  | `vexcore.killrewards` | Rewards for player kills |
| `/prestige` |  | `vexcore.prestige` | Prestige levels |
| `/boosts` | /boost, /booster | `vexcore.boosts` | Short potion boosts |
| `/sell [hand\|all]` |  | `vexcore.sell` | Sell items for money |
| `/worth [item]` | /price | `vexcore.worth` | What an item sells for |
| `/kit [kit\|preview <kit>\|save <kit>\|delete <kit>\|give <kit> <player>\|reset <player> [kit]]` | /kits | `vexcore.kit` | Claim a kit |
| `/chatfilter <regex\|remove\|similar\|test\|strikes\|clear\|reload>` | /cf-rules | `vexcore.chatfilter.admin` | Chat filter tools |
| `/chathistory [player]` | /chatlog | `vexcore.chatfilter.admin` | Everything the chat filter caught |
| `/chattoggle` | /togglechat | `vexcore.chattoggle` | Hide or show chat for yourself |
| `/mentiontoggle` | /togglementions, /mentions | `vexcore.mentiontoggle` | Turn @mention pings on or off |
| `/mutechat [30s\|5m\|1h]` | /lockchat | `vexcore.chat.admin` | Lock the chat for everyone |
| `/clearchat` | /cc | `vexcore.chat.admin` | Clear the chat |
| `/chatview <id>` |  | `` | Open a shared inventory |
| `/msg <player> <message>` | /tell, /w, /whisper, /m, /pm | `vexcore.msg` | Send a private message |
| `/reply <message>` | /r | `vexcore.msg` | Answer the last private message |
| `/msgtoggle` | /togglemsg, /togglepm | `vexcore.msg` | Turn private messages on or off |
| `/ignore [player\|list]` |  | `vexcore.ignore` | Ignore a player (or list who you ignore) |
| `/socialspy` | /spy | `vexcore.socialspy` | See private messages |
| `/staffchat [message]` | /sc | `vexcore.staffchat` | Staff chat |
| `/rename <name\|reset>` |  | `vexcore.rename` | Rename the item in your hand |
| `/team <create\|invite\|join\|leave\|kick\|promote\|demote\|transfer\|rename\|disband\|info\|list\|pvp\|sethome\|home\|chat>` | /t, /teams | `vexcore.team` | Teams |
| `/teamchat [message]` | /tc | `vexcore.team` | Team chat |
| `/vanish [player]` | /v | `vexcore.vanish` | Vanish |
| `/screenshare <player> \| end <player> \| setroom` | /ss | `vexcore.screenshare` | Freeze a player for a screenshare |
| `/stp <player> [target]` |  | `vexcore.stafftp` | Staff teleport |
| `/stphere <player>` |  | `vexcore.stafftp` | Bring a player to you |
| `/stpback` |  | `vexcore.stafftp` | Back to where you were before a staff teleport |
| `/hide` | /disguise | `vexcore.hide` | A shared name and skin (needs PacketEvents) |
| `/nametags [reload]` |  | `vexcore.nametags.admin` | Name tag settings, /nametags reload rebuilds them |
| `/scoreboard` | /sb | `vexcore.scoreboard` | Show or hide the scoreboard |
| `/stats [player]` | /stat | `vexcore.stats` | Player stats |
| `/leaderboard [category]` | /lb, /top | `vexcore.leaderboard` | Leaderboards |
| `/joincounter [reset\|clear\|set <number>]` |  | `vexcore.joincounter.admin` | The first-join counter |
| `/announce <message>` | /broadcast, /bc | `vexcore.announce` | Announce something to everyone |
| `/antilag` | /clearlag, /lagg | `vexcore.antilag` | Clear ground items now |
| `/ggwave [time]` | /gg | `vexcore.ggwave` | Start a GG wave |
| `/giveaway [cancel [number] \| forcecancel <player> [reason]]` | /gws, /giveaways | `vexcore.giveaway` | Giveaways: /%command% cancel [number], staff: /%command% forcecancel <player> [reason] |
| `/ffa` |  | `vexcore.ffa` | Host an FFA event |
| `/ffaaccept` |  | `vexcore.ffa.join` | Join the FFA event |
| `/1v1 <player> [wager] [world] \| accept \| deny` | /duel | `vexcore.duel` | Challenge a player to a 1v1 |
| `/quests` | /quest, /questboard | `vexcore.quests` | The quest board |
| `/rtp [world]` | /randomtp, /wild | `vexcore.rtp` | Random teleport |
| `/endlock [on\|off]` | /lockend | `vexcore.endlock` | Lock or unlock the End (RTP and portals) |
| `/warp [name]` |  | `vexcore.warp` | Warp to a server warp, or open the warp menu |
| `/warps` |  | `vexcore.warp` | The server warp menu |
| `/setwarp <name> [description]` |  | `vexcore.warp.admin` | Set a server warp where you stand (icon: the item in your hand) |
| `/delwarp <name>` |  | `vexcore.warp.admin` | Delete a server warp |
| `/pwarp [name\|set\|delete\|icon\|desc\|list\|cost\|help]` | /pwarps, /pw, /playerwarp, /playerwarps | `vexcore.pwarp` | Player warps: browse, visit, buy and manage |
| `/spawn [name] [player]` |  | `vexcore.spawn` | Teleport to a spawn |
| `/setspawn [name]` |  | `vexcore.spawn.admin` | Set a spawn where you stand |
| `/delspawn <name>` |  | `vexcore.spawn.admin` | Delete a spawn |
| `/spawns` |  | `vexcore.spawn` | List the spawns |
| `/afk` |  | `vexcore.afk` | Teleport to the afk area |
| `/setafk` |  | `vexcore.afk.admin` | Set the afk area where you stand |
| `/home [number]` | /homes | `vexcore.home` | Open your homes or teleport to one |
| `/sethome [number]` |  | `vexcore.home` | Save a home where you stand |
| `/delhome <number>` |  | `vexcore.home` | Delete a home |
| `/tpa <player>` | /tpask | `vexcore.tpa` | Ask to teleport to a player |
| `/tpahere <player>` |  | `vexcore.tpahere` | Ask a player to teleport to you |
| `/tpahereall` |  | `vexcore.tpa.hereall` | Ask everyone to teleport to you |
| `/tpaccept [player]` | /tpyes | `vexcore.tpa` | Accept a teleport request |
| `/tpdeny [player]` | /tpno | `vexcore.tpa` | Deny a teleport request |
| `/tpacancel [player]` | /tpcancel | `vexcore.tpa` | Cancel your own requests |
| `/tpatoggle [tpa\|tpahere]` | /tptoggle | `vexcore.tpa` | Turn incoming requests on or off |
| `/tpauto` |  | `vexcore.tpa` | Accept requests automatically |
| `/settings` | /options, /prefs | `vexcore.settings` | Open your settings |
| `/nightvision` | /nv | `vexcore.nightvision` | Toggle permanent night vision |
| `/playerhide` | /ph | `vexcore.playerhide` | Hide the other players from your view |
| `/mobtoggle` |  | `vexcore.mobtoggle` | Stop mobs spawning around you |
| `/phantoms` | /phantomtoggle, /togglephantoms | `vexcore.phantoms` | Despawn phantoms around you |
| `/jointoggle` |  | `vexcore.jointoggle` | Show or hide join and leave messages |
| `/deathtoggle` |  | `vexcore.deathtoggle` | Show or hide death messages |
| `/discord` | /dc | `vexcore.discord` | The Discord invite |
| `/store` | /webstore | `vexcore.store` | The webstore link |
| `/apply` |  | `vexcore.apply` | How to apply for staff |
| `/live <link\|toggle>` | /stream | `vexcore.live.toggle` | Announce your stream, or hide announcements |
| `/rules` |  | `vexcore.rules` | The server rules |
| `/guide` | /tutorial | `vexcore.guide` | The server guide |
| `/media` | /creator | `vexcore.media` | Apply for the media rank |
| `/ranks` | /rank | `vexcore.ranks` | The server ranks |
| `/socials` | /social, /links | `vexcore.socials` | Every link and menu of the server in one place |
| `/craft` | /workbench, /wb | `vexcore.craft` | Open a crafting table |
| `/anvil` |  | `vexcore.anvil` | Open an anvil |
| `/grindstone` |  | `vexcore.grindstone` | Open a grindstone |
| `/smithingtable` | /smithing | `vexcore.smithingtable` | Open a smithing table |
| `/loom` |  | `vexcore.loom` | Open a loom |
| `/cartographytable` | /cartography | `vexcore.cartographytable` | Open a cartography table |
| `/stonecutter` |  | `vexcore.stonecutter` | Open a stonecutter |
| `/echest [player]` | /enderchest, /ec | `vexcore.echest` | Open your ender chest |
| `/sign` |  | `vexcore.sign` | Sign the item in your hand |
| `/ping [player]` |  | `vexcore.ping` | Show your ping |
| `/trash` | /disposal | `vexcore.trash` | Open the trash bin |
| `/alts <player>` | /altcheck, /dupeip | `vexcore.ipprotection.alts` | Accounts that played from the same network |
| `/ban <player> [duration] [reason] [-s]` | /tempban | `vexcore.punish.ban` | Ban a player (add a duration for a temporary ban) |
| `/ipban <player> [duration] [reason] [-s]` | /banip, /tempipban | `vexcore.punish.ipban` | Ban a player's network |
| `/mute <player> [duration] [reason] [-s]` | /tempmute | `vexcore.punish.mute` | Mute a player |
| `/kick <player> [reason] [-s]` |  | `vexcore.punish.kick` | Kick a player |
| `/warn <player> [reason] [-s]` |  | `vexcore.punish.warn` | Warn a player |
| `/unban <player> [-s]` | /pardon, /unipban | `vexcore.punish.unban` | Lift a ban |
| `/unmute <player> [-s]` |  | `vexcore.punish.unmute` | Lift a mute |
| `/unwarn <player> [-s]` | /delwarn | `vexcore.punish.unwarn` | Lift the newest warning |
| `/history <player>` | /punishments, /checkpunish | `vexcore.punish.history` | A player's punishments |
| `/punish <player>` | /p | `vexcore.punish.menu` | The punish menu |
| `/staff` | /staffmode, /mod | `vexcore.staffmode` | Staff mode |
| `/fly [player]` |  | `vexcore.fly` | Toggle flying |
| `/heal [player]` |  | `vexcore.heal` | Heal |
| `/feed [player]` | /eat | `vexcore.feed` | Feed |
| `/god [player]` | /godmode | `vexcore.god` | God mode |
| `/speed <1-10> [walk\|fly] [player]` |  | `vexcore.speed` | Walk or fly speed |
| `/gmc [player]` |  | `vexcore.gamemode` | Creative |
| `/gms [player]` |  | `vexcore.gamemode` | Survival |
| `/gma [player]` |  | `vexcore.gamemode` | Adventure |
| `/gmsp [player]` |  | `vexcore.gamemode` | Spectator |
| `/invsee <player>` | /inv | `vexcore.invsee` | See a player's inventory |
| `/clearinventory [player]` | /ci, /clear | `vexcore.clearinventory` | Clear an inventory |
| `/vote` | /votes | `vexcore.vote` | Vote for the server |
| `/votetop` | /topvoters | `vexcore.vote` | This month's top voters |
| `/voteparty` | /vp | `vexcore.vote` | Vote party progress |
| `/fakevote <player> [site]` |  | `vexcore.votes.admin` | Send a test vote |
| `/event [start <type>\|stop\|koth set [radius]]` | /events | `vexcore.events` | Server events |
| `/report <player> [reason]` |  | `vexcore.report` | Report a player |
| `/reports [number]` |  | `vexcore.reports` | Pending player reports |
| `/invrollback <player>` | /rollback, /invrb, /restoreinv | `vexcore.invrollback` | Inventory backups and restores |
| `/goal` | /goals, /storegoal | `vexcore.goal` | The store goal |
| `/tebexpurchase <player> <amount> [package]` |  | `vexcore.tebex.admin` | Announce a store purchase (for Tebex package commands) |

## Placeholders

Every one works in every VexCore file (messages, menus, scoreboard, name tags) even without
PlaceholderAPI, and everywhere else (TAB, holograms, other plugins) through PlaceholderAPI.
`[x]` is a part you fill in.

### core

| Placeholder | What it shows |
|---|---|
| `%vexcore_toggle_[id]%` | true/false for a /settings toggle: pay, msg, tpa, tpahere, tpauto, chat, mentions, scoreboard, nightvision, playerhide, mobtoggle, phantoms, joinmessages, deathmessages, live, coinflip |

### economy

| Placeholder | What it shows |
|---|---|
| `%vexcore_balance%` | Balance, plain number (1500.25) |
| `%vexcore_balance_formatted%` | Balance with the currency ($1,500.25) |
| `%vexcore_balance_short%` | Balance short ($1.5k) |
| `%vexcore_baltop_name_[place]%` | Name at that place of /baltop (baltop_name_1) |
| `%vexcore_baltop_balance_[place]%` | Balance at that place of /baltop |

### keyall

| Placeholder | What it shows |
|---|---|
| `%vexcore_keyall%` | Time to the next key-all (42m 10s) |
| `%vexcore_keyall_countdown%` | Time to the next key-all as a clock (42:10, 1:02:03) |
| `%vexcore_keyall_seconds%` | Seconds to the next key-all |
| `%vexcore_keyall_minutes%` | Minutes to the next key-all, rounded up |
| `%vexcore_keyall_at%` | Clock time of the next key-all (18:00, placeholder.time-format) |
| `%vexcore_keyall_interval%` | How often key-alls happen (1h) |

### home

| Placeholder | What it shows |
|---|---|
| `%vexcore_homes%` | Homes the player has |
| `%vexcore_homes_max%` | Homes the player may have |

### warps

| Placeholder | What it shows |
|---|---|
| `%vexcore_warps%` | How many server warps there are |

### pwarps

| Placeholder | What it shows |
|---|---|
| `%vexcore_pwarps%` | Player warps the player owns |
| `%vexcore_pwarps_max%` | Player warps the player may own |
| `%vexcore_pwarps_next_cost%` | What the next player warp costs |

### combat

| Placeholder | What it shows |
|---|---|
| `%vexcore_combat%` | In combat / Safe (placeholder: in the combat file) |
| `%vexcore_combat_seconds%` | Seconds of combat tag left |

### ping

| Placeholder | What it shows |
|---|---|
| `%vexcore_ping%` | The player's ping in ms |

### coinflip

| Placeholder | What it shows |
|---|---|
| `%vexcore_coinflip_games%` | Open coinflip games |
| `%vexcore_coinflip_wins%` | Coinflips the player won |
| `%vexcore_coinflip_losses%` | Coinflips the player lost |

### invest

| Placeholder | What it shows |
|---|---|
| `%vexcore_invest_invested%` | Money invested |
| `%vexcore_invest_pending%` | Income waiting to be collected |
| `%vexcore_invest_income%` | Income per second |
| `%vexcore_invest_hourly%` | Income per hour online |
| `%vexcore_invest_limit%` | How much the player may invest |
| `%vexcore_invest_earned%` | Everything the investment has earned |

### daily

| Placeholder | What it shows |
|---|---|
| `%vexcore_daily%` | Time to the next /daily, or Ready |
| `%vexcore_daily_streak%` | Days in a row the player claimed /daily |

### playtime

| Placeholder | What it shows |
|---|---|
| `%vexcore_playtime%` | The player's playtime |

### killrewards

| Placeholder | What it shows |
|---|---|
| `%vexcore_kills%` | Player kills (what kill rewards count) |

### prestige

| Placeholder | What it shows |
|---|---|
| `%vexcore_prestige%` | The player's prestige level |
| `%vexcore_prestige_max%` | The highest prestige level |

### boosts

| Placeholder | What it shows |
|---|---|
| `%vexcore_boosts_cooldown%` | Boost cooldown left, or Ready |
| `%vexcore_boosts_active%` | The running boost's name, or None |
| `%vexcore_boosts_active_time%` | Time left on the running boost |

### sell

| Placeholder | What it shows |
|---|---|
| `%vexcore_sell_multiplier%` | The player's sell multiplier (1.5) |

### kits

| Placeholder | What it shows |
|---|---|
| `%vexcore_kit_[kit]%` | Ready, time left, Claimed or Locked (kit_starter) |

### teams

| Placeholder | What it shows |
|---|---|
| `%vexcore_team%` | The player's team name |
| `%vexcore_team_tag%` | The team tag for chat and name tags |
| `%vexcore_team_role%` | The player's role in the team |
| `%vexcore_team_members%` | Members of the team |
| `%vexcore_team_online%` | Members of the team online |

### vanish

| Placeholder | What it shows |
|---|---|
| `%vexcore_vanished%` | true/false: the player is vanished |
| `%vexcore_online%` | Players online, without vanished staff |

### staffmode

| Placeholder | What it shows |
|---|---|
| `%vexcore_staffmode%` | true/false: the player is in staff mode |

### punishments

| Placeholder | What it shows |
|---|---|
| `%vexcore_punish_muted%` | true/false: the player is muted |

### reports

| Placeholder | What it shows |
|---|---|
| `%vexcore_reports_pending%` | Reports waiting for staff |

### stats

| Placeholder | What it shows |
|---|---|
| `%vexcore_stats_[stat]%` | kills, deaths, streak, best_streak, mob_kills, blocks_broken, blocks_placed, duel_wins, duel_losses, ffa_kills |
| `%vexcore_stats_kdr%` | Kills per death |

### leaderboard

| Placeholder | What it shows |
|---|---|
| `%vexcore_top_[category]_[place]_name%` | Name at that place (top_kills_1_name); categories are in the leaderboard file |
| `%vexcore_top_[category]_[place]_value%` | Value at that place (top_kills_1_value) |

### joincounter

| Placeholder | What it shows |
|---|---|
| `%vexcore_joincount%` | How many new players have joined (first joins) |

### antilag

| Placeholder | What it shows |
|---|---|
| `%vexcore_antilag%` | Time to the next ground item clear |

### quests

| Placeholder | What it shows |
|---|---|
| `%vexcore_quests_done%` | Quests the player finished |

### tebex

| Placeholder | What it shows |
|---|---|
| `%vexcore_goal_name%` | The store goal's name |
| `%vexcore_goal_current%` | Money towards the goal, plain number |
| `%vexcore_goal_current_formatted%` | Money towards the goal ($120.00) |
| `%vexcore_goal_target%` | The goal's target, plain number |
| `%vexcore_goal_target_formatted%` | The goal's target ($500.00) |
| `%vexcore_goal_percent%` | How far the goal is, in percent |

### votes

| Placeholder | What it shows |
|---|---|
| `%vexcore_voteparty_current%` | Votes towards the next vote party |
| `%vexcore_voteparty_needed%` | Votes a vote party needs |

### events

| Placeholder | What it shows |
|---|---|
| `%vexcore_event_name%` | The running event, or None |
| `%vexcore_event_time%` | Time left in the running event |
| `%vexcore_event_next%` | Time to the next automatic event |

### rtp

| Placeholder | What it shows |
|---|---|
| `%vexcore_end_locked%` | true/false: the End is locked |
