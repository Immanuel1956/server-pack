# VexCore changelog

## Round 18: warps, player warps, all-time alt links, giveaway cancel, startup banner

### Build fix (release blocker)
- `mvn clean package` failed at the ProGuard step: the Tebex head art (Round 16) uses `java.awt`/`javax.imageio`, which `proguard.conf` did not list. Added `java.desktop.jmod`. The obfuscated jar builds again.

### Server warps (`features.warps`, new)
- `/warp <name>` warps (countdown like /spawn, `vexcore.warp.bypass` skips it); `/warp` or `/warps` opens a paged menu.
- `/setwarp <name> [description]` (staff): the item in your hand becomes the icon. `/delwarp <name>`.
- Saved in `data/warps.yml` (per server, like spawns). `per-warp-permission: true` locks each warp behind `vexcore.warp.<name>` (locked warps show as locked).

### Player warps (`features.pwarps`, new)
- `/pwarp` browses every player warp: sort by most visited / newest / A-Z, show everyone's or only yours.
- `/pwarp set <name>` buys a warp where you stand (or moves one of yours). Cost for a player owning N warps: `(base + increase-per-warp × N) × multiplier-per-warp^N`; defaults 100k, 200k, 300k... `vexcore.pwarp.free` makes them free.
- Slots: `default-slots: 3`; ranks get more with `vexcore.pwarps.<n>` (e.g. `vexcore.pwarps.5`) or the `permission-slots` list; `max-slots` caps it.
- `/pwarp delete|icon|desc <name>`, `/pwarp list [player]`, `/pwarp cost`. Staff (`vexcore.pwarp.admin`) can delete or edit anyone's warp.
- Safety: no warps over lava/fire/cactus, inside blocks or above drops of more than 3 blocks (standing on slabs, carpets, paths is fine). The landing spot is checked again when someone warps there (Paper, when its chunk is loaded), so a trap built later fails the warp instead of killing the visitor.
- Names are 3-16 letters/numbers/-/_; names and descriptions go through the chat filter; descriptions are shown as plain text (no colour codes or click tricks).
- Visits are counted (once per visitor per warp per restart). New warps are announced (`announce-new`). Optional refund on delete (`refund-percent`, default 0).
- New table `pwarps`; shared by every server on the same MySQL.

### Alt detection: all-time links saved in the database
- Accounts that ever played from the same network stay linked forever, even after one switches network (VPN, mobile data). At join, each account's group is read from `ip_accounts`: every network it ever used, every account seen there, and their alts (`link.depth: 2`).
- Invest, keyall, vote party (who earns), daily, kill and playtime rewards (claims) and GG waves now check the whole linked group, not just the current network.
- Giveaways: linked accounts share one entry, checked against the entries in the database, so it holds across restarts (it used to reset on restart). An alt of the owner can't enter the owner's giveaway.
- `/alts <player>` lists every linked account and whether it shared a network directly or came through another alt.
- Caps (`link.max-accounts: 40`, `link.max-networks: 60`) stop a shared network from linking everyone; use `exempt-ips` for those. `link.all-time: false` goes back to "same network right now".

### Giveaways
- `/giveaway cancel [number]`: cancel your own giveaway; the prize goes back to Your Prizes. By default only while nobody has entered (`rules.owner-cancel-with-entries: false`, stops bait-and-cancel).
- `/giveaway forcecancel <player> [reason]` (`vexcore.giveaway.forcecancel`): staff cancel all of a player's running giveaways; the owner is told (now, or at their next join).
- Offline winners get a title when they next join: **CONGRATS!** / *You have won <player>'s giveaway, enjoy!*, plus a chat line pointing to Your Prizes. Online winners get the title too. Owners whose giveaway had no entries, or was cancelled by staff, while they were offline are told on join as well. (`giveaway_claims` gains a `notify` column automatically.)
- Cancelling and the end timer can't both happen to the same giveaway.

### Startup
- New console banner: gradient VEXCORE logo, then one line each for server, database (with connect time), features, commands and plugin hooks (Vault, PlaceholderAPI, PacketEvents, LuckPerms, NuVotifier), and the start time. Problems and failed features are listed only when there are any. About 18 lines instead of one line per feature (70+).
- `startup-banner: FANCY | SIMPLE | OFF` in config.yml. SIMPLE is plain ASCII for consoles that show ? instead of symbols.

### Speed
- Scoreboard: its lines, title frames and disabled worlds are read once instead of for every player on every update (three list copies per player per second); the online count is worked out once per round.
- Keyall and antilag: warning times are parsed once, not every second.
- Name tags: the display style (nine settings) is read once, not for each line of each rebuild. A bad `display.background` is reported instead of breaking tags.
- Text: a regex used when a placeholder is a component is compiled once.
- Checked and already lean: quest progress, chat, staff mode, the name-tag follower, teleports, database (WAL, batched writes). No risky rewrites.

Validation: built with `mvn clean package` against Paper API 1.21.10 (compiled from PaperMC's source, since repo.papermc.io is unreachable here), Vault 1.7, PlaceholderAPI 2.11.5 and PacketEvents 2.14.0: 0 compile errors, ProGuard completed, 29 tests passed (new: player-warp cost/names, banner). The startup banner was rendered through Adventure's ANSI serializer. Not run on a live server (no Paper server jar reachable here).

## Round 17: chat filter word lists and accuracy

### Word lists (features/chatfilter/blocked.yml, regenerated)
245 patterns in 12 rules (was about 80 in 7), each with its own strikes:
- **SLURS** (CANCEL, 3 strikes): racial, ethnic, homophobic, transphobic and ableist slurs; the worst are also caught inside other words, while "snigger" stays clean.
- **HATE** (new, CANCEL, 3): extremist slogans (heil hitler, 14/88, white power...), "gas/kill/hang the <group>", "<group> should die", holocaust denial.
- **THREATS** (CANCEL, 2): kys and every "kill/neck/rope yourself" form, go die, hope you die, drink bleach, dox/swat/ddos threats, "I know where you live". PvP talk ("I'll kill you in the duel", "if you just die you respawn") is left alone.
- **SEXUAL_VIOLENCE** (new, CANCEL, 2): rape, paedophilia, loli/shota, child abuse (moved out of PROFANITY, where it was only masked).
- **PROFANITY** (REPLACE, 1): every f-word and s-word form, also inside other words (clusterfuck, bullshit), plus British and American insults.
- **SEXUAL** (new, REPLACE, 1): sexual words, split out so a younger server can switch it to CANCEL.
- **MILD** (new, off by default): damn, hell, crap, wtf... for family-friendly servers.
- **PERSONAL_INFO** (new, CANCEL, 0 strikes, bypass `vexcore.chatfilter.bypass.personalinfo`): e-mail addresses and phone numbers.
- ADVERTISING, LINKS, SCAM and CUSTOM are kept as they were.
- `exceptions:` innocent words that contain a caught one (Scunthorpe, shitake, cocktail, peacock, therapist, Nigeria, snigger, Moby Dick...) are never caught.
- `tools/gen_blocked.py` builds the file; edit its lists and rerun it from the project root to regenerate (it keeps your CUSTOM rule).

### Accuracy fixes
- A hit made only of digits no longer counts. Leetspeak folding read prices and coordinates as words ("455" as "ass", "7175" as "tits"); `5h1t` is still caught because it has a letter.
- Short roots only take safe endings, so "spicy", "tardy", "cocky", "cumin", "booby trap" and "pricked" are not caught.
- Messages that are mostly symbols or emoji are blocked (`symbols` in config.yml: over 60% of a message of at least 12 characters).

### Speed
- Scan passes that would read the same text again are skipped (plain chat usually needs one): about 23% faster; a normal message takes about 0.35 ms on the async chat thread with the new lists.
- The exception words are one combined pattern: one scan however many there are.

Validation: all 245 patterns compile in Java's regex engine. The real filter code was run against the generated file with 173 checks (slurs, hate, threats and profanity in plain, leetspeak, spaced-out, look-alike and fullwidth forms, plus 90+ innocent Minecraft messages, names, places and numbers that must stay clean): all pass. New unit tests cover the same. Not built with Maven here.

### Giveaways: money or items (finished)
- The choice is complete from start to payout: the Choose Prize menu, the money menu, a money preview, money claims paid to the balance, and `%prize%` in every announcement.
- `winner`, `you-won` and `no-entries` now show the prize (`%prize%`, e.g. "$5,000.00" or "12 items").
- The Create menu has a BACK button (slot 36) to the prize choice. Items put in are given back.
- Opening Create checks the same rules as START (cooldown, max running, a start still saving), so a player is told before putting items in.
- The money preview uses the new `money` template and `money-slot` in `gui/preview.yml`. An older preview.yml without them still shows a gold ingot in slot 22.
- The main list's "entered" template shows the prize too.

Validation: `GiveawayFeature` type-checks with javac 21 against stubs of the Paper and VexCore APIs it uses (a negative test confirms the check catches missing methods). All giveaway YAML files parse. Not built with Maven here (the Paper repository is unreachable from this environment) and not tested on a live server.

## Round 16: punishments, staff mode, staff essentials, votes, events, daily streaks, keyall

### Punishments (`features.punishments`, new)
- `/ban`, `/ipban`, `/mute` `<player> [duration] [reason] [-s]` (no duration = permanent; `/tempban`, `/tempmute` are aliases), `/kick`, `/warn`, `/unban`, `/unmute`, `/unwarn` (newest warning). `-s` = silent (only `vexcore.punish.notify` see it).
- `/punish <player>`: a menu of configurable reasons; each has a ladder of durations that climbs with every earlier punishment for that reason. Right click = silent. Shows whether they are banned/muted and their warnings.
- `/history <player>`: every punishment with status (active/expired/lifted by); right click an active one to lift it (`vexcore.punish.lift`).
- Bans are checked at login with a configurable ban screen (appeal link, id, time left). IP bans ban the player's network through IP protection's hashes and kick every account on it. Mutes block chat and the `muted-commands`.
- Warnings expire after `warnings.expire-days`; `warnings.actions` run commands at N active warnings (default 3 → 1h mute, 5 → 1d ban).
- Permanent punishments, and temporary ones over `max-temporary`, need `vexcore.punish.permanent`. `vexcore.punish.exempt` can't be punished by players.
- Active bans and mutes are cached and re-read every `sync-seconds`, so servers sharing MySQL see each other's. Discord webhooks for punishments and lifts. New table `punishments`.
- The report screen has a PUNISH button for the offender.

### Staff mode (`features.staffmode`, new)
- `/staff`: saves inventory, game mode, flight, level and invulnerability (also in the database, so a crash restores them at the next join), turns on vanish and flight, and gives configurable hotbar tools: vanish toggle, random teleport, freeze (screenshare), inspect inventory, punish, rollback, reports, online players menu (teleport / punish / inspect) and leave.
- Tools can't be dropped or moved; no picking up, building or hitting while in staff mode (configurable). Leaving, logging out and reloads restore everything.

### Staff essentials (`features.staffessentials`, new)
- `/fly`, `/heal`, `/feed`, `/god` `[player]`, `/speed <1-10> [walk|fly] [player]`, `/gmc` `/gms` `/gma` `/gmsp` `[player]`, `/invsee <player>`, `/clearinventory [player]`. Others need `<permission>.others`.

### Votes (`features.votes`, new)
- NuVotifier votes, hooked at runtime (no hard dependency). Each vote pays money, runs commands and rolls bonus rewards; votes for offline players are delivered when they join.
- `/vote`: a menu of the vote sites, each showing whether you can vote there again (click for the link), your monthly and total votes, and the party. `/votetop`: this month's top voters. `/voteparty`: progress (admin: `start`). `/fakevote <player> [site]` to test.
- Vote party every `party.votes` votes, rewarding everyone online (one account per network via IP protection). New tables `votes`, `vote_sites`, `vote_queue`.

### Server events (`features.events`, new)
- Chat games (unscramble, maths, type a code), mining / mob hunt / fishing / PvP competitions (placed blocks, spawner mobs and farmed kills don't count) and King of the Hill (`/event koth set [radius]`; contested hill pauses the clock).
- Rotate automatically every `auto.interval` (45m) with a minimum player count, or `/event start <type>` / `/event stop`. Boss bar with time, leader or capture progress; rewards for the top places.

### Daily rewards: login streak
- Claiming again within cooldown + `grace-hours` continues the streak. Every day multiplies the money prizes (`bonus-per-day` 10%, up to `max-multiplier` 3x) and every `extra-pick-every-days` (7) adds a pick. Milestone days (7, 30) give extra commands and a broadcast. Missing a day resets it (the player is told). Shown in the menu and as `%vexcore_daily_streak%`. The `daily` table gains a `streak` column automatically.

### Keyall
- Keyalls were already automatic (every `interval`, 1h). New: `align-to-clock` (on by default) runs them on the hour, and a countdown boss bar shows for the last `bossbar.show-minutes`.

### Internals
- `Database.addColumn` upgrades older tables safely (used by invest and daily).
- Vanish has a public `setVanished` for staff mode; IP protection exposes network hashing for IP bans.

Validation: every changed file parses; every new and changed feature type-checks against API stubs with no errors; all YAML parses without duplicate keys; command names and aliases checked for clashes. Not built with Maven here; not tested on a live server.

## Round 15: invest return rate and cap

- `income.rate-per-second` (default 0.00001): what each invested dollar earns per second. Replaces the `per` / `per-add` pair, which is still read if your file has it and no rate is set, so existing servers earn exactly as before. The bundled config has a table of rates with income per hour and payback time.
- `max-limit` (default 100,000,000): a hard cap on anyone's invest limit, whatever admins (`/invest limit add`) or prestige add. 0 = no cap. Money invested above it before the update stays; only adding is blocked.
- Menu placeholders `%rate%` (per second, percent), `%hourly%` (this investment per hour online), `%max_limit%`; PlaceholderAPI `%vexcore_invest_hourly%`.

Validation: parses and type-checks against API stubs; YAML checked. Not built with Maven here.

## Round 14: IP protection against alts, paged homes

### IP protection (`features.ipprotection`, new)
Alt accounts can no longer multiply grind rewards. Accounts are grouped by network (IP address, stored only as a salted SHA-256 hash; the salt is generated into `data/ipprotection.yml`).
- **Invest:** of the accounts online from one network, only the first `max-accounts-per-ip` (default 1, by join time) earn income; the others are told once. One account per network may invest (`/invest add`), and only that account receives offline income. The menu shows `%earning%`.
- **Keyall:** only the network's first account(s) get keys.
- **GG waves** (manual and store purchase): one reward per network per wave.
- **Giveaways:** one entry per network per giveaway.
- **Kill rewards / playtime rewards:** each reward can be claimed by one account per network.
- **Daily reward:** one claim per network per cooldown.
- **Kill farming:** killing an account from your own network, or the same victim again within `same-victim-cooldown-minutes` (10), doesn't count: it is taken off the vanilla kill statistic (which kill rewards use) and VexCore's kills/streak stats, and the killer is told why.
- `/alts <player>` (aliases `/altcheck`, `/dupeip`; `vexcore.ipprotection.alts`) lists accounts that played from the same network.
- `vexcore.ipprotection.bypass` (default false, not even ops) treats an account separately (trusted households); `exempt-ips` never limits a network (school, café). Each system can be switched off under `protect:`.
- New tables `ip_accounts` and `ip_claims`. Claims made before this update aren't known, so the protection applies from now on.

### Homes
- The home menu has pages. Each page holds as many homes as `home-slots` lists (14 by default); previous/next buttons appear when there is another page, and the page a player was on is remembered (going back from the dialog or the delete confirmation returns to it). `%page%`, `%pages%`, `%max%` placeholders.
- `max-homes` can be any number (bundled default is now 28 = 2 pages).
- `permission-homes`: your own permission nodes and the homes each gives (e.g. `vexcore.homes.vip: 5`), on top of `vexcore.home.<n>` and `default-homes`.
- `show-locked-homes`: false hides homes a player can't own, so their pages end there.

Validation: every changed file parses; IP protection, invest, GG wave, kill/playtime rewards, daily and keyall type-check against API stubs with no errors. Giveaway, stats and homes changes were reviewed by hand (too many dependencies to stub). Not built with Maven here; not tested on a live server.

## Round 13: invest and chat filter upgrades

### Invest
- `/invest withdraw <amount|all>` (and a WITHDRAW button): take part of the investment out, minus `withdraw.fee-percent` (default 50, the same loss as deleting, so the economy balance doesn't change until you lower it). `withdraw.keep-minimum` stops leaving less than `minimum` invested.
- Offline income: `offline-income.percent` (25) of the normal rate for up to `max-hours` (12) away, paid on return (absences under `min-minutes` pay nothing, so reloads don't count). The player is told how much and for how long; auto-collect pays it straight out.
- Lifetime earnings: tracked per player, shown in the menu and as `%vexcore_invest_earned%`.
- `/invest top` (and a TOP INVESTORS button): the biggest investments with income and lifetime earnings.
- The `invest` table gains `earned` and `last_seen`; older tables are upgraded automatically on start (SQLite and MySQL).
- Fixed: the delete refund was rounded to 2 decimals whatever the economy's decimals, so a 0-decimal economy could be paid a fraction. It is now rounded down to what the economy holds.
- Faster: income settings are read once per reload instead of for every investor every second; accounts with nothing invested are skipped before any other work.

### Chat filter
- Strikes and automatic mutes: each rule hit adds strikes (`strikes:` per rule in blocked.yml, default 1, WARN 0). Crossing a level in `strikes.punishments` mutes the player's chat, /msg and team chat (default 3 → 5m, 5 → 30m, 8 → 2h) and can run console commands. Strikes fade after `forget-minutes` without a new one; mutes survive relogs. Staff with alerts are told.
- `allowed:` list: your own domains and invites are taken out before the rules look, so ADVERTISING and LINKS no longer block them. Plain text or `regex:`.
- Per-rule `commands:` in blocked.yml, run from the console on every hit.
- Join delay (`join-delay-seconds`, default 3): new arrivals can't chat for a moment, which stops join-and-spam bots.
- New subcommands: `/chatfilter remove <word>` (undo `regex`), `/chatfilter strikes|clear <player>`, `/chatfilter reload` (blocked.yml and allowed only, no full reload).
- Faster: every per-message setting is read once per reload instead of on every message, and the repeat check only folds text when it is on.

Validation: all changed files parse and type-check against API stubs with no errors. The filter's rule matching, the new allow-list and masking were executed against the real blocked.yml patterns (24 checks, all pass), and the date fallback was executed too. New unit tests were added for these (ChatFilterRegressionTest, DatesTest). Not built with Maven here; not tested on a live server.

## Round 12: phantom despawning, fixes and speed

- **Despawn phantoms** (`features.phantoms`). New /settings button "DESPAWN PHANTOMS" and `/phantoms` (aliases `/phantomtoggle`, `/togglephantoms`, permission `vexcore.phantoms`, default true). With it on, phantoms don't spawn within `radius` (50) blocks of the player, and any that fly in are removed every `check-seconds` (2) with a puff of smoke (`particles`). Each phantom is removed on its own thread (Folia-safe). Saved per player like the other toggles.
- **Fixed: a typo in a date pattern broke features.** `/sign` (`date-format`), pay history and coinflip history (`history.time-format`) and the chat-filter log (`log.time-format`) threw an error every time when the pattern was invalid. All dates now go through `core/Dates`: each pattern is checked once, a bad one logs one warning and falls back to `dd.MM.yyyy HH:mm`. Formatters are cached instead of rebuilt per menu entry.
- **Faster /mobtoggle.** The spawn check looked at every player in the world for every mob that spawned. It now keeps the set of players who have the toggle on and skips the check entirely when nobody does.
- **Store boss bar.** The goal list is read once per reload instead of every second, and the boss bar is only updated when its text or progress actually changes (it used to resend every second).
- **Reports.** Resolved reports kept in memory are capped at the latest 200, and old report cooldowns are pruned, so neither grows without end on a long-running server.

Validation: all changed files parse; the new and reworked features type-check against API stubs with no errors or warnings; all changed YAML parses without duplicate keys. Still **not built with Maven** here and not tested on a live server.

## Round 11: reports, inventory rollback, Tebex goals and purchase GG waves

- **Reports** (`features.reports`). `/report <player>` opens a Paper dialog (offender's head, a reason box, Submit/Cancel) with open and close sounds; `/report <player> <reason>` skips the dialog. Reports are numbered #0001, #0002... (`first-number`), saved in a new `reports` table, announced to staff with `vexcore.reports.notify` (click to open) and sent to a Discord webhook (`webhook`, off until a url is set). `/reports` lists pending reports; a report opens a 27-slot menu: reporter head (11, click to teleport), the report on paper (13, every detail, and the resolve command), offender head (15, click to teleport), back (22). `/report resolve <number>` (`vexcore.reports.manage`) closes it, tells the reporter and can send `webhook-resolved`. Cooldown, min/max reason length, self-report and an exempt permission are configurable. Player-typed reasons are stripped of colour codes, tags and `%`, and are escaped for Discord; webhooks can only ping role ids listed in `ping-roles`.
- **Inventory rollback** (`features.invrollback`). `/invrollback <player>` (aliases `/rollback`, `/invrb`, `/restoreinv`) opens "Rollback | <player>": AUTO, JOINS, DEATHS and QUITS, each with how many are saved. A kind lists its backups newest first (date, item count, trigger, time ago). Left click restores onto the online player (`vexcore.invrollback.restore`), right click previews the exact inventory with armour and offhand. Backups: every `interval-minutes` (skipped when unchanged), on join, on death (before drops) and on quit, including players online when the server stops. The newest `keep` per kind are kept. New table `inv_backups`. Offline players can be looked up by name from their backups; restoring needs them online.
- **Tebex store goals** (`features.tebex`). With the server's secret key, the Tebex Plugin API is checked every `poll-seconds` for new payments (the first check only learns existing ones). A boss bar shows the goal: VexCore's own goal list (money counted from payments, several goals with console reward commands, reset or stay after the last) or a Tebex community goal. The bar shows a thank-you title after a purchase and a GG title during a GG wave. `/goal` shows progress; `/goal set|add|reset|refresh` for `vexcore.goal.admin`. `/tebexpurchase <player> <amount> [package]` announces instantly (for a Tebex package command; then set `poll-payments: false`). State is kept in `data/tebex.yml`.
- **Purchase announcement and GG wave**. A purchase draws the buyer's face (skin face plus hat layer, 8x8) in chat with configurable lines beside it ("Say GG for $50K money!"), plays a sound and starts a GG wave paying `purchase.gg-wave.money` (default 50,000) to each player's first gg. `/ggwave` gains `money:` and a `rewarded` message; waves started by a purchase replace a running one so everyone can gg again.
- Menus accept `sounds.close`, played when the player leaves the menu (not when switching to another VexCore menu).
- New permissions in plugin.yml; `report`, `goal`, `goals`, `storegoal` added to the default command whitelist group, `reports` and `invrollback` to the staff group.

Validation: every changed Java file parses, and the new code type-checks against stubs of the Paper/Bukkit/Adventure/Gson and VexCore APIs it uses. **Not built with Maven here** (this environment can't reach the Paper repository), no tests run, no live server test. The old `target/` output was removed because it does not contain these features. Run `mvn clean package` before installing.

## Round 10 (part 2): remaining features and validation

- Ignore now filters team chat and Paper's public-chat audience even when VexCore's chat renderer is off. Third-party plugins that send chat directly are outside this listener.
- Direct configured messages receive `globalmessages.yml: default-sound` when they have no own sound. Explicit silence/disabled sounds and embedded sound actions are respected; broadcasts do not inherit the default. Existing teleport countdown sounds remain active once per second.
- Every bundled ERROR label uses `&#FF0000&lERROR`; existing message/menu values are normalized when read without rewriting the owner's files.
- Duels announce “Duel Accepted!” with a sound; require two safe, horizontally separated spots; wait for both successful teleports before the countdown; play countdown/go sounds; and hold horizontal movement during the countdown. No same-block fallback. Failed placement refunds wagers; duel refunds settle before player caches unload.
- Giveaway creation asks Money or Items. Money is withdrawn on start; both prize types use persistent winner/return claims. No entrants returns the prize to its owner. Failed deposit restores the claim. Pending claim deliveries are restored on orderly reload/stop. Completed giveaways publish results only after their database transaction commits.
- Kits retain persistent claim timestamps and gain `cooldowns-enabled` plus `kits.<kit>.cooldown-enabled`; the last partial second no longer permits an early claim. One-time rules are separate.
- `/ranktrial <player> <rank> <duration>` dispatches LuckPerms `parent addtemp` using UUID, bounded duration, allowed-rank list and configurable temporary modifier. Requires `vexcore.ranktrial` (op by default). Expiry is owned by LuckPerms. The message reports submission; LuckPerms' console response confirms success or rejection.
- Command whitelist defaults are strict and enabled on fresh installs. It filters tab roots and blocks non-listed/namespaced commands with the configured sound; permission groups and staff bypass remain. Existing explicit settings remain in force.
- `/pl`, `/plugins`, and namespaced forms have a configurable response in `features/commandroutes/config.yml`. `/help` and `/tutorial` run the guide command with its usual permission checks.
- Removed the small-caps feature, conversion command, setting, permission and automatic item/chat transformation. Incoming styled-text matching remains for chat filtering and GG triggers.
- Confirmed settings-menu mob toggle uses the same action/state as `/mobtoggle`. Clamped its radius and skip players whose settings are still loading. The bundled button explicitly says BLOCK MOB SPAWNING; ON means blocking the configured mob reasons/types.
- Toggles now also snapshot on autosave, quit and orderly shutdown, in addition to saving on change. Database completion callbacks may finish follow-up writes during orderly shutdown.
- Added Maven regression coverage for text parsing, filter evasions/cancellation/masking/false positives, duration validation, plugin-list aliases, message sound priority, kit cooldown boundaries, and bundled YAML/menu bounds.

Validation: JDK 21, `mvn clean package`, 11 tests passed, ProGuard 7.6.1 completed. No live Paper/Folia, LuckPerms, proxy, multiplayer or MySQL integration test was performed. See UPGRADE.md for installation and targeted in-game checks.

## Round 10 (part 1): team and baltop menus, RTP, home dialogs, chat

Done:
- Teams run from menus, like LifestealCore. `/team` opens the team menu: member heads, search, sort, invite, team info, leave or disband, team home (left click: go, right click: set, shift right click: remove) and PvP. Click a member to hand out rights (manage teammates, PvP, visit home, edit home, team chat), kick them, or make them owner. Kick, leave, disband and transfer ask first. Without a team, `/team` offers create and your invites. Rights are kept in the existing role column, and join dates are in a new table, so there is no migration. The `/team` subcommands still work. New: `/team delhome`, `default-permissions`, `input-seconds`, `no-home-text`.
- /baltop uses LifestealCore's layout: a full page of heads, with previous, current page and next along the bottom.
- Payment history shows the other player's head instead of red and lime dye.
- RTP menu: Overworld, Nether, End, and 1v1 next to them. A new `nether` world is in rtp/config.yml.
- `/endlock [on|off]` (vexcore.endlock) locks the End: RTP there is refused, and End portals are blocked too (endlock.block-portals). vexcore.endlock.bypass ignores the lock, and it survives restarts (data/endlock.yml).
- Homes: right-click a saved home for its dialog (Paper dialogs): Teleport, Change Icon, Rename, Delete, Back. Change Icon lists every item in the game with its picture and a search bar, 120 per page. Names and icons are saved in a new table, `home_meta`. The texts are in home/config.yml under `dialog`.
- Swear words (REPLACE rules) now go out masked, and the sender gets "That message is not allowed here" with the villager sound (`words.notify-on-mask`).
- Mentions: @name is coloured for everyone (`mentions.color`), and the pinged player gets "%prefix% <name> mentioned you!" with a sound. No ping if either player ignores the other.
- The /msg sender hears a sound too (`sent`), and `/balance <player>` plays the same sound as your own balance.
- FFA: the broadcast now plays its sound. The old code sent an empty message, which skipped the sound.


- Name tags are rooted to the head, the way LifestealCore does it. Every player's game is told the lines ride the player, including the owner's own game, so the tag sits on your head in F5 too. The server's own movement packets for the lines are dropped, so nothing pulls them off the head: no trailing, no floating, no wobble. Needs PacketEvents, as before.
- Every ERROR message now plays the villager "hmm" sound (`entity.villager.no`). It is set in globalmessages.yml with `error-sound` and `error-marker` (the word that marks an error message; "" turns it off). A message with its own sound under `sounds` keeps that sound.

## Fourth sweep: bugs, settings and speed

### License removed
- VexCore no longer has a license check or `license-key`: it is for your own network and simply starts.

### Bugs fixed
- Money: taking money now rounds up and paying out rounds down, so rounding can never create money. Vault calls made while the economy reloads no longer fail.
- Invest: collect, delete and auto-collect work in whole economy units (no leftover fractions).
- Duel:
  - A duel cancelled during the countdown can no longer start anyway.
  - Nether duels spawn at ground level (the roof left no room above).
  - The second start spot may now be up to 13 blocks away in its chunk (it was capped at 6), so `start.min-distance` works as written.
  - Deaths cancelled by another plugin no longer end a duel. The same fix applies to combat tag.
- Quests:
  - Placed blocks keep their "placed" mark when a piston pushes or pulls them, or when sand and gravel fall. They lose it when an explosion or physics removes them. Moving a placed block no longer turns it into free "mined" progress.
  - Items from droppers and dispensers no longer count as picked up (hopper, dropper, pick up, repeat).
  - Spam shift-clicking a craft counts once, correctly. A number key onto a taken hotbar slot counts nothing.
- Screenshare:
  - Freezing someone takes them off their boat or horse, and they can't mount anything while frozen.
  - The blocked teleport causes are now a list you can edit.
- `/team list` no longer shows vanished staff as online.
- A TPA countdown fails if the other player gets into a duel or combat in the meantime.
- FFA: dying and typing `/ffaaccept` again no longer teleports you back to the host.
- Tab completion no longer offers the real names of `/hide` players.
- Join messages: `%displayname%` no longer gives away a `/hide` player's real name.
- `/vexcore reload` tells anyone who was typing an amount in chat that the question was cancelled, so their next line doesn't go to public chat.
- Antilag removes each entity on its own thread (Folia-safe).
- The join counter file is written off the main thread and saved on shutdown.

### New settings (the defaults behave as before)
| File | Key | What it does |
|---|---|---|
| config.yml | `hide-commands-without-permission` | Hide commands players can't use from /help and tab. |
| config.yml | `menu-click-gap-ms` | Minimum time between two menu clicks. |
| config.yml | `invalid-material` | The item shown for a material that doesn't exist. |
| globalmessages.yml | `title-times` | Title fade-in, stay and fade-out, in ticks. |
| rtp | `ready-refill-ticks` | How often the ready-spot pools are refilled. |
| rtp | `safe-landing-effect` | The landing effect (for example `slow_falling`). |
| rtp | `safe-landing-block-damage`, `safe-landing-block-attacking` | What landing protection covers. |
| rtp, duel | `worlds.<name>.environment`, `max-y` | Which kind of world, and where the Nether search starts. |
| rtp, duel | `search.parallel`, `search.timeout-seconds` | How the spot search runs. |
| duel | `max-seconds` | Time limit for a fight. |
| duel | `start.title-times` | Title timing for the start titles. |
| duel | `start.slow-falling-extra-seconds` | Extra slow falling after the countdown. |
| duel | `blocked-biomes` | Biomes duels never start in. |
| quests | `ignored-gamemodes` | Game modes that make no progress. |
| quests | `playtime-afk-minutes` | When a player counts as AFK for PLAYTIME. |
| quests | `roll-check-seconds` | How often the board reset is checked. |
| quests | `track.interval-ticks` | Tracking action bar speed. |
| nametags | `join-delay-ticks`, `display.teleport-duration` | When tags appear, and how smoothly they follow. |
| mobtoggle | `block-reasons.natural` | Whether natural spawns are blocked (it used to be always on). |
| antilag | `entity-types` | What a clear removes (items, arrows, XP orbs...). |
| teams | `members-separator`, `list-size`, `disband-confirm-seconds` | /team info and list layout, and the disband confirm time. |
| coinflip, invest | `input-seconds` | Time to type an amount in chat. |
| coinflip | `animation.reveal-buffer-ticks` | Payout fallback after the animation. |
| boosts | `show-particles`, `show-icon` | Effect particles and icon. |
| nightvision | `show-particles` | Effect particles. |
| vanish | `actionbar-interval-ticks` | How often the "vanished" bar shows. |
| giveaway | `rules.min-duration-seconds` | The shortest giveaway. |
| screenshare | `kick-counts-as-logout`, `blocked-teleport-causes` | Logout punishment on kick, and blocked teleports. |
| scoreboard | `hide-numbers`, `join-delay-ticks` | The red numbers, and when the board shows. |

## RTP rework and third bug sweep

### RTP
- RTP is free: `cost` is gone.
- Instant: every RTP world keeps `ready-spots` (3) safe spots found in the background, so `/rtp` starts its countdown at once. The countdown stays.
- Each ready spot is checked again right before use, and one older than `ready-max-age-seconds` is thrown away.
- With nothing ready, four tries run at once instead of one after another. A search can never hang: after a minute it gives up.
- Safer spots:
  - Solid ground only: no leaves, magma or cactus.
  - Two truly clear blocks above (no fire, cobweb, berry bushes, wither roses, powder snow or portals).
  - Nothing dangerous one block around.
  - Inside the world border.
  - The Nether searches below its roof.
- After landing, for `safe-landing-seconds`:
  - slow falling;
  - no damage of any kind;
  - the player can't hurt anyone either, including with arrows, tridents, potions or TNT.

### Bugs fixed
- Money:
  - After `/vexcore reload`, shops and jobs plugins (through Vault) talked to the old economy, which could give free items or lose pay. There is now one Vault provider that always uses the running economy.
  - An amount smaller than the economy's decimals "succeeded" without changing the balance. It is refused now, and invest auto-collect pays in the economy's own units.
  - Invest no longer says "nothing to take" when deleting or switching to auto.
- Quests:
  - Crafting clicks that crafted nothing (full inventory, wrong item held) counted, which meant unlimited money. Only items that really land count now.
  - Place-and-break no longer farms PLACE_BLOCK, and drops from your own placed blocks don't count as pickups.
  - AFK players don't earn PLAYTIME.
- `/playerhide` made `/msg`, `/stp`, `/ss` and `/team invite` say everyone was offline. Only vanish hides players from commands now.
- Chat filter:
  - A swear no longer lets a scam, link or custom-blocked word through in the same message: every rule is checked.
  - Symbol-only messages ("?", ":)") are no longer "repeating".
  - A scanned command typed with nothing after it is no longer silently eaten.
- `/hide`:
  - Stays on through `/vexcore reload`.
  - The real name no longer leaks in mentions, `[inv]`/`[ec]`, or join, leave and death messages.
- Vanish:
  - Vanished staff aren't shown for a moment on reload.
  - They don't count in join/leave `%online%` or team online counts.
  - They show as "Someone" when they kill.
- Screenshare: ender pearls, chorus fruit and portals no longer free a frozen player.
- 1v1:
  - Fights end after `max-seconds` (600) with both wagers back.
  - Players can't hit anyone while they're protected before the start.
  - Getting combat-tagged while the spot is searched calls the fight off.
  - The second start spot is checked for safety.
  - Nether duels are supported.
- FFA:
  - `/ffaaccept` works once per event.
  - A player leaves the FFA when they die.
  - FFA kills have a leaderboard.
- Homes: `/home <n>` respects the current home limit and blocked worlds.
- `/echest <player>`: closes when the owner logs out, so their items can't be duplicated or lost.
- Giveaways: a failed save can't leave the giveaway running.
- `/tpauto` doesn't accept while you're in combat or a 1v1.
- A teleport countdown is cancelled when you ride away on a horse or boat.
- The `/settings` buttons respect their `permission:` (set for night vision, player hide, mob toggle, small caps and scoreboard).
- Deleting a key from a reward, level or kit entry no longer brings back the default from the jar.
- `/cf delete` without a game no longer starts the anti-spam wait.
- `/keyall set` no longer sends the wrong warning.
- Death messages skip deaths another plugin cancelled.
- The GG wave works for small-caps players.
- Team member names update after a name change.
- Admin tab completion suggests player names again where a player is expected.
- `%amount_raw%` added for daily reward commands.

## Speed and bug-fix pass

### Name tags
- With PacketEvents, the lines are fixed to the head: everyone else's game is told they ride the player, so they move with the head exactly. On the server the player never carries them, so teleports to other worlds (`/spawn`, `/rtp`, other plugins) keep working.
- Without PacketEvents they follow the player as before. The startup report says so.
- Fixes:
  - `/vexcore reload` could leave two tags.
  - Vanish and `/hide` now take the tag away at once.
  - Other team plugins could bring the vanilla name back.
  - Killed lines come back.
  - PacketEvents loading after VexCore is now picked up.

### Speed
- Config reads are cached, about 20x faster (measured against Bukkit's own config classes, with the same answers in 1,923 checks).
- Messages sent to many players are parsed once instead of once per player. Sounds are parsed once per message.
- The text cache keeps the lines in use.
- A clean chat message costs 79 µs in the chat filter instead of 125 µs. The patterns match exactly as before, checked on 200,000 random messages.
- Chat does mention work only when a message has an `@`.
- MySQL:
  - Batches go as one statement.
  - Prepared statements are cached.
  - Each job saves 2 round trips.
- SQLite keeps more in memory.
- Leaner hot paths:
  - Placeholders: lookups are cached, and `%vexcore_online%` no longer loops over every player.
  - Quest progress: no allocation per event.
  - Name tags: no timer, no stream and no new location per tick. On Paper they move with a plain teleport.
  - Command whitelist: a lookup instead of a new set per command.
  - Scoreboard: the line names are made once.

### Bug fixes
- Quests:
  - Creative and spectator players don't earn quest money.
  - Blocks you placed yourself don't count when broken again.
  - Items you dropped don't count when picked back up.
  - Shift-click crafting counts every item.
- Chat filter:
  - No longer blocks "good fight. gg", "spicy", "cocky", "how do i buy rank" or "selling for 1000000".
  - `/r` and team chat no longer trip slowmode or the repeat check.
  - The SCAM rule has a bypass permission.
- Chat:
  - Small caps keeps `@mentions`, `[item]`, links and `<3` working.
  - `/mentiontoggle` exists now.
  - With `apply-to: NAME`, the hover covers only the name.
  - Colour codes inside links are left alone.
- `/hide`: the name stays scrambled in chat, and `/msg` shows the hidden name.
- `/reply` works when vanished staff messaged you.
- `/rename`: the length and filter checks now look at what the item will actually show.
- Teams:
  - A blocked word like "mod" no longer blocks "Modern".
  - `/team invite` or `/team kick` with no name shows the usage line.
- Screenshare:
  - A ban during a screenshare no longer runs the logout punishment as well.
  - `/vexcore reload` no longer ends screenshares.
- Combat tags survive `/vexcore reload`.
- 1v1:
  - Pick a world without a wager (`/1v1 Steve end`).
  - The command's aliases work during a fight.
  - Better tab completion.
  - A spot is found on fresh maps.
- RTP and 1v1 work in the Nether.
- Money:
  - Money shows as $2.50, not $2.5.
  - `/pay` tells the sender what the receiver got after tax.
  - `/daily` no longer says "received" when the payment failed.
- Coinflip: a typo no longer starts the anti-spam wait.
- Key-all:
  - Warnings fire even after lag.
  - `/keyall set` and `/keyall force` give the right feedback.
- `/kit delete` removes kits made with `/kit save`.
- Invest events survive reloads and restarts.
- Boosts: bad `duration` or `cooldown` values are reported, and the hole in the menu is filled.
- GG wave: a second `/ggwave` can't reset who got paid.
- Giveaways: joining one that just ended says so.
- FFA:
  - A host who logged out no longer blocks new events.
  - FFA kills are counted.
- Stats and quests ignore deaths another plugin cancelled.
- Tab completion:
  - Player names are only suggested where the command expects a player.
  - Staff commands are hidden from players who can't use them.
- Menu buttons use their own feature's `{prefix}`.
- Durations too large to be real are refused instead of erroring.
- Kill rewards show "kills" after their numbers.
- Changes to sounds in globalmessages.yml apply after a reload.

## Wrap-up: license, ProGuard, checks and speed

### License
- Removed the old license check copied over from SetupCore. It verified the key as "SetupCore" against Pikz Studio's license server and sent that server the host's OS username, home folder and working directory.
- VexCore now checks its key with VexLicense (`license/VexLicense.java`). Without a valid key it stays off and says why; the key is never printed.

### ProGuard
- `mvn clean package` builds the obfuscated jar by default, and `-Ddev` builds a readable one. The rules are in `proguard.conf`.
- Jackson and the JSON library were removed. Nothing is shaded any more.
- Added PacketEvents 2.14.0 as a provided dependency. `/hide` needs it to compile.

### Fixes from the code check
- Money:
  - An offline balance change that timed out could still go through later. That doubled money on /pay and coinflip wins, and took money when another plugin was told the withdrawal failed. Now it either runs or is called off.
  - A payment that landed while its player was logging out was lost. It's now written.
  - 1v1 wagers vanished on /vexcore reload and on shutdown. They're refunded now, and a payout that can't go through is logged.
  - Coinflip winnings for someone who left in the same moment are kept for their next login. Paying an offline winner no longer freezes the server while it waits for the database.
  - /pay to an offline player answers when the database is done, instead of freezing while it waits.
  - Invest auto-collect dropped fractions of a cent every second, up to a third of the income. It now adds them up.
- Items:
  - Big giveaways (full shulkers) didn't fit in MySQL and could be lost. The column is larger now, and a giveaway that can't be saved is called off and its items go back.
  - A giveaway claim made while logging out comes back instead of vanishing.
- Quests:
  - A quest reward earned while logging out is still paid.
  - The board roll could wipe progress made at that same moment. Each quest now resets itself on first use.
- Staff:
  - Turning /playerhide off, or changing world, showed vanished staff again. Vanish and /playerhide now decide together.
  - Players frozen for a screenshare could still use team chat.
- Chat:
  - "Fancy font" letters (𝐟𝐮𝐜𝐤, 𝓯𝓾𝓬𝓴) got past the chat filter. They are caught now; all 56 filter tests pass.
  - `/chatfilter regex` while people were chatting could let a message through unfiltered.
- Folia:
  - `/nametags reload` and old tag lines after a teleport.
  - 1v1 start teleports.
  - [item]/[inv]/[ec] items are read on the player's own thread.
  - Coinflip no longer reads the creator's open menu from another thread.
  - Command-list updates and the /hide off switch run on each player's own thread.
  - `/echest <player>` refuses players in another region instead of editing their items from the wrong thread.

### Speed
- Added database indexes for payment and coinflip history, pending payouts, baltop, the chat log and giveaway claims.
- MySQL is only pinged after sitting idle, not before every job.
- Mob toggle reads its settings once, not on every spawn, and only checks players in the same world.
- The command whitelist is built once instead of on every command.
- The chat filter skips its Unicode work for plain ASCII messages.
- Quest rankings look up names only for the places that are shown.
- Nametags only update the vanilla-name team when a tag comes or goes.
- The invest menu only refreshes for players who have it open.

## Round 8: nametags, quest board, /hide, speed

### Nametags (LifestealCore style)
- Every line is its own text display above the head: background, shadow, see-through, scale, opacity, line width, view distance, all in `features/nametags/config.yml`.
- Lines can animate (frames with their own interval) and refresh on their own timers. Placeholders that don't resolve are dropped.
- The tag hides while sneaking, invisible, vanished, hidden, dead, in spectator or in a disabled world, and only players who can see the owner see it.
- The displays follow the player every tick instead of riding them, so teleports between worlds keep working.
- `/nametags reload`.
- On Folia the vanilla name above the head can't be hidden, so it shows under the display.

### Quest board (LifestealCore's)
- Every quest in LifestealCore's quests.yml (69), all 24 quest types.
- STEPS or RESET boards, target/money/bonus growing per step, a bonus for being first, and the board rolling on its timer.
- Board, detail and leaderboard menus (this board or all time), and action-bar tracking.
- Fixed: progress made just before a roll was saved under the new board.

### /hide
- Changes the player's name and skin for everyone else: tab list, above the head, chat (no rank, team or hover while hidden).
- Needs PacketEvents. Without it, `/hide` answers that it is disabled because of the missing dependency, and startup logs a warning.

### Speed
- Parsed text is cached, so the same lines aren't rebuilt every tick.
- The scoreboard only sends the lines that changed.
- The chat hover card is cached for a few seconds (`hover.cache-seconds`).
- Mentions and spam checks do less work per message.

## Rounds 4 to 7: chat, staff, events and quests

Chat is handled the way LifestealCore handles it (taken from the LifestealCore jar: its filter rules, checks and chat configs). MOTD, portals, spawners, server tools, arena reset and orders were dropped. `/hide` came in round 8.

### Framework
- `%vexcore_...%` placeholders work in every VexCore text, also without PlaceholderAPI.
- Small caps converter; a safe-spot finder for RTP and 1v1 (background chunk loading, block checks on the region's own thread, so it works on Folia).
- Join and leave messages stay quiet for vanished staff.

### Features

| Feature | What it does |
|---|---|
| chatfilter | LifestealCore's filter: slowmode, length, same-character spam, repeat (similarity %), shouting (LOWERCASE or CANCEL), then the rules in blocked.yml (ADVERTISING, LINKS, SLURS, THREATS, PROFANITY, SCAM, CUSTOM), each REPLACE, CANCEL or WARN with its own alert and bypass, run on the raw, folded and glued message. Alerts to staff, a chat log with /chathistory, /chatfilter regex, similar and test. Also scans /msg, team chat, renames, team names and chosen commands. |
| chat | "{prefix}{name} ▷ {message}" with LuckPerms prefix/suffix, a profile card on hover (unresolved lines dropped, supporter lines), a command on click. [item], [inv], [ec] (read-only snapshots), @mentions with action bar and sound. /chattoggle, /mutechat [time] (survives a restart), /clearchat. |
| msg | /msg, /reply, /msgtoggle, /ignore, /socialspy. Filtered, [item] works, vanish-safe. |
| staffchat | /staffchat [message], toggle, or start with #. [item] works. |
| commandwhitelist | Only listed commands work and show in tab completion; plugin:command is always blocked. Off by default. |
| rename | /rename <name>, /rename reset. Filtered, colours by permission, optional cost. |
| smallcaps | /smallcaps for chat and renames. |
| teams | Create, invite, join, leave, kick, promote, demote, transfer, rename, disband, info, list, friendly fire, team home, team chat (/tc or start with !). Names: 3-16 letters, numbers and _, a blocked list, and the chat filter. |
| vanish | Hidden from everyone without vexcore.vanish.see, silent join and leave, survives relogs and restarts, no pickup, no mob targeting, invulnerable. |
| screenshare | /ss freezes a player (and takes them to the room), their chat only reaches staff, logging out runs the logout commands. |
| stafftp | /stp, /stphere, /stpback without countdowns. |
| nametags, scoreboard | Group prefix, suffix and colour with tab sorting; an animated sidebar. Paper only. |
| stats, leaderboard | Kills, deaths, streaks, mob kills, blocks, 1v1 results; top lists of every stat, balance and prestige. |
| joincounter | The first-join welcome with the player's number. |
| announce | /announce: title, chat lines, sound. |
| antilag | Clears ground items on a timer with action bar countdowns; /antilag. Paper only. |
| ggwave | /ggwave: every gg turns into a coloured GG for a while, the first one pays rewards. |
| giveaway | Player item giveaways: items go into the database on start, winners take them from Your Items. |
| ffa | /ffa broadcasts an event, players click to join and are teleported to the host. |
| duel | /1v1 <player> [wager] [world]: a random safe spot, a countdown without damage, pearls or elytra, the first death pays both wagers. |
| quests | A quest board drawn every reset.hours, 20 quest types, rewards by difficulty and a first-to-finish bonus decided by the database. |
| rtp | /rtp menu or /rtp <world>: background safe-spot search (existing chunks first), countdown, cooldown and cost on arrival, safe landing. |

### Not done
- The SetupCore importer doesn't cover the new features.
- LifestealCore's quest board extras (steps mode, per-quest rankings) and RTP spot pools were left out.

## Round 3 — selling and kits

Shop, player vaults and crates were dropped from this round.

### Framework
- **Menus:**
  - A feature can run something when a menu closes, for any reason: closed, disconnect, death, opening another menu, or a reload. On disconnect this happens before the quit event and the final save.
  - Items put into a menu's editable slots now survive a redraw. If a slot stops being editable, its item goes back to the player.
  - Shift-clicking from your own inventory into a menu with editable slots only ever fills those slots. Before, an item could land in an empty button slot and be lost (this affected the trash bin too).

### Features

| Feature | What it does |
|---|---|
| sell | `/sell` opens a menu to put items in. Closing it (or the SELL button) sells them; what can't be sold goes back. `/sell hand`, `/sell all` (hotbar optional, never armour or offhand), `/worth [item]`. Prices per item in config.yml. The highest permission multiplier applies. Special items (custom name, lore, model), enchanted items and damaged items each have a rule (damaged items can sell for the durability left). Shulker boxes and bundles that still hold items are never sold. Items are only removed once the money is paid; if paying fails, everything is put back. |
| kits | `/kit` menu (left click claims, right click previews), `/kit <name>`, `/kit preview <name>`. Items are saved in game with `/kit save <name>`, so any item works exactly as it was. Per kit: name, icon, description, permission, cooldown, one-time, first-join, money and commands. Armour goes straight into empty armour slots. A kit that doesn't fit is refused, or dropped at the player's feet if configured. The claim is saved before anything is handed out. Admin: `/kit delete`, `/kit give` (ignores cooldowns), `/kit reset <player> [kit]`. |

## Round 2 — economy and rewards

### Framework
- **Money**: one way for every feature to charge and pay. It uses VexCore's economy when that is on, and any other Vault economy when it is off. A payment or charge that didn't go through is always reported, so nothing is handed out for free.
- **Amounts**: `1500`, `1.5k`, `2m`, `3b`, `1t`, `1q` and `1,000,000` all work. Negative numbers, `NaN`, `Infinity`, hex and exponents are refused.
- **Chat input**: menus can ask the player to type an amount in chat, with a cancel word and a timeout.
- **One-time rewards**: a shared claims table records each reward the moment it is claimed and refuses a second claim, even across a relog or a crash.
- **Reload report**: now also warns when a menu has fewer slots than the rewards, levels or picks it has to show.
- **SetupCore import**: now also copies balances, investments (with auto-collect) and prestige levels.

### Features

| Feature | What it does |
|---|---|
| economy | VexCore's own money, registered with Vault (priority configurable). `/bal [player]`, `/pay`, `/paytoggle`, `/baltop` (paged menu, re-sorted every minute), `/payhistory [player]` (paged menu), `/eco give/take/set/reset/freeze`. Offline players can be paid and charged safely. Optional payment tax, minimum payment and maximum balance. Paying is blocked while combat tagged. Payment history is pruned after `keep-days`. |
| coinflip | `/cf` menu of open games, `/cf create <amount>` (or `/cf <amount>`), `/cf delete`, `/cf toggle`, `/cf history`. The bet is taken on create and open games survive restarts. A flip is settled the moment it is joined; the animation only shows the result. Winnings that can't be paid right away are kept for the next login. Optional confirm menu, tax, minimum and maximum bet, broadcast threshold and anti-macro cooldown. |
| invest | Money put in pays out every second while its owner is online. Income piles up to collect, or goes straight to the balance with auto-collect. The limit is the default, plus admin bonuses, plus prestige bonuses. Deleting refunds a configurable percentage. Admins can run timed income events (`/invest event 2 1h`). |
| daily | Pick hidden slots once per cooldown. Each pick rolls a weighted reward (a random money range and/or commands). The claim time is saved before anything is handed out. |
| keyall | Everyone online gets a weighted reward (command lines, so any crate plugin works) every interval. Warnings before it, a minimum player count, `/keyall force` and `/keyall set`. The timer survives restarts. |
| playtime | One-time rewards for time played, using the server's own statistic, so time played before VexCore counts. Players are told when a reward is waiting. |
| killrewards | The same, for player kills. |
| prestige | Levels taken in order. Each can cost money (taken) and need kills and hours played (not taken). Each can run commands and permanently raise the invest limit. |
| boosts | Short potion boosts from a menu, sharing one cooldown that money can buy away. Each boost can need its own permission. |

### Fixed while finishing the round
- Coinflip: nobody can create or join a game until the open games have loaded after a start or reload. Before, a game created in that moment could be overwritten.
- Coinflip: if the joiner leaves mid-animation, both players are still told the result.
- Coinflip: in the rare case where a join fails at the same moment the creator opens a new game, the old bet is paid back instead of lost.
- Invest: the open menu is refreshed on the player's own thread (Folia).
- Invest: `/invest limit add all` is now saved for online players who had never invested.
- Invest: `/invest add all` with no room left says so, instead of "invalid amount".
- Invest: deleting an investment or turning on auto-collect no longer says "nothing to take".
- Invest: `/invest event stop` says when no event is running, and event messages reach the console too.
- Economy: `%vexcore_balance%` always prints plain digits (it could print `1.0E7`).
- Economy: `/baltop` never waits on the database to show your own balance.
- Economy and coinflip: history decides sent/received and won/lost by UUID, so a name change can't flip them.
- Playtime and kill rewards: if the money of a claimed reward can't be paid, the player is told and the console logs it.
- Daily: `picks` can no longer be higher than the number of pick slots, which made the menu impossible to confirm.
- Boosts: effects are looked up in `Registry.MOB_EFFECT`, the non-deprecated registry in 1.21.10.

## Round 1 — framework and first 24 features

### Framework

**Files**
- Top-level files: config.yml, database.yml, commands.yml, globalmessages.yml.
- Each feature has a folder `features/<name>/` with its config.yml, plus a `gui/` folder when it has menus.
- `data/` holds server data.
- Missing files are restored with their defaults on start and on reload.

**Commands** (commands.yml)
- Every command can be enabled or disabled, renamed, aliased, and given its own permission, description and usage line.
- `shortcuts` define new command names that run another command with fixed arguments.
- Turning a feature off removes all of its commands from the server.
- When a command name was taken from another plugin, VexCore gives it back when the command is removed.

**Menus**
- Every menu is defined in YAML: items, templates and sounds.
- Any item can carry command attachments: `[player]`, `[console]`, `[message]`, `[broadcast]`, `[actionbar]`, `[title]`, `[sound]`, `[chat]`, `[refresh]` and `[close]`.
- Built-in buttons use `function:` and are hidden when they don't apply, such as page arrows on a one-page menu.
- Every menu file ends with a `sounds:` section. Every click plays a sound by default.
- Menus are protected by their holder, not their title:
  - clicks and drags are cancelled;
  - only top-inventory clicks count;
  - the double-click collect is blocked.

**Messages**
- Each feature can override any global message.
- Messages can be a list of lines and can use `[actionbar]`, `[title]` and `[sound]` lines.
- A message's sound goes next to it as `sounds.<key>`. Every success message ships with a sound.

**Text**
- Supports `&` codes, `&#hex`, bare `#hex`, MiniMessage and PlaceholderAPI, in any combination.
- A colour code resets bold and the other formats, as in vanilla.
- Text that players type is never parsed for formatting.

**Database**
- SQLite or MySQL/MariaDB.
- A table prefix can be set.
- All work runs on one ordered database thread, one transaction per job.

**Player data**
- A load that finishes after its session ended cleans up after itself.
- Nothing is saved before a player's data has loaded.
- Changes are refused until loading finishes.

**Reload** — `/vexcore reload`
- Restarts every feature from its files and reopens the database.
- Prints a report with one line per feature and every problem found in the files.

**Import** — `/vexcore import setupcore [confirm]`
- Copies homes, spawn, afk area, tpa settings and toggles.
- Each feature is rolled back on its own if its part fails.

**Other**
- Folia-safe scheduling everywhere.
- Loads at STARTUP so other plugins can find the economy (round 2).
- Location data is resolved lazily, so worlds that load late never break anything.
- SetupCore's license check is kept as it was.

### Features

| Feature | What changed from SetupCore / LifestealCore |
|---|---|
| spawn | Multiple named spawns. First-join placement happens before the world is sent, so there is no flicker. Join and respawn placement are optional; respawn can ignore beds. `/spawn <name> <player>` sends another player. |
| afk | Countdown teleport. |
| home | Numbered homes. The limit comes from `vexcore.home.<n>` (highest wins) or the default. The menu has a teleport row and a delete row, and lets you save a home straight from an empty slot. Delete can ask for confirmation, and any world can be blocked. Changes are written immediately. |
| tpa | Clickable accept and deny buttons that keep working after the commands are renamed. Adds tpdeny, tpacancel, tpahereall, auto-accept, per-type toggles and expiry. |
| combat | Tagging from melee, projectiles and TNT. Blocked commands match by id, name or alias, and also apply to menu buttons and shortcuts. Teleports are blocked or cancelled while tagged. Logging out while tagged kills the player. |
| settings | Paged menu of every toggle. Toggles of disabled features are hidden. Buttons for other plugins' toggles can be added. |
| nightvision | Stays through milk, totems, death, relogs and restarts. Potions the player drank are never removed. |
| playerhide | Works per world and only in the hider's own view. Never undoes a vanish plugin's hiding. An exempt permission keeps staff visible. |
| mobtoggle | Radius and spawn reasons are configurable, with a whitelist. |
| joinmessages / deathmessages | Joinmessages: join, first-join and leave messages. Deathmessages: per-cause texts in three groups, with the killer and weapon. Both can be toggled per player. |
| discord / store / apply | The whole message is clickable. |
| live | Checks the platform and applies a cooldown; players can toggle the announcements. |
| rules / guide / media / ranks | Pure menus, fully editable. |
| dropfix | Runs after every other plugin and respects keep-inventory. Can merge stacks. |
| workstations | Every station command can be turned off in commands.yml. `/echest <player>` opens another player's ender chest. |
| sign | Stamps the item once. Items signed by SetupCore count as already signed. |
| ping | Colour-coded ping. |
| trash | Clearing the inventory needs a right click. |
