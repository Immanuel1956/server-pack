# VexCore

All-in-one survival core by VexorStudios for Paper 1.21.10 and Folia (Java 21).
It combines SetupCore and LifestealCore, with no lifesteal features.

## Building

Build with a JDK 21 (not only a JRE) and Maven.

| Command | Result |
|---|---|
| `mvn clean package` | The obfuscated jar, `target/VexCore-1.0.0.jar`. This is the one to sell. |
| `mvn clean package -Ddev` | A readable jar without ProGuard, for testing. |

- ProGuard settings are in `proguard.conf`. Only VexCore's own classes are renamed. The class in plugin.yml, event handlers, the PlaceholderAPI hook and the PacketEvents bridge keep their names.
- Keep `target/proguard-mapping.txt` from every release you ship, and never publish it. ProGuard's retrace tool uses it to turn an obfuscated error from a buyer back into real class names and line numbers.

## Plugin folder

| Path | What it holds |
|---|---|
| `config.yml` | How often player data is autosaved, and an on/off switch for each feature. |
| `database.yml` | `SQLITE` (a file in `data/`) or `MYSQL`/MariaDB, plus a table prefix. |
| `commands.yml` | Every command. For each one you can set `enabled`, `name`, `aliases`, `permission`, `description` and `usage`. Also holds `shortcuts`. |
| `globalmessages.yml` | The shared prefix, error messages and teleport messages, the `/vexcore` messages, and their sounds. |
| `features/<name>/config.yml` | One folder per feature, with every setting, message and sound for that feature. |
| `features/<name>/gui/*.yml` | One file per menu. |
| `data/` | Server-local data: `spawns.yml`, `warps.yml` (server warps), `afk.yml`, `keyall.yml` (when the next key-all is due), `kits.yml` (the items of each kit), and `vexcore.db` when using SQLite. |

- Missing files are restored from the jar on start and on reload. Existing files are never overwritten.
- In settings files, a key you delete falls back to its default.
- In menu files, an item you delete stays deleted.

### Commands

| Command | What it does |
|---|---|
| `/vexcore reload` | Saves every online player, then stops all features, closes the database and rereads every file. It then reopens the database, starts the enabled features, re-registers commands and reloads the players. Afterwards it prints a report: one line per feature (its commands and menus), every problem it found (YAML errors, unknown materials or sounds, slots outside the menu), and a summary. |
| `/vexcore features` | Lists every feature as on, off or failed. |
| `/vexcore version` | Shows the version and the database type. |
| `/warp [name]`, `/warps` | Server warps (menu, or warp straight there). Staff: `/setwarp <name> [description]` (icon: the item in your hand), `/delwarp <name>`. |
| `/pwarp` | Player warps: browse, `/pwarp <name>`, `/pwarp set|delete|icon|desc <name>`, `/pwarp list [player]`, `/pwarp cost`. The first costs 100k, each more costs more; 3 slots, more with `vexcore.pwarps.<n>`. |
| `/giveaway cancel [n]` | Cancel your own giveaway (the prize comes back to Your Prizes). Staff: `/giveaway forcecancel <player> [reason]`. |
| `/alts <player>` | Every account linked to a player, all time (shared any network, or through another alt). |

- Turning a feature off in `config.yml` removes its commands, menus, listeners, timers and placeholders.
- A command can also be removed on its own with `enabled: false` in `commands.yml`.
- Renaming works the same way: `name: core` turns `/vexcore` into `/core`.

## Text

Any string in any file can use:

- `&` codes and hex colours: `&a`, `&#RRGGBB`, `#RRGGBB`, `&x&R&R&G&G&B&B`.
- MiniMessage tags: `<gradient:..>`, `<click:..>`, `<hover:..>`.
- `%placeholders%`, plus PlaceholderAPI placeholders when it is installed.

As in vanilla, a colour code ends the bold (and other formatting) that came before it.

A message can be one line or a list. An empty string sends nothing. A line can start with one of these:

| Prefix | Effect |
|---|---|
| `[actionbar]` | Shows the line above the hotbar. |
| `[title] Title\|Subtitle` | Shows a title and subtitle. |
| `[sound] name;volume;pitch` | Plays a sound. |

If `sounds.<key>` exists, that sound plays whenever message `<key>` is sent. VexCore looks for it in the feature's `config.yml` first, then in `globalmessages.yml`.

## Menus

Each menu file has these parts:

```yaml
title: "&8Title %page%/%pages%"
rows: 3                     # or size: 27
content-slots: "10-16"      # paged menus only
items:                      # placed where they say: add, move, restyle, remove freely
  anything:
    slot: 13                # or slots: "0-8, 45-53"
    material: DIAMOND
    name: "&bName"
    lore: ["line"]
    glow: true
    custom-model-data: 0
    item-model: ""
    skull: "%player%"       # a player name or a base64 texture
    permission: ""          # the item is hidden from players without it
    function: next-page     # built-in behaviour the menu offers; hidden when it doesn't apply
    commands: ["[player] spawn"]
    left-commands: []
    right-commands: []
    sound: ""               # this item's click sound; "" = silent
    close-on-click: false
templates:                  # the look of entries the feature fills in (homes, toggles, ...)
sounds:                     # open, click, page, and the feature's own sounds
```

Command attachment lines:

| Line | Effect |
|---|---|
| `[player] cmd` | The player runs the command. This is the default when no prefix is given. |
| `[console] cmd` | The console runs the command. |
| `[message] text` | Sends the text to the player. |
| `[broadcast] text` | Sends the text to everyone. |
| `[actionbar] text` | Shows the text above the player's hotbar. |
| `[title] a\|b` | Shows a title and subtitle. |
| `[sound] name;vol;pitch` | Plays a sound. |
| `[chat] text` | The player says the text in chat. |
| `[refresh]` | Redraws the menu. |
| `[close]` | Closes the menu. |

- Every click is cancelled.
- Clicks only count inside the menu, never in the player's own inventory.
- Items can't be taken out or dragged in. The exceptions are the slots of the trash bin and the sell menu.
- Shift-clicking an item from your own inventory into one of those menus only ever lands in those slots.
- Items put into those slots survive a menu redraw. When the sell menu closes for any reason (closed, disconnect, death, reload), what's in it is sold or given back. Nothing is lost.

## Database

- Every read and write runs on a single database thread, in the order it was requested.
- A player's data is marked *loaded* only after all of it has been read. Until then nothing of theirs is saved, and the plugin refuses changes for them.
- Homes and toggles are written the moment they change.
- Spawns and the afk area stay in `data/` as YAML, so servers that share one MySQL database never swap spawns.

## Chat filter

Every message runs through the checks in `features/chatfilter/config.yml` in order (slowmode, length and character spam, repeat, shouting), then through the rules in `blocked.yml`. The first one that blocks ends it. Every rule runs three times: on the raw message; on a folded copy (accents, zalgo, invisible characters, look-alike letters from other alphabets, fullwidth, small caps and leetspeak turned back into plain letters); and on a glued copy where `f u c k` and `f.u.c.k` read as one word again while `who read` stays two. `/chatfilter test <message>` shows which rule catches a message, `/chatfilter regex <word>` adds one, and `/chathistory` shows everything that was caught. Public chat, `/msg`, team chat, renames, team names and any commands listed under `scan.commands` all go through it.

VexCore's own `%vexcore_...%` placeholders work in every VexCore text even without PlaceholderAPI.

## Economy

VexCore has its own money and registers it with Vault, so shops, jobs and crate plugins all use
the same balances. Turning `economy` off in `config.yml` makes coinflip, invest, daily and the
rest use whatever other Vault economy is installed.

- Amounts can be typed as `1500`, `1.5k`, `2m`, `3b`, `1t` or `1q`. Negative numbers, `NaN`,
  `Infinity`, hex and exponents are refused.
- An online player's balance lives in memory and is written every `save-seconds` and on quit.
  An offline player's balance is changed in the database with one conditional statement, so a
  withdrawal can never take more than there is.
- Nothing can charge or pay a player whose data is still loading.
- A frozen balance (`/eco freeze`) can't pay or be paid; admin changes still work.
- Anything that fails to pay out stays safe. A coinflip win for a frozen player waits for their
  next login, and investment income that can't be paid stays collectable.

## Importing from SetupCore

`/vexcore import setupcore` first explains what the import copies. Nothing is copied until you run `/vexcore import setupcore confirm`.

- Source files: `plugins/SetupCore/setupcore.db` and `teleports.db`.
- What it copies so far:
  - homes
  - the spawn
  - the afk area
  - tpa settings
  - night vision, player hide, join-message and death-message toggles
  - balances
  - investments (with their auto-collect setting)
  - prestige levels
- Online players are saved before the import and reloaded after it.
- Each feature is imported on its own. If one feature fails, only that feature is rolled back.
- The importer grows with every round.

## Permissions and placeholders

Every permission is `vexcore.*`; `plugin.yml` lists them all.

Placeholders:

| Feature | Placeholders |
|---|---|
| home | `%vexcore_homes%`, `%vexcore_homes_max%` |
| warps | `%vexcore_warps%` (how many server warps) |
| pwarps | `%vexcore_pwarps%`, `%vexcore_pwarps_max%`, `%vexcore_pwarps_next_cost%` |
| combat | `%vexcore_combat%`, `%vexcore_combat_seconds%` |
| ping | `%vexcore_ping%` |
| settings | `%vexcore_toggle_<id>%` (for example `%vexcore_toggle_pay%`) |
| economy | `%vexcore_balance%` (plain number), `%vexcore_balance_formatted%`, `%vexcore_balance_short%`, `%vexcore_baltop_name_<place>%`, `%vexcore_baltop_balance_<place>%` |
| coinflip | `%vexcore_coinflip_games%`, `%vexcore_coinflip_wins%`, `%vexcore_coinflip_losses%` |
| invest | `%vexcore_invest_invested%`, `%vexcore_invest_pending%`, `%vexcore_invest_income%`, `%vexcore_invest_limit%` |
| daily | `%vexcore_daily%` (time left, or `placeholder-ready`) |
| keyall | `%vexcore_keyall%` |
| playtime | `%vexcore_playtime%` |
| killrewards | `%vexcore_kills%` |
| prestige | `%vexcore_prestige%`, `%vexcore_prestige_max%` |
| boosts | `%vexcore_boosts_cooldown%` |
| sell | `%vexcore_sell_multiplier%` |
| kits | `%vexcore_kit_<name>%` (Ready, the time left, Claimed or Locked) |
| teams | `%vexcore_team%`, `%vexcore_team_tag%`, `%vexcore_team_role%`, `%vexcore_team_members%`, `%vexcore_team_online%` |
| vanish | `%vexcore_vanished%`, `%vexcore_online%` (without vanished players) |
| stats | `%vexcore_stats_<stat>%` (kills, deaths, streak, best_streak, mob_kills, blocks_broken, blocks_placed, duel_wins, duel_losses), `%vexcore_stats_kdr%` |
| leaderboard | `%vexcore_top_<category>_<place>_name%`, `%vexcore_top_<category>_<place>_value%` |
| joincounter | `%vexcore_joincount%` |
| antilag | `%vexcore_antilag%` (time to the next clear) |
| quests | `%vexcore_quests_done%` |

## Rounds

| Round | Contents |
|---|---|
| 1 ✔ | Framework (files, commands, menus, messages, database, player data, reload, import); spawn, afk, home, tpa, combat, settings, nightvision, playerhide, mobtoggle, joinmessages, deathmessages, discord, store, apply, live, rules, guide, media, ranks, dropfix, workstations, sign, ping, trash |
| 2 ✔ | Economy (own Vault provider: pay, bal, baltop, eco admin), coinflip, invest, daily, keyall, playtime, killrewards, prestige, boosts |
| 3 ✔ | Sell/worth, kits (shop, player vaults and crates were dropped) |
| 4 ✔ | Teams and chat: chat filter (LifestealCore's rules), chat format and hover card, [item]/[inv]/[ec], mentions, /msg, staff chat, chat toggle/lock/clear, command whitelist, rename (small-caps feature removed in Round 10 part 2) |
| 5 ✔ | Vanish, screenshare, nametags, scoreboard, staff teleports |
| 6 ✔ | Antilag, /announce, GG wave, giveaways, FFA events, 1v1 duels, join counter, stats, leaderboards |
| 7 ✔ | Quest board, RTP |
| 8 ✔ | LifestealCore nametags (text displays fixed to the head with PacketEvents), LifestealCore quest board, /hide, speed pass |
| 9 ✔ | ProGuard, code check, speed and bug-fix pass |
| 18 ✔ | Server warps, player warps, all-time alt links, giveaway cancel and login win titles, startup banner, speed pass |

Dropped: MOTD, portals, spawners, server tools, arena reset, orders, the shop, player vaults and crates.

`/hide` (LifestealCore's disguise) needs the PacketEvents plugin. Without it VexCore still starts, and `/hide` says it is disabled because of the missing dependency.
